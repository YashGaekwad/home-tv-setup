package com.yashg.gestures;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Range;

import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.core.Delegate;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer;
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The webcam, MediaPipe's gesture recogniser, our gesture rules and the wake logic, shared by the test screen and
 * the background control. Frames live in memory only for the moment they are processed; nothing is saved or sent
 * (the app has no internet permission). Everything runs on one camera thread.
 *
 * Wake: until someone holds an upright open palm still for about a second, gestures are seen but not acted on, so
 * other people's hands (dinner on the sofa) do not drive the TV. Awake lasts until no gesture for IDLE_MS.
 */
final class GestureEngine {
    interface Listener {
        /** A gesture to act on (only while awake). */
        void onGesture(Gestures.G g);

        /** Awake or asleep changed. */
        void onAwake(boolean awake);

        /** Every processed frame, for the test screen; may be ignored. */
        default void onFrame(Bitmap bmp, List<NormalizedLandmark> lm, Gestures.G stable, Gestures.G event,
                             double fps, double ms, String info) {}

        default void onError(String text) {}
    }

    private static final int CAM_W = 1280, CAM_H = 720, CROP_W = 640, CROP_H = 480;
    private static final int[] SEARCH_X = {(CAM_W - CROP_W) / 2, 0, CAM_W - CROP_W};
    static final long IDLE_MS = 10_000, REPEAT_MS = 700;
    static final int WAKE_FRAMES = 2;               // 2 of the last 3 frames, ~1.5 s of raised, steady open palm (2 hands run at ~2 fps)

    private final Context ctx;
    private final Listener listener;
    private final boolean alwaysAwake, wantFrames;
    private HandlerThread thread;
    private Handler handler;
    private CameraDevice camera;
    private CameraCaptureSession session;
    private ImageReader reader;
    private GestureRecognizer recognizer;
    private final AtomicBoolean busy = new AtomicBoolean();
    private volatile boolean running;

    private int[] argb;
    private byte[] yArr, uArr, vArr;
    private boolean tracking;
    private int cropX, cropY, searchIdx;
    private long lastHandTime;
    private double lastX = 0.5, lastY = 0.5;

    private final Swipes swipes = new Swipes();   // only for the hand's tilt now (wake steadiness)
    private final PalmMove move = new PalmMove();
    private Gestures.G stable = Gestures.G.NONE;
    private final Gestures.G[] recentG = new Gestures.G[3];
    private int recentIdx;
    private long stableSince, lastRepeat;
    private boolean awake;
    private long awakeUntil, lastEventTime;
    private int wakeCount, wakeIdx;
    private final boolean[] wakeHits = new boolean[3];
    private final double[] wakeTilts = new double[3];
    private double wakeSum;
    private double prevTilt = Double.NaN;
    private long frames, lastFpsTime;
    private double fps, inferMs;
    private PrintWriter diag;
    volatile String step = "free";

    /** alwaysAwake: the test screen acts on every gesture without the wake step. */
    GestureEngine(Context ctx, Listener listener, boolean alwaysAwake, boolean wantFrames) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
        this.alwaysAwake = alwaysAwake;
        this.wantFrames = wantFrames;
    }

    boolean isRunning() {
        return running;
    }

    void start() {
        if (running) return;
        running = true;
        thread = new HandlerThread("gesture-camera");
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(() -> {
            try {
                recognizer = GestureRecognizer.createFromOptions(ctx, GestureRecognizer.GestureRecognizerOptions.builder()
                        .setBaseOptions(BaseOptions.builder().setModelAssetPath("gesture_recognizer.task")
                                .setDelegate(Delegate.CPU).build())
                        .setRunningMode(RunningMode.VIDEO)
                        .setNumHands(2)
                        .setMinHandDetectionConfidence(0.5f)
                        .setMinHandPresenceConfidence(0.5f)
                        .setMinTrackingConfidence(0.5f)
                        .build());
                openCamera();
            } catch (Exception e) {
                listener.onError("Could not start: " + e.getMessage());
                stop();
            }
        });
    }

    void stop() {
        running = false;
        try {
            if (session != null) session.close();
            if (camera != null) camera.close();
            if (reader != null) reader.close();
        } catch (Exception ignored) {
        }
        session = null;
        camera = null;
        reader = null;
        Handler h = handler;
        HandlerThread t = thread;
        if (h != null) {
            h.post(() -> {
                if (recognizer != null) recognizer.close();
                recognizer = null;
                if (t != null) t.quitSafely();
            });
        }
        handler = null;
        thread = null;
        if (awake) setAwake(false);
    }

    private void openCamera() throws Exception {
        CameraManager cm = ctx.getSystemService(CameraManager.class);
        String[] ids = cm.getCameraIdList();
        if (ids.length == 0) {
            listener.onError("No camera connected");
            stop();
            return;
        }
        String id = ids[0];
        CameraCharacteristics cc = cm.getCameraCharacteristics(id);
        Range<Integer> best = null;
        Range<Integer>[] ranges = cc.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if (ranges != null) for (Range<Integer> r : ranges) if (best == null || r.getUpper() > best.getUpper()) best = r;
        final Range<Integer> fpsRange = best;
        reader = ImageReader.newInstance(CAM_W, CAM_H, ImageFormat.YUV_420_888, 2);
        reader.setOnImageAvailableListener(this::onImage, handler);
        //noinspection MissingPermission
        cm.openCamera(id, new CameraDevice.StateCallback() {
            @Override
            public void onOpened(CameraDevice c) {
                if (!running) {
                    c.close();
                    return;
                }
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
                                s.setRepeatingRequest(b.build(), null, handler);
                            } catch (Exception e) {
                                listener.onError("Camera error: " + e.getMessage());
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession s) {
                            listener.onError("Camera session failed");
                        }
                    }, handler);
                } catch (Exception e) {
                    listener.onError("Camera error: " + e.getMessage());
                }
            }

            @Override
            public void onDisconnected(CameraDevice c) {
                c.close();
                listener.onError("Camera unplugged");
                stop();
            }

            @Override
            public void onError(CameraDevice c, int error) {
                c.close();
                listener.onError("Camera error " + error + " (in use by another app?)");
                stop();
            }
        }, handler);
    }

    private void onImage(ImageReader r) {
        Image img;
        try {
            img = r.acquireLatestImage();
        } catch (Exception e) {
            return;
        }
        if (img == null) return;
        if (!running || recognizer == null || !busy.compareAndSet(false, true)) {
            img.close();
            return;
        }
        try {
            // Searching: full-resolution windows over the centre, left and right of the room in turn; once a hand is
            // found the window follows it (a hand on the sofa is too small for the hand finder in the whole picture).
            if (!tracking) {
                cropX = SEARCH_X[searchIdx++ % SEARCH_X.length];
                cropY = (CAM_H - CROP_H) / 2;
            }
            final int x0 = cropX, y0 = cropY;
            Bitmap bmp = toBitmap(img, x0, y0);
            img.close();
            long t0 = SystemClock.elapsedRealtime();
            GestureRecognizerResult res = recognizer.recognizeForVideo(new BitmapImageBuilder(bmp).build(), t0);
            long dt = SystemClock.elapsedRealtime() - t0;
            process(res, bmp, x0, y0, t0, dt);
        } catch (Exception e) {
            try {
                img.close();
            } catch (Exception ignored) {
            }
        } finally {
            busy.set(false);
        }
    }

    private void process(GestureRecognizerResult res, Bitmap bmp, int x0, int y0, long now, long dt) {
        // Which hand to follow. Asleep: only an open, raised palm (the wake hand) - a fist of someone eating or a hand
        // on a lap must not capture the camera window, as it did in the live log. Awake: the raised hand nearest to
        // the one being followed, so it stays with the person who woke it.
        int hi;
        if (alwaysAwake) hi = Gestures.raisedHand(res);
        else if (!awake) hi = Gestures.pointerHand(res);
        else {
            hi = Gestures.nearestRaised(res, lastX, lastY, 0.25);
            if (hi < 0 && now - lastHandTime > 2500) hi = Gestures.pointerHand(res);   // lost them: look for a palm again
        }
        if (hi >= 0) {
            lastX = res.landmarks().get(hi).get(9).x();
            lastY = res.landmarks().get(hi).get(9).y();
        }
        List<NormalizedLandmark> lm = hi < 0 ? null : res.landmarks().get(hi);
        reframe(lm, x0, y0, now);
        boolean palm = Gestures.isPointer(res, hi);   // "palm" = the pointing hand now
        // Kept simple on purpose (user: "the direction my palm moves, it moves that way"): an open palm moves the
        // focus, closing it into a fist selects, victory is back. Nothing else acts.
        Gestures.G g = Gestures.simple(res, hi);
        swipes.update(now, lm, palm);
        if (alwaysAwake && lm != null && palm && !move.anchored()) move.anchor(lm, x0, y0, CROP_W, CROP_H);
        Gestures.G motion = alwaysAwake || awake ? move.update(now, lm, palm, x0, y0, CROP_W, CROP_H) : Gestures.G.NONE;

        // A static gesture counts when it is in 2 of the last 3 frames: at ~2 fps with two hands tracked, a held thumb
        // read "up, none, up, up, none" in the live log and never made 3 in a row. Up/down repeat while held, the rest
        // fire once.
        recentG[recentIdx++ % 3] = g;
        Gestures.G cand = Gestures.G.NONE;
        for (Gestures.G x : recentG) {
            if (x == null || x == Gestures.G.NONE) continue;
            int n = 0;
            for (Gestures.G y : recentG) if (y == x) n++;
            if (n >= 2) cand = x;
        }
        Gestures.G event = Gestures.G.NONE;
        if (cand != stable) {
            stable = cand;
            stableSince = now;
            lastRepeat = now;
            if (cand != Gestures.G.NONE) event = cand;
        } else if ((cand == Gestures.G.VOL_UP || cand == Gestures.G.VOL_DOWN) && now - lastRepeat >= 500) {
            lastRepeat = now;
            event = cand;
        }
        if (motion != Gestures.G.NONE) event = motion;

        // Wake: a raised open palm (roughly upright, within 45 deg) held steady. Its tilt becomes the user's own
        // upright, which scrolling then measures from.
        double tilt = swipes.lastFlickX;
        // At ~2 fps a frame or two is often misread, so: 2 of the last 3 frames, and steady within 15 deg.
        boolean upright = lm != null && palm && Math.abs(tilt) < 70
                && (Double.isNaN(prevTilt) || Math.abs(tilt - prevTilt) < 15);
        if (lm != null && palm) prevTilt = tilt;
        else if (lm == null) prevTilt = Double.NaN;
        if (!alwaysAwake) {
            wakeHits[wakeIdx] = upright;
            wakeTilts[wakeIdx] = tilt;
            wakeIdx = (wakeIdx + 1) % 3;
            wakeCount = 0;
            wakeSum = 0;
            for (int k = 0; k < 3; k++) if (wakeHits[k]) { wakeCount++; wakeSum += wakeTilts[k]; }
            if (!awake && upright && wakeCount >= WAKE_FRAMES) {
                swipes.setNeutral(wakeSum / wakeCount);
                move.anchor(lm, x0, y0, CROP_W, CROP_H);
                setAwake(true);
                awakeUntil = now + IDLE_MS;
                event = Gestures.G.NONE;
            }
            if (awake && (event != Gestures.G.NONE || (lm != null && palm))) awakeUntil = now + IDLE_MS;
            if (awake && now > awakeUntil) setAwake(false);
        }
        if (event != Gestures.G.NONE && (alwaysAwake || awake)) {
            lastEventTime = now;
            listener.onGesture(event);
        }

        frames++;
        inferMs = inferMs == 0 ? dt : inferMs * 0.8 + dt * 0.2;
        if (now - lastFpsTime >= 1000) {
            fps = frames * 1000.0 / Math.max(1, now - lastFpsTime);
            frames = 0;
            lastFpsTime = now;
        }
        String top = hi < 0 || res.gestures().size() <= hi || res.gestures().get(hi).isEmpty()
                ? (res.landmarks().isEmpty() ? "no hand" : "hand not raised")
                : res.gestures().get(hi).get(0).categoryName() + " " + Math.round(res.gestures().get(hi).get(0).score() * 100) + "%";
        log(lm == null ? "t=" + now + " step=" + step + " hand=no"
                : String.format(Locale.US, "t=%d step=%s hand=yes palm=%b size=%.3f tilt=%+.0f cat=%s g=%s motion=%s off=%+.1f,%+.1f awake=%b",
                now, step, palm, swipes.lastHand, tilt, top.replace(' ', ':'), g, motion, move.offX, move.offY, awake));
        if (wantFrames) {
            listener.onFrame(bmp, lm, stable, event, fps, inferMs,
                    top + "  ·  " + (tracking ? "following hand" : "searching") + (alwaysAwake ? "" : awake ? "  ·  awake" : "  ·  asleep"));
        }
    }

    private void setAwake(boolean a) {
        awake = a;
        wakeCount = 0;
        wakeSum = 0;
        java.util.Arrays.fill(wakeHits, false);
        if (!a) move.clear();
        listener.onAwake(a);
    }

    /** Re-centre the window on the hand near its edge; back to searching after 1.5 s without a hand. */
    private void reframe(List<NormalizedLandmark> lm, int x0, int y0, long now) {
        if (lm == null) {
            if (tracking && now - lastHandTime > 1500) {
                tracking = false;
                swipes.reset();
            }
            return;
        }
        lastHandTime = now;
        NormalizedLandmark c = lm.get(9);
        int hx = x0 + Math.round(c.x() * CROP_W), hy = y0 + Math.round(c.y() * CROP_H);
        boolean nearEdge = !tracking
                || hx < cropX + CROP_W * 0.25 || hx > cropX + CROP_W * 0.75
                || hy < cropY + CROP_H * 0.2 || hy > cropY + CROP_H * 0.8;
        if (nearEdge) {
            cropX = Math.max(0, Math.min(CAM_W - CROP_W, hx - CROP_W / 2));
            cropY = Math.max(0, Math.min(CAM_H - CROP_H, hy - CROP_H / 2));
            tracking = true;
            swipes.reset();
        }
    }

    /** The CROP_W x CROP_H window at (x0, y0) of the YUV_420_888 frame, to ARGB. */
    private Bitmap toBitmap(Image img, int x0, int y0) {
        int n = CROP_W * CROP_H;
        if (argb == null || argb.length != n) argb = new int[n];
        Image.Plane[] pl = img.getPlanes();
        yArr = copy(pl[0].getBuffer(), yArr);
        uArr = copy(pl[1].getBuffer(), uArr);
        vArr = copy(pl[2].getBuffer(), vArr);
        final byte[] ya = yArr, ua = uArr, va = vArr;
        final int[] out = argb;
        int yRow = pl[0].getRowStride(), uvRow = pl[1].getRowStride(), uvPix = pl[1].getPixelStride();
        for (int oy = 0; oy < CROP_H; oy++) {
            int sy = y0 + oy, yo = sy * yRow, uvo = (sy >> 1) * uvRow, o = oy * CROP_W;
            for (int ox = 0; ox < CROP_W; ox++) {
                int sx = x0 + ox, i = uvo + (sx >> 1) * uvPix;
                int U = (ua[i] & 0xFF) - 128, V = (va[i] & 0xFF) - 128, Y = (ya[yo + sx] & 0xFF) - 16;
                int c = 1192 * (Y < 0 ? 0 : Y);
                int rr = (c + 1634 * V) >> 10, gg = (c - 833 * V - 400 * U) >> 10, bb = (c + 2066 * U) >> 10;
                rr = rr < 0 ? 0 : (rr > 255 ? 255 : rr);
                gg = gg < 0 ? 0 : (gg > 255 ? 255 : gg);
                bb = bb < 0 ? 0 : (bb > 255 ? 255 : bb);
                out[o + ox] = 0xFF000000 | (rr << 16) | (gg << 8) | bb;
            }
        }
        return Bitmap.createBitmap(out, CROP_W, CROP_H, Bitmap.Config.ARGB_8888);
    }

    private static byte[] copy(ByteBuffer src, byte[] dst) {
        src.rewind();
        int n = src.remaining();
        if (dst == null || dst.length < n) dst = new byte[n];
        src.get(dst, 0, n);
        return dst;
    }

    /** Diagnostic line to files/diag.txt (adb pull /sdcard/Android/data/com.yashg.gestures/files/diag.txt). */
    synchronized void log(String line) {
        try {
            if (diag == null) {
                File f = new File(ctx.getExternalFilesDir(null), "diag.txt");
                if (f.length() > 2_000_000) f.delete();   // keep it small
                diag = new PrintWriter(new FileWriter(f, true), true);
            }
            diag.println(SystemClock.elapsedRealtime() + " " + line);
        } catch (Exception ignored) {
        }
    }
}
