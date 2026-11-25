package com.yashg.gestures;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Range;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.core.Delegate;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer;
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Step 1 test: the webcam, MediaPipe's gesture recogniser and our gesture rules, with the speed on this TV.
 * Nothing is sent or saved; frames live only in memory for the moment they are processed.
 */
public class TestActivity extends Activity {
    private static final int W = 640, H = 480;

    private ImageView preview;
    private Overlay overlay;
    private TextView big, label, stats, raw;
    private HandlerThread camThread;
    private Handler camHandler;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader reader;
    private GestureRecognizer recognizer;
    private final AtomicBoolean busy = new AtomicBoolean();
    private int[] argb;
    private long frames, lastFpsTime;
    private double fps, inferMs;
    private Gestures.G last = Gestures.G.NONE, stable = Gestures.G.NONE;
    private int streak;
    private final Swipes swipes = new Swipes();
    private Gestures.G event = Gestures.G.NONE;   // the last swipe / play-pause, shown for a moment
    private long eventUntil;
    private int eventCount;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0B0D12);

        LinearLayout row = new LinearLayout(this);
        row.setPadding(dp(40), dp(36), dp(40), dp(36));
        FrameLayout cam = new FrameLayout(this);
        preview = new ImageView(this);
        preview.setScaleX(-1f);   // mirror, like looking in a mirror
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        cam.addView(preview, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlay = new Overlay(this);
        cam.addView(overlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        row.addView(cam, new LinearLayout.LayoutParams(dp(720), dp(540)));

        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setPadding(dp(48), 0, 0, 0);
        side.addView(text("GESTURE TEST", 14, 0x99FFFFFF, Typeface.DEFAULT_BOLD));
        big = text("—", 120, Color.WHITE, Typeface.DEFAULT);
        side.addView(big);
        label = text("Show a hand to the camera", 30, Color.WHITE, Typeface.create("sans-serif-light", Typeface.NORMAL));
        side.addView(label);
        stats = text("", 18, 0xFFF5B700, Typeface.DEFAULT);
        stats.setPadding(0, dp(24), 0, 0);
        side.addView(stats);
        raw = text("", 15, 0x80FFFFFF, Typeface.DEFAULT);
        raw.setPadding(0, dp(8), 0, 0);
        side.addView(raw);
        TextView help = text("☝ point left / right / up / down = arrows\n🤏 pinch = OK / open\n✋ palm = play / pause"
                + "\n👍 👎 = volume\n✊ fist = mute\n✌ victory = back\n🤟 = home", 17, 0xB3FFFFFF, Typeface.DEFAULT);
        help.setPadding(0, dp(28), 0, 0);
        help.setLineSpacing(0, 1.3f);
        side.addView(help);
        row.addView(side);
        root.addView(row);
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.CAMERA}, 1);
            return;
        }
        start();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) start();
        else label.setText("Camera permission is needed");
    }

    @Override
    protected void onPause() {
        super.onPause();
        stop();
    }

    private void start() {
        camThread = new HandlerThread("camera");
        camThread.start();
        camHandler = new Handler(camThread.getLooper());
        camHandler.post(() -> {
            try {
                long t0 = SystemClock.elapsedRealtime();
                recognizer = GestureRecognizer.createFromOptions(this, GestureRecognizer.GestureRecognizerOptions.builder()
                        .setBaseOptions(BaseOptions.builder().setModelAssetPath("gesture_recognizer.task")
                                .setDelegate(Delegate.CPU).build())
                        .setRunningMode(RunningMode.VIDEO)
                        .setNumHands(1)
                        .setMinHandDetectionConfidence(0.5f)
                        .setMinHandPresenceConfidence(0.5f)
                        .setMinTrackingConfidence(0.5f)
                        .build());
                long load = SystemClock.elapsedRealtime() - t0;
                runOnUiThread(() -> raw.setText("Model loaded in " + load + " ms"));
                openCamera();
            } catch (Exception e) {
                runOnUiThread(() -> label.setText("Could not start: " + e.getMessage()));
            }
        });
    }

    private void openCamera() throws Exception {
        CameraManager cm = getSystemService(CameraManager.class);
        String id = cm.getCameraIdList()[0];
        CameraCharacteristics cc = cm.getCameraCharacteristics(id);
        Range<Integer> best = null;
        Range<Integer>[] ranges = cc.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if (ranges != null) for (Range<Integer> r : ranges) if (best == null || r.getUpper() > best.getUpper()) best = r;
        final Range<Integer> fpsRange = best;

        reader = ImageReader.newInstance(W, H, ImageFormat.YUV_420_888, 2);
        reader.setOnImageAvailableListener(this::onImage, camHandler);
        //noinspection MissingPermission
        cm.openCamera(id, new CameraDevice.StateCallback() {
            @Override
            public void onOpened(CameraDevice c) {
                camera = c;
                try {
                    c.createCaptureSession(Collections.singletonList(reader.getSurface()), new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession s) {
                            session = s;
                            try {
                                CaptureRequest.Builder b = c.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                b.addTarget(reader.getSurface());
                                if (fpsRange != null) b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange);
                                s.setRepeatingRequest(b.build(), null, camHandler);
                            } catch (Exception e) {
                                runOnUiThread(() -> label.setText("Camera error: " + e.getMessage()));
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession s) {
                            runOnUiThread(() -> label.setText("Camera session failed"));
                        }
                    }, camHandler);
                } catch (Exception e) {
                    runOnUiThread(() -> label.setText("Camera error: " + e.getMessage()));
                }
            }

            @Override
            public void onDisconnected(CameraDevice c) {
                c.close();
                runOnUiThread(() -> label.setText("Camera unplugged"));
            }

            @Override
            public void onError(CameraDevice c, int error) {
                c.close();
                runOnUiThread(() -> label.setText("Camera error " + error));
            }
        }, camHandler);
    }

    private void onImage(ImageReader r) {
        Image img = r.acquireLatestImage();
        if (img == null) return;
        if (!busy.compareAndSet(false, true)) {
            img.close();
            return;
        }
        try {
            Bitmap bmp = toBitmap(img);
            img.close();
            long t0 = SystemClock.elapsedRealtime();
            GestureRecognizerResult res = recognizer.recognizeForVideo(new BitmapImageBuilder(bmp).build(), t0);
            long dt = SystemClock.elapsedRealtime() - t0;
            Gestures.G g = Gestures.classify(res);
            Gestures.G motion = swipes.update(t0, res.landmarks().isEmpty() ? null : res.landmarks().get(0),
                    Gestures.isPalm(res));
            String top = res.gestures().isEmpty() || res.gestures().get(0).isEmpty() ? "no hand"
                    : res.gestures().get(0).get(0).categoryName() + " " + Math.round(res.gestures().get(0).get(0).score() * 100) + "%";
            List<NormalizedLandmark> lm = res.landmarks().isEmpty() ? null : res.landmarks().get(0);
            runOnUiThread(() -> show(bmp, lm, g, motion, dt, top));
        } catch (Exception e) {
            try {
                img.close();
            } catch (Exception ignored) {
            }
        } finally {
            busy.set(false);
        }
    }

    private void show(Bitmap bmp, List<NormalizedLandmark> lm, Gestures.G g, Gestures.G motion, long dt, String top) {
        preview.setImageBitmap(bmp);
        overlay.set(lm);
        frames++;
        long now = SystemClock.elapsedRealtime();
        inferMs = inferMs == 0 ? dt : inferMs * 0.8 + dt * 0.2;
        if (now - lastFpsTime >= 1000) {
            fps = frames * 1000.0 / Math.max(1, now - lastFpsTime);
            frames = 0;
            lastFpsTime = now;
        }
        // A gesture counts once it holds for 3 frames in a row.
        streak = g == last ? streak + 1 : 1;
        last = g;
        if (streak >= 3) stable = g;
        if (motion != Gestures.G.NONE) {
            event = motion;
            eventUntil = now + 1200;
            eventCount++;
        }
        Gestures.G shown = now < eventUntil ? event : stable;
        big.setText(shown.icon);
        label.setText(shown == Gestures.G.NONE ? "Show a hand to the camera"
                : shown.label + (now < eventUntil ? "  (#" + eventCount + ")" : ""));
        stats.setText(String.format(Locale.US, "%.1f frames/s  ·  %.0f ms per frame", fps, inferMs));
        raw.setText("MediaPipe: " + top + "   ·   this frame: " + g.label);
    }

    private byte[] yArr, uArr, vArr;

    /** YUV_420_888 to ARGB: the planes are copied out in one go, then converted from plain arrays. */
    private Bitmap toBitmap(Image img) {
        int w = img.getWidth(), h = img.getHeight();
        if (argb == null || argb.length != w * h) argb = new int[w * h];
        Image.Plane[] pl = img.getPlanes();
        yArr = copy(pl[0].getBuffer(), yArr);
        uArr = copy(pl[1].getBuffer(), uArr);
        vArr = copy(pl[2].getBuffer(), vArr);
        final byte[] ya = yArr, ua = uArr, va = vArr;
        final int[] out = argb;
        int yRow = pl[0].getRowStride(), uvRow = pl[1].getRowStride(), uvPix = pl[1].getPixelStride();
        for (int y = 0; y < h; y++) {
            int yo = y * yRow, uvo = (y >> 1) * uvRow, o = y * w;
            for (int x = 0; x < w; x += 2) {
                int i = uvo + (x >> 1) * uvPix;
                int U = (ua[i] & 0xFF) - 128, V = (va[i] & 0xFF) - 128;
                int rv = 1634 * V, gv = -833 * V - 400 * U, bu = 2066 * U;
                for (int k = 0; k < 2 && x + k < w; k++) {
                    int Y = (ya[yo + x + k] & 0xFF) - 16;
                    int c = 1192 * (Y < 0 ? 0 : Y);
                    int rr = (c + rv) >> 10, gg = (c + gv) >> 10, bb = (c + bu) >> 10;
                    rr = rr < 0 ? 0 : (rr > 255 ? 255 : rr);
                    gg = gg < 0 ? 0 : (gg > 255 ? 255 : gg);
                    bb = bb < 0 ? 0 : (bb > 255 ? 255 : bb);
                    out[o + x + k] = 0xFF000000 | (rr << 16) | (gg << 8) | bb;
                }
            }
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888);
    }

    private static byte[] copy(ByteBuffer src, byte[] dst) {
        src.rewind();
        int n = src.remaining();
        if (dst == null || dst.length < n) dst = new byte[n];
        src.get(dst, 0, n);
        return dst;
    }

    private void stop() {
        try {
            if (session != null) session.close();
            if (camera != null) camera.close();
            if (reader != null) reader.close();
        } catch (Exception ignored) {
        }
        session = null;
        camera = null;
        reader = null;
        if (camHandler != null) {
            camHandler.post(() -> {
                if (recognizer != null) recognizer.close();
                recognizer = null;
            });
        }
        if (camThread != null) camThread.quitSafely();
        camThread = null;
    }

    private TextView text(String s, int sp, int color, Typeface tf) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setTypeface(tf);
        return v;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** The 21 hand points over the mirrored preview. */
    private static final class Overlay extends View {
        private List<NormalizedLandmark> lm;
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private static final int[][] BONES = {{0, 1}, {1, 2}, {2, 3}, {3, 4}, {0, 5}, {5, 6}, {6, 7}, {7, 8}, {5, 9},
                {9, 10}, {10, 11}, {11, 12}, {9, 13}, {13, 14}, {14, 15}, {15, 16}, {13, 17}, {0, 17}, {17, 18},
                {18, 19}, {19, 20}};

        Overlay(android.content.Context c) {
            super(c);
            dot.setColor(0xFFF5B700);
            line.setColor(0xCCFFFFFF);
            line.setStrokeWidth(4);
        }

        void set(List<NormalizedLandmark> lm) {
            this.lm = lm;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (lm == null) return;
            // The preview keeps 4:3 inside this view; match its letterbox and its mirror.
            float vw = getWidth(), vh = getHeight(), scale = Math.min(vw / W, vh / H);
            float ox = (vw - W * scale) / 2, oy = (vh - H * scale) / 2;
            for (int[] b : BONES) {
                canvas.drawLine(x(lm.get(b[0]), ox, scale), y(lm.get(b[0]), oy, scale),
                        x(lm.get(b[1]), ox, scale), y(lm.get(b[1]), oy, scale), line);
            }
            for (NormalizedLandmark p : lm) canvas.drawCircle(x(p, ox, scale), y(p, oy, scale), 7, dot);
        }

        private float x(NormalizedLandmark p, float ox, float s) {
            return ox + (1 - p.x()) * W * s;
        }

        private float y(NormalizedLandmark p, float oy, float s) {
            return oy + p.y() * H * s;
        }
    }
}
