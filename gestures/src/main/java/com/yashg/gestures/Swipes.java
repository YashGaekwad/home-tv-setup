package com.yashg.gestures;

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;

import java.util.ArrayDeque;
import java.util.List;

/**
 * Joystick-style palm scrolling and the still-palm hold.
 *  - Tilt the open palm to one side (wrist to knuckles more than TILT_ON degrees from upright) and hold it: one scroll
 *    step that way after HOLD_ON_MS, then another every REPEAT_MS while it stays tilted. Back under TILT_OFF stops.
 *  - An upright palm is the rest position and does nothing (play/pause is a fist, see Gestures).
 * Why tilt-and-hold and not flicks: the TV runs about 5 frames a second, so a quick wrist snap is one or two frames
 * and its direction could not be told from the slower pull before it (guided test from the sofa). The tilt itself
 * read cleanly: about 0 degrees upright, 55-75 degrees tilted, every time.
 * Up/down are pointing (Gestures). Tilt is an angle, so it does not depend on how big the hand is in the picture.
 * Positions are in the unmirrored camera image, so the user's right is the image's left.
 */
final class Swipes {
    static final double TILT_ON = 25, TILT_OFF = 12, UPRIGHT = 10;   // wrists bend less one way: right tilts peaked ~30-34 deg
    /** Past this the hand points sideways or down (resting on the lap reads about 120 deg): not a scroll. */
    static final double TILT_MAX = 85;
    static final long HOLD_ON_MS = 450, REPEAT_MS = 700;
    static final double STEADY_DEG = 6, STEADY_PALMS = 0.3;
    static final int HOLD_FRAMES = 10;              // ~2 s of steady upright palm = play/pause

    private final ArrayDeque<Double> recent = new ArrayDeque<>();   // last tilts, for a median
    /** The user's own upright: a relaxed raised palm sat at about +21 deg in the sofa log, not 0. Set on wake. */
    private double neutral;

    void setNeutral(double deg) {
        neutral = deg;
        side = 0;
    }
    private int side;                               // -1 tilted to the user's right, +1 to the left, 0 upright
    private long sideSince, lastStep, lastSeen;
    private double pTilt, px, py, width = -1;
    private boolean havePrev, holdFired;
    private int steady;
    /** For the diagnostic log. */
    double lastFlickX, lastFlickY, lastMoveX, lastMoveY, lastHand;

    void reset() {
        recent.clear();
        side = 0;
        havePrev = false;
        steady = 0;
        holdFired = false;
        width = -1;
    }

    Gestures.G update(long now, List<NormalizedLandmark> lm, boolean palm) {
        if (lm == null || !palm) {
            if (now - lastSeen > 600) reset();
            return Gestures.G.NONE;
        }
        double w = Math.hypot(lm.get(5).x() - lm.get(17).x(), lm.get(5).y() - lm.get(17).y());
        if (w < 0.004) return Gestures.G.NONE;
        width = width < 0 ? w : width * 0.7 + w * 0.3;
        lastSeen = now;
        lastHand = width;

        NormalizedLandmark wr = lm.get(0);
        double kx = (lm.get(5).x() + lm.get(9).x() + lm.get(13).x()) / 3, ky = (lm.get(5).y() + lm.get(9).y() + lm.get(13).y()) / 3;
        double raw = Math.toDegrees(Math.atan2(kx - wr.x(), wr.y() - ky));   // 0 = up, + = image right (user's left)
        recent.addLast(raw);
        while (recent.size() > 3) recent.removeFirst();
        double absolute = median();
        double tilt = absolute - neutral;
        double cx = (wr.x() + lm.get(5).x() + lm.get(9).x() + lm.get(13).x() + lm.get(17).x()) / 5;
        double cy = (wr.y() + lm.get(5).y() + lm.get(9).y() + lm.get(13).y() + lm.get(17).y()) / 5;
        lastFlickX = absolute;

        // Which side is the palm on, with a gap between on and off so it does not flicker.
        int now_side = side;
        if (Math.abs(absolute) > TILT_MAX) now_side = 0;   // hand down or sideways: rest
        else if (tilt > TILT_ON) now_side = 1;
        else if (tilt < -TILT_ON) now_side = -1;
        else if (Math.abs(tilt) < TILT_OFF) now_side = 0;
        if (now_side != side) {
            side = now_side;
            sideSince = now;
            lastStep = 0;
        }

        // Steady upright palm: play / pause once.
        boolean still = havePrev && Math.abs(raw - pTilt) < STEADY_DEG && Math.hypot(cx - px, cy - py) / width < STEADY_PALMS;
        pTilt = raw; px = cx; py = cy;
        havePrev = true;
        // (An upright palm is the rest position; play/pause is the fist, in Gestures.)

        if (side == 0) return Gestures.G.NONE;
        boolean first = lastStep == 0 && now - sideSince >= HOLD_ON_MS;
        boolean again = lastStep != 0 && now - lastStep >= REPEAT_MS;
        if (!first && !again) return Gestures.G.NONE;
        lastStep = now;
        return side > 0 ? Gestures.G.SWIPE_LEFT : Gestures.G.SWIPE_RIGHT;
    }

    private double median() {
        Double[] v = recent.toArray(new Double[0]);
        java.util.Arrays.sort(v);
        return v[v.length / 2];
    }
}
