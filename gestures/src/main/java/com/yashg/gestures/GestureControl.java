package com.yashg.gestures;

import android.accessibilityservice.AccessibilityService;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Background gesture control. Runs the gesture engine while the screen is on and the switch is on, and acts:
 *  - scroll / arrows and OK: move or press the focused item through the screen's accessibility information (apps that
 *    draw their own screen, like many video players, may not respond);
 *  - back and home: system actions;
 *  - play / pause: the media key, which the playing app receives.
 * A small dot in the corner shows the camera is on (grey: watching for the wake palm; green: awake), with the last
 * gesture next to it for a moment. Enabled once with adb (settings enabled_accessibility_services).
 */
public class GestureControl extends AccessibilityService implements GestureEngine.Listener {
    static final String PREFS = "gestures", KEY_ON = "on";
    static final String ACTION_PAUSE = "com.yashg.gestures.PAUSE", ACTION_RESUME = "com.yashg.gestures.RESUME";

    private GestureEngine engine;
    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout badge;
    private View dot;
    private TextView label;
    private boolean pausedForTest;
    private final Runnable clearLabel = () -> {
        if (label != null) label.setText("");
    };

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (ACTION_PAUSE.equals(a)) pausedForTest = true;
            else if (ACTION_RESUME.equals(a)) pausedForTest = false;
            update();
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        startForeground(1, notification());
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(ACTION_PAUSE);
        f.addAction(ACTION_RESUME);
        registerReceiver(receiver, f);
        addBadge();
        update();
    }

    @Override
    public void onDestroy() {
        try {
            unregisterReceiver(receiver);
        } catch (Exception ignored) {
        }
        if (engine != null) engine.stop();
        removeBadge();
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {}

    @Override
    public void onInterrupt() {}

    /** Camera on only while the screen is on, the switch is on and the test screen is not using the camera. */
    void update() {
        boolean on = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_ON, true);
        boolean screen = getSystemService(PowerManager.class).isInteractive();
        boolean want = on && screen && !pausedForTest;
        if (want && (engine == null || !engine.isRunning())) {
            engine = new GestureEngine(this, this, false, false);
            engine.start();
        } else if (!want && engine != null) {
            engine.stop();
            engine = null;
        }
        main.post(() -> {
            if (badge != null) badge.setVisibility(want ? View.VISIBLE : View.GONE);
            setDot(false);
        });
    }

    // ---- GestureEngine.Listener (camera thread) ----

    @Override
    public void onGesture(Gestures.G g) {
        main.post(() -> {
            act(g);
            if (label != null) {
                label.setText(g.icon + " " + g.label);
                main.removeCallbacks(clearLabel);
                main.postDelayed(clearLabel, 1200);
            }
        });
    }

    @Override
    public void onAwake(boolean awake) {
        main.post(() -> {
            setDot(awake);
            if (label != null) {
                label.setText(awake ? "Gestures on" : "");
                main.removeCallbacks(clearLabel);
                if (awake) main.postDelayed(clearLabel, 1500);
            }
        });
    }

    @Override
    public void onError(String text) {
        main.post(() -> {
            if (label != null) label.setText(text);
        });
    }

    // ---- actions ----

    private void act(Gestures.G g) {
        switch (g) {
            case SWIPE_LEFT: case LEFT: move(View.FOCUS_LEFT); break;
            case SWIPE_RIGHT: case RIGHT: move(View.FOCUS_RIGHT); break;
            case SWIPE_UP: case UP: move(View.FOCUS_UP); break;
            case SWIPE_DOWN: case DOWN: move(View.FOCUS_DOWN); break;
            case OK: click(); break;
            case BACK: performGlobalAction(GLOBAL_ACTION_BACK); break;
            case HOME: performGlobalAction(GLOBAL_ACTION_HOME); break;
            case PLAY_PAUSE: media(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE); break;
            default: break;
        }
    }

    /** Move input focus to the next item in that direction on the current screen. */
    private void move(int direction) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focused == null) {
            // Nothing focused yet: focus the first focusable item.
            AccessibilityNodeInfo first = firstFocusable(root, 0);
            if (first != null) first.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            return;
        }
        AccessibilityNodeInfo next = focused.focusSearch(direction);
        if (next != null) next.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        else if (direction == View.FOCUS_DOWN || direction == View.FOCUS_UP) {
            // A list at its last visible item: let it scroll.
            focused.performAction(direction == View.FOCUS_DOWN
                    ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
        }
    }

    private void click() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        for (AccessibilityNodeInfo n = focused; n != null; n = n.getParent()) {
            if (n.isClickable()) {
                n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                return;
            }
        }
    }

    private AccessibilityNodeInfo firstFocusable(AccessibilityNodeInfo n, int depth) {
        if (n == null || depth > 30) return null;
        if (n.isFocusable() && n.isVisibleToUser()) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo f = firstFocusable(n.getChild(i), depth + 1);
            if (f != null) return f;
        }
        return null;
    }

    private void media(int keyCode) {
        AudioManager am = getSystemService(AudioManager.class);
        long t = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, keyCode, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, keyCode, 0));
    }

    // ---- the camera dot ----

    private void addBadge() {
        WindowManager wm = getSystemService(WindowManager.class);
        badge = new LinearLayout(this);
        badge.setGravity(Gravity.CENTER_VERTICAL);
        label = new TextView(this);
        label.setTextColor(Color.WHITE);
        label.setTextSize(16);
        label.setShadowLayer(6, 0, 0, Color.BLACK);
        label.setPadding(0, 0, dp(10), 0);
        badge.addView(label);
        dot = new View(this);
        badge.addView(dot, new LinearLayout.LayoutParams(dp(14), dp(14)));
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.x = dp(20);
        lp.y = dp(16);
        try {
            wm.addView(badge, lp);
        } catch (Exception e) {
            badge = null;
        }
        setDot(false);
    }

    private void setDot(boolean awake) {
        if (dot == null) return;
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(awake ? 0xFF34C759 : 0x99B0B0B0);
        d.setStroke(dp(2), 0x66000000);
        dot.setBackground(d);
    }

    private void removeBadge() {
        if (badge != null) {
            try {
                getSystemService(WindowManager.class).removeView(badge);
            } catch (Exception ignored) {
            }
        }
        badge = null;
    }

    private Notification notification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("gestures", "Gesture control", NotificationManager.IMPORTANCE_MIN));
        return new Notification.Builder(this, "gestures")
                .setContentTitle("Gesture control")
                .setContentText("Camera on while the TV screen is on")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .build();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
