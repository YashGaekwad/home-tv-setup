package com.yashg.electricity;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * Full-screen MGVCL dashboard: latest day, this month, bill so far, month-end estimate and the daily chart.
 * It shows the cached reading at once and refreshes in the background (Refresher).
 */
public class MainActivity extends Activity implements Scraper.Listener {
    private static final long STALE_MS = 30 * 60 * 1000;

    private FrameLayout root;
    private View board;
    private TextView status, updated, footer, banner, todLine;
    private TextView todayVal, todaySub, monthVal, monthSub, billVal, billSub, estVal, estSub;
    private BarChartView chart;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        root = new FrameLayout(this);
        board = buildBoard();
        root.addView(board, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        Refresher.schedule(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Refresher r = Refresher.get(this);
        r.addListener(this);
        JSONObject last = Store.load(this);
        render(last);
        if (r.busy()) {
            status.setText(r.lastStatus());
        } else if (last == null || System.currentTimeMillis() - last.optLong("time", 0) > STALE_MS) {
            r.refresh();
        } else {
            status.setText("Up to date  ·  OK to refresh");
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        Refresher.get(this).removeListener(this);
        hideSite();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            Refresher.get(this).refresh();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            // Show the site itself, for a sign-in problem (an OTP, a changed password).
            if (board.getVisibility() == View.VISIBLE) showSite(); else hideSite();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BACK && board.getVisibility() != View.VISIBLE) {
            hideSite();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void showSite() {
        WebView w = Refresher.get(this).webView();
        if (w.getParent() == null) root.addView(w, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        board.setVisibility(View.INVISIBLE);
    }

    private void hideSite() {
        WebView w = Refresher.get(this).peekWebView();
        if (w != null && w.getParent() == root) {
            root.removeView(w);
            Refresher.get(this).releaseSoon();
        }
        board.setVisibility(View.VISIBLE);
    }

    // ---- Scraper.Listener (via Refresher) ----

    @Override
    public void onStatus(String text) {
        status.setText(text);
    }

    @Override
    public void onError(String text) {
        status.setText(text + "  ·  OK to retry");
    }

    @Override
    public void onResult(JSONObject dash, JSONObject usage) {
        render(Store.load(this));
        status.setText("Up to date  ·  OK to refresh");
    }

    // ---- display ----

    private void render(JSONObject all) {
        if (all == null) {
            status.setText("Loading…");
            return;
        }
        Store.Summary s = Store.summarize(all);
        SimpleDateFormat dayName = new SimpleDateFormat("EEE d MMM", Locale.US);
        if (s.latestKwh >= 0) {
            todayVal.setText(Store.kwh(s.latestKwh) + "  " + Store.rupees(s.latestRupees));
            todaySub.setText(dayName.format(s.latestDate) + (s.prevKwh >= 0 ? "  ·  day before " + Store.kwh(s.prevKwh) : ""));
        }
        monthVal.setText(Store.kwh(s.monthKwh));
        monthSub.setText(s.monthName + " so far  ·  " + String.format(Locale.US, "%.1f", s.perDay) + " a day");
        billVal.setText(Store.rupees(s.billSoFar));
        billSub.setText("if billed today  ·  " + Store.kwh(s.monthKwh));
        estVal.setText(Store.rupees(s.monthEndBill));
        estSub.setText("month end  ·  about " + Store.kwh(s.monthEndKwh));

        banner.setText(Store.alertLine(s));
        banner.setTextColor(android.graphics.Color.parseColor(Store.accent(s.level)));
        todLine.setText(s.tod.isEmpty() ? "" : "Time of day this month:  " + String.join("  ·  ", s.tod));
        todLine.setVisibility(s.tod.isEmpty() ? View.GONE : View.VISIBLE);

        int from = Math.max(0, s.values.size() - 30);
        double[] cv = new double[s.values.size() - from];
        String[] cl = new String[cv.length];
        Calendar c = Calendar.getInstance();
        for (int i = from; i < s.values.size(); i++) {
            cv[i - from] = s.values.get(i);
            c.setTime(s.dates.get(i));
            cl[i - from] = String.valueOf(c.get(Calendar.DAY_OF_MONTH));
        }
        chart.setData(cv, cl);

        footer.setText("Outstanding " + s.dash.optString("outstanding", "–")
                + "   ·   Billed up to " + s.dash.optString("billedUpto", "–")
                + "   ·   Power " + s.dash.optString("power", "–")
                + "   ·   Estimate: GERC slabs + ₹" + (int) Bill.FIXED_PER_MONTH + " fixed + ₹" + Bill.FPPPA_PER_UNIT
                + "/unit fuel + 15% duty");
        updated.setText(s.time == 0 ? "" : "Updated " + new SimpleDateFormat("d MMM, h:mm a", Locale.US).format(new Date(s.time)));
    }

    // ---- layout ----

    private View buildBoard() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(0xFF0B0D12);
        int pad = dp(40);
        col.setPadding(pad, dp(28), pad, dp(24));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = label("⚡  Electricity", 30, Color.WHITE, Typeface.create("sans-serif-light", Typeface.NORMAL));
        head.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        updated = label("", 15, 0x99FFFFFF, null);
        head.addView(updated);
        col.addView(head);

        status = label("", 15, 0xFFF5B700, null);
        status.setPadding(0, dp(4), 0, dp(14));
        col.addView(status);

        LinearLayout cards = new LinearLayout(this);
        TextView[] t = card(cards, "LATEST DAY");
        todayVal = t[0]; todaySub = t[1];
        t = card(cards, "THIS MONTH");
        monthVal = t[0]; monthSub = t[1];
        t = card(cards, "BILL SO FAR");
        billVal = t[0]; billSub = t[1];
        t = card(cards, "ESTIMATED BILL");
        estVal = t[0]; estSub = t[1];
        col.addView(cards);

        banner = label("", 18, 0xFF34C759, Typeface.create("sans-serif-medium", Typeface.NORMAL));
        banner.setPadding(dp(4), dp(16), 0, 0);
        col.addView(banner);
        todLine = label("", 15, 0xB3FFFFFF, null);
        todLine.setPadding(dp(4), dp(6), 0, 0);
        col.addView(todLine);

        TextView chartTitle = label("DAILY USAGE (kWh)", 13, 0x80FFFFFF, Typeface.DEFAULT_BOLD);
        chartTitle.setLetterSpacing(0.12f);
        chartTitle.setPadding(0, dp(20), 0, dp(6));
        col.addView(chartTitle);
        chart = new BarChartView(this);
        col.addView(chart, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        footer = label("", 13, 0x80FFFFFF, null);
        footer.setPadding(0, dp(10), 0, 0);
        col.addView(footer);
        return col;
    }

    private TextView[] card(LinearLayout parent, String caption) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF171A22);
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1), 0x1FFFFFFF);
        box.setBackground(bg);
        box.setPadding(dp(20), dp(16), dp(20), dp(16));
        TextView cap = label(caption, 12, 0x80FFFFFF, Typeface.DEFAULT_BOLD);
        cap.setLetterSpacing(0.12f);
        TextView val = label("–", 30, Color.WHITE, Typeface.create("sans-serif-light", Typeface.NORMAL));
        TextView sub = label("", 13, 0x99FFFFFF, null);
        box.addView(cap);
        box.addView(val);
        box.addView(sub);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        if (parent.getChildCount() > 0) lp.leftMargin = dp(16);
        parent.addView(box, lp);
        return new TextView[] {val, sub};
    }

    private TextView label(String text, int sp, int color, Typeface tf) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(sp);
        v.setTextColor(color);
        if (tf != null) v.setTypeface(tf);
        return v;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
