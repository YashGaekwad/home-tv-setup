package com.yashg.electricity;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Home-utility rows for the launcher's utility card. Any app can publish rows the same way: a provider
 * with meta-data "com.gathin.tvhome.UTILITY_ROWS" and these columns. One row per meter or device.
 *
 *   title    "Electricity"
 *   accent   "#F5B700"
 *   primary  big figure, e.g. "₹840"
 *   detail   one line, e.g. "112 kWh in November · Today 5 kWh ₹38 · ~₹870 by month end"
 *   updated  epoch millis of the reading
 *   package  the app the card opens
 *   level    ok | warn | alert (the card tints itself: green, amber, red)
 *   alert    optional second line in the level's colour, e.g. a usage spike or the slab ahead
 *   primary_label        a few words under the big figure, e.g. "so far in November"
 *   stat1..3 / statN_label  up to three small figures with a label each, e.g. "99 kWh" / "used"
 *   spark    recent daily values, comma separated, oldest first (a small bar chart)
 *   chip     a short status, e.g. "On track" or "Near 100-unit slab", shown as a pill in the level's colour
 */
public class UtilitiesProvider extends ContentProvider {
    static final String AUTHORITY = "com.yashg.electricity.utilities";
    static final Uri ROWS = Uri.parse("content://" + AUTHORITY + "/rows");
    static final String[] COLUMNS = {"_id", "title", "accent", "primary", "detail", "updated", "package", "level", "alert",
            "primary_label", "stat1", "stat1_label", "stat2", "stat2_label", "stat3", "stat3_label", "spark", "chip"};

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        MatrixCursor c = new MatrixCursor(COLUMNS);
        Store.Summary s = Store.summarize(Store.load(getContext()));
        if (s.time == 0) {
            c.addRow(new Object[] {1, "Electricity", "#F5B700", "–", "Waiting for the first reading", 0L,
                    getContext().getPackageName(), "ok", "", "waiting for the first reading", "", "", "", "", "", "", "",
                    "Connecting"});
        } else {
            StringBuilder d = new StringBuilder();
            d.append(Store.kwh(s.monthKwh)).append(" in ").append(s.monthName);
            if (s.latestKwh >= 0 && s.latestDate != null) {
                d.append("  ·  ").append(new SimpleDateFormat("d MMM", Locale.US).format(s.latestDate)).append(" ")
                        .append(Store.kwh(s.latestKwh)).append(" ").append(Store.rupees(s.latestRupees));
            }
            d.append("  ·  ~").append(Store.rupees(s.monthEndBill)).append(" by month end");
            if (s.nightShare >= 0) d.append("  ·  night ").append(Math.round(100 * s.nightShare)).append("%");
            StringBuilder spark = new StringBuilder();
            for (int i = Math.max(0, s.values.size() - 14); i < s.values.size(); i++) {
                if (spark.length() > 0) spark.append(',');
                spark.append(s.values.get(i));
            }
            String day = s.latestDate == null ? "" : new SimpleDateFormat("d MMM", Locale.US).format(s.latestDate);
            c.addRow(new Object[] {1, "Electricity", Store.accent(s.level), Store.rupees(s.billSoFar), d.toString(), s.time,
                    getContext().getPackageName(), s.level, Store.alertLine(s),
                    "so far in " + s.monthName,
                    Store.kwh(s.monthKwh), "used",
                    Store.rupees(s.monthEndBill), "by month end",
                    s.latestKwh >= 0 ? Store.rupees(s.latestRupees) : "", day.isEmpty() ? "" : "on " + day,
                    spark.toString(), Store.chip(s)});
        }
        c.setNotificationUri(getContext().getContentResolver(), ROWS);
        return c;
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.dir/vnd.com.gathin.tvhome.utility";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        return 0;
    }

    static String time(long t) {
        return new SimpleDateFormat("h:mm a", Locale.US).format(new Date(t));
    }
}
