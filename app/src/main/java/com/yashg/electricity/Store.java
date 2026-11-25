package com.yashg.electricity;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** The last reading from the portal, and the figures the dashboard and the home card show from it. */
final class Store {
    /** A day under this many kWh is a day away (house locked, fridge and standby only); it is not "usual". */
    static final double AWAY_KWH = 2.0;

    private Store() {}

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("cache", Context.MODE_PRIVATE);
    }

    static JSONObject load(Context c) {
        try {
            String s = prefs(c).getString("last", null);
            return s == null ? null : new JSONObject(s);
        } catch (Exception e) {
            return null;
        }
    }

    static void save(Context c, JSONObject dash, JSONObject usage) throws Exception {
        JSONObject all = new JSONObject();
        JSONObject old = load(c);
        // The dashboard is read at most once a day; keep the last one in between.
        boolean fresh = dash != null && dash.length() > 0;
        if (!fresh && old != null) dash = old.optJSONObject("dash");
        all.put("dash", dash == null ? new JSONObject() : dash);
        all.put("dashTime", fresh ? System.currentTimeMillis() : (old == null ? 0 : old.optLong("dashTime", 0)));
        all.put("usage", usage);
        all.put("time", System.currentTimeMillis());
        prefs(c).edit().putString("last", all.toString()).apply();
    }

    static boolean dashStale(Context c) {
        JSONObject old = load(c);
        return old == null || System.currentTimeMillis() - old.optLong("dashTime", 0) > 24L * 3600 * 1000;
    }

    /** Everything worked out from one reading. */
    static final class Summary {
        final List<Date> dates = new ArrayList<>();
        final List<Double> values = new ArrayList<>();
        double latestKwh = -1, prevKwh = -1, latestRupees;
        Date latestDate;
        double monthKwh, perDay, billSoFar, monthEndKwh, monthEndBill;
        String monthName = "";
        long time;
        JSONObject dash = new JSONObject();
        /** ok, warn (heading past 100 units this month) or alert (past it, or a spike). */
        String level = "ok";
        double nextSlab = -1, rateNow, rateNext;
        String spike = "";
        double usualKwh = -1;
        /** Time-of-day share of this month's units, largest first, e.g. "Evening peak 31%"; empty when the portal has none. */
        final List<String> tod = new ArrayList<>();
        double nightShare = -1;
    }

    static String accent(String level) {
        return "alert".equals(level) ? "#FF453A" : "warn".equals(level) ? "#F5B700" : "#34C759";
    }

    static Summary summarize(JSONObject all) {
        Summary s = new Summary();
        if (all == null) return s;
        s.time = all.optLong("time", 0);
        JSONObject usage = all.optJSONObject("usage");
        if (all.optJSONObject("dash") != null) s.dash = all.optJSONObject("dash");
        if (usage == null) usage = new JSONObject();

        SimpleDateFormat in = new SimpleDateFormat("MMM dd,yyyy", Locale.US);
        JSONArray days = usage.optJSONArray("days");
        List<Integer> rawIndex = new ArrayList<>();
        if (days != null) {
            for (int i = 0; i < days.length(); i++) {
                JSONObject d = days.optJSONObject(i);
                if (d == null || d.isNull("v")) continue;
                try {
                    s.dates.add(in.parse(d.getString("d").replace(", ", ",")));
                    s.values.add(d.getDouble("v"));
                    rawIndex.add(i);
                } catch (Exception ignored) {
                }
            }
        }
        int n = s.values.size();
        Calendar latest = Calendar.getInstance();
        if (n > 0) {
            s.latestKwh = s.values.get(n - 1);
            s.latestDate = s.dates.get(n - 1);
            if (n > 1) s.prevKwh = s.values.get(n - 2);
            latest.setTime(s.latestDate);
        }
        double monthSum = 0;
        List<Integer> monthRaw = new ArrayList<>();
        Calendar c = Calendar.getInstance();
        for (int i = 0; i < n; i++) {
            c.setTime(s.dates.get(i));
            if (c.get(Calendar.MONTH) == latest.get(Calendar.MONTH) && c.get(Calendar.YEAR) == latest.get(Calendar.YEAR)) {
                monthSum += s.values.get(i);
                monthRaw.add(rawIndex.get(i));
            }
        }
        double soFar = parse(usage.optString("soFar"), 0);
        // The portal's "so far" can run a day ahead of the daily chart; take the larger.
        s.monthKwh = Math.max(soFar, monthSum);
        int dim = latest.getActualMaximum(Calendar.DAY_OF_MONTH);
        int elapsed = Math.max(1, latest.get(Calendar.DAY_OF_MONTH));
        s.perDay = s.monthKwh / elapsed;
        double projected = parse(usage.optString("projected"), 0);
        s.monthEndKwh = Math.max(s.monthKwh, projected > 0 ? projected : s.perDay * dim);
        s.billSoFar = Bill.total(s.monthKwh);
        s.monthEndBill = Bill.total(s.monthEndKwh);
        // What the latest day's units added to this month's bill, surcharges and duty included.
        if (s.latestKwh >= 0) s.latestRupees = s.billSoFar - Bill.total(Math.max(0, s.monthKwh - s.latestKwh));
        s.monthName = new SimpleDateFormat("MMMM", Locale.US).format(latest.getTime());

        // Slab: the 100-unit edge is where a unit goes from about ₹7 to about ₹7.70 all-in.
        s.nextSlab = Bill.nextBoundary(s.monthKwh);
        s.rateNow = Bill.unitRate(s.monthKwh);
        s.rateNext = s.nextSlab > 0 ? Bill.unitRate(s.nextSlab) : s.rateNow;
        if (s.monthKwh >= 100) s.level = "alert";
        else if (s.monthEndKwh >= 100) s.level = "warn";

        // Spike: the latest day against a usual day at home - the median of the 30 days before it, leaving out
        // near-empty days (under AWAY_KWH: the house locked, only the fridge running). A plain average of the
        // last week made an ordinary day look like a spike right after a holiday.
        if (n >= 8 && s.latestKwh >= 3) {
            List<Double> home = new ArrayList<>();
            for (int i = n - 2, k = 0; i >= 0 && k < 30; i--, k++) {
                if (s.values.get(i) >= AWAY_KWH) home.add(s.values.get(i));
            }
            if (home.size() >= 5) {
                java.util.Collections.sort(home);
                int m = home.size();
                double usual = m % 2 == 1 ? home.get(m / 2) : (home.get(m / 2 - 1) + home.get(m / 2)) / 2;
                s.usualKwh = usual;
                if (s.latestKwh >= Math.max(2 * usual, usual + 3)) {
                    s.spike = String.format(Locale.US, "%s used %.1f× a usual day at home (%s vs %s)",
                            new SimpleDateFormat("d MMM", Locale.US).format(s.latestDate), s.latestKwh / usual,
                            kwh(s.latestKwh), kwh(usual));
                    s.level = "alert";
                }
            }
        }

        // Time of day, from the chart's per-band series for this month's days.
        JSONArray series = usage.optJSONArray("series");
        if (series != null) {
            String[][] bands = {{"Peak_1_Morning", "Morning peak"}, {"Peak_2_Evening", "Evening peak"},
                    {"Off_Peak_1_Afternoon", "Afternoon"}, {"Off_Peak_2_Evening", "Early evening"},
                    {"Night", "Night"}, {"Rest", "Early morning"}};
            double[] tot = new double[bands.length];
            double bandSum = 0;
            for (int b = 0; b < bands.length; b++) {
                for (int j = 0; j < series.length(); j++) {
                    JSONObject o = series.optJSONObject(j);
                    if (o == null || !bands[b][0].equalsIgnoreCase(o.optString("name"))) continue;
                    JSONArray data = o.optJSONArray("data");
                    if (data == null) continue;
                    for (int idx : monthRaw) tot[b] += data.optDouble(idx, 0);
                }
                bandSum += tot[b];
            }
            if (bandSum > 0) {
                Integer[] order = new Integer[bands.length];
                for (int b = 0; b < order.length; b++) order[b] = b;
                java.util.Arrays.sort(order, (x, y) -> Double.compare(tot[y], tot[x]));
                for (int b : order) {
                    if (tot[b] <= 0) continue;
                    s.tod.add(bands[b][1] + " " + Math.round(100 * tot[b] / bandSum) + "%");
                }
                s.nightShare = tot[4] / bandSum;
            }
        }
        return s;
    }

    /** A few words for the card's status pill. */
    static String chip(Summary s) {
        if (!s.spike.isEmpty()) return String.format(Locale.US, "Usage spike · %.1f× usual", s.latestKwh / s.usualKwh);
        if (s.monthKwh >= 250) return String.format(Locale.US, "Top slab · ₹%.2f a unit", s.rateNow);
        if (s.monthKwh >= 100) return String.format(Locale.US, "Past 100 units · ₹%.2f a unit", s.rateNow);
        if ("warn".equals(s.level)) return "Near the 100-unit slab";
        return "On track";
    }

    /** One line for the card: the spike if there is one, else where the month stands against the 100-unit slab. */
    static String alertLine(Summary s) {
        if (!s.spike.isEmpty()) return "⚠ " + s.spike;
        if (s.time == 0) return "";
        if (s.nextSlab > 0) {
            double left = s.nextSlab - s.monthKwh;
            String when = s.monthEndKwh >= s.nextSlab ? "on course to cross it" : "on course to stay under";
            return String.format(Locale.US, "%s to the %d-unit slab · %s · then ₹%.2f a unit (now ₹%.2f)",
                    kwh(Math.max(0, left)), (int) s.nextSlab, when, s.rateNext, s.rateNow);
        }
        return String.format(Locale.US, "Top slab: ₹%.2f a unit", s.rateNow);
    }

    static double parse(String s, double fallback) {
        try {
            return Double.parseDouble(s);
        } catch (Exception e) {
            return fallback;
        }
    }

    static String kwh(double v) {
        return (v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.US, "%.1f", v)) + " kWh";
    }

    static String rupees(double v) {
        return "₹" + String.format(new Locale("en", "IN"), "%,d", Math.round(v));
    }
}
