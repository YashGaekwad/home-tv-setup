package com.yashg.gestures;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** The Gestures tile: gesture control on/off, the guided test, and how the gestures work. */
public class MainActivity extends Activity {
    private Button toggle;
    private TextView status;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(0xFF0B0D12);
        col.setPadding(dp(64), dp(48), dp(64), dp(40));

        col.addView(text("✋  Gesture control", 34, Color.WHITE, Typeface.create("sans-serif-light", Typeface.NORMAL)));
        status = text("", 18, 0xFFF5B700, Typeface.DEFAULT);
        status.setPadding(0, dp(8), 0, dp(24));
        col.addView(status);

        LinearLayout row = new LinearLayout(this);
        toggle = button("");
        toggle.setOnClickListener(v -> {
            SharedPreferences p = getSharedPreferences(GestureControl.PREFS, MODE_PRIVATE);
            p.edit().putBoolean(GestureControl.KEY_ON, !p.getBoolean(GestureControl.KEY_ON, true)).apply();
            // The service re-reads the switch when it hears from us.
            sendBroadcast(new Intent(GestureControl.ACTION_RESUME).setPackage(getPackageName()));
            refresh();
        });
        row.addView(toggle);
        Button test = button("Open gesture test");
        test.setOnClickListener(v -> startActivity(new Intent(this, TestActivity.class)));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tl.leftMargin = dp(20);
        row.addView(test, tl);
        col.addView(row);

        TextView how = text("How to use\n"
                + "1.  Raise your open palm, upright and still, for a second: the dot top-right turns green.\n"
                + "2.  Tilt the palm to your right / left and hold  =  move right / left (repeats).\n"
                + "3.  👍 thumbs up  =  up        👎 thumbs down  =  down\n"
                + "4.  👌 OK sign  =  select        ✊ fist next to your face  =  play / pause\n"
                + "5.  ✌ victory  =  back        🤟  =  home\n"
                + "After 10 seconds without a gesture it goes back to sleep (grey dot).\n\n"
                + "The camera is used only on this TV: nothing is recorded, saved or sent. "
                + "It stops when the TV screen is off or this switch is off.",
                18, 0xCCFFFFFF, Typeface.DEFAULT);
        how.setLineSpacing(0, 1.35f);
        how.setPadding(0, dp(36), 0, 0);
        col.addView(how);
        setContentView(col);
        toggle.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        boolean on = getSharedPreferences(GestureControl.PREFS, MODE_PRIVATE).getBoolean(GestureControl.KEY_ON, true);
        String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        boolean service = enabled != null && enabled.contains(getPackageName() + "/");
        toggle.setText(on ? "Turn gesture control OFF" : "Turn gesture control ON");
        if (!service) status.setText("Background service not enabled yet (needs one adb command, see README)");
        else status.setText(on ? "On: watching for the wake palm while the screen is on" : "Off: the camera is not used");
    }

    private Button button(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(18);
        b.setTextColor(Color.WHITE);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF232838);
        bg.setCornerRadius(dp(24));
        bg.setStroke(dp(2), 0x33FFFFFF);
        b.setBackground(bg);
        b.setPadding(dp(28), dp(12), dp(28), dp(12));
        b.setOnFocusChangeListener((v, has) -> bg.setStroke(dp(has ? 3 : 2), has ? 0xFFF5B700 : 0x33FFFFFF));
        return b;
    }

    private TextView text(String s, int sp, int color, Typeface tf) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setTypeface(tf);
        v.setGravity(Gravity.START);
        return v;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
