package com.yashg.gestures;

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;

import java.util.List;

/**
 * "Move the fingertip, the focus follows" (was the palm: too tiring - the user's wrist hurt). A joystick around where the palm was when it woke the control:
 * move the open palm about one palm-length to a side and the focus moves that way at once, then again every
 * moment while the palm stays out there (faster the further out). Bring it back near the start to stop.
 * Why a joystick and not swipes: the TV processes ~2-4 frames a second, too few to read a quick swipe, but where the
 * hand is now compared to where it started reads reliably, and the way back does not move the focus backwards.
 * Positions are in whole-camera pixels (the follow-zoom window moves, the room does not). The camera image is not
 * mirrored, so the user's right is the image's left.
 */
final class PalmMove {
    // In palm-lengths (wrist to middle knuckle) from the start. Up/down need more: an arm held out sinks 2-3
    // palm-lengths (sofa log: moving right read as down).
    static final double ON_X = 0.7, ON_Y = 0.8, OFF = 0.4;   // fingertip: small moves, elbow resting
    /** Repeat speed follows distance: just out = slow, far out = fast. */
    static final long SLOW_MS = 900, FAST_MS = 350;
    /** Gone longer than this: start again where the palm comes back, without moving. */
    static final long LOST_MS = 1000;
    private long lastSeen;

    private double ax, ay = -1, size = -1;          // start position and palm length, pixels
    private Gestures.G dir = Gestures.G.NONE;
    private long lastStep;
    /** For the diagnostic log: offset from the start, in palm-lengths. */
    double offX, offY;

    /** Start here (called on wake). */
    void anchor(List<NormalizedLandmark> lm, int x0, int y0, int w, int h) {
        ax = px(lm, x0, w);
        ay = py(lm, y0, h);
        size = len(lm, w, h);
        dir = Gestures.G.NONE;
        lastSeen = Long.MAX_VALUE / 2;   // not "lost" on the first frame after waking
    }

    boolean anchored() {
        return ay >= 0;
    }

    void clear() {
        ay = -1;
        dir = Gestures.G.NONE;
    }

    Gestures.G update(long now, List<NormalizedLandmark> lm, boolean palm, int x0, int y0, int w, int h) {
        if (!anchored() || lm == null || !palm) {
            dir = Gestures.G.NONE;
            return Gestures.G.NONE;
        }
        double x = px(lm, x0, w), y = py(lm, y0, h);
        if (now - lastSeen > LOST_MS) {             // back after a gap: this is the new centre
            lastSeen = now;
            ax = x;
            ay = y;
            size = len(lm, w, h);
            dir = Gestures.G.NONE;
            offX = offY = 0;
            return Gestures.G.NONE;
        }
        lastSeen = now;
        size = size * 0.8 + len(lm, w, h) * 0.2;
        offX = (x - ax) / size;
        offY = (y - ay) / size;
        double ox = Math.abs(offX), oy = Math.abs(offY);
        Gestures.G want;
        if (dir != Gestures.G.NONE && sameAxis(dir, ox, oy)) want = dir;   // still out there
        else if (ox >= ON_X && ox >= oy * 0.6) want = offX > 0 ? Gestures.G.SWIPE_LEFT : Gestures.G.SWIPE_RIGHT;   // sideways wins
        else if (oy >= ON_Y && oy > ox * 1.5) want = offY > 0 ? Gestures.G.SWIPE_DOWN : Gestures.G.SWIPE_UP;
        else want = Gestures.G.NONE;
        if (want == Gestures.G.NONE) {
            dir = want;
            // Resting near the centre: let the centre follow slow drift (a tiring arm sinks).
            ax += (x - ax) * 0.15;
            ay += (y - ay) * 0.15;
            return want;
        }
        if (want != dir) {          // just moved out: step now
            dir = want;
            lastStep = now;
            return want;
        }
        double out = want == Gestures.G.SWIPE_LEFT || want == Gestures.G.SWIPE_RIGHT ? ox - ON_X : oy - ON_Y;
        long every = Math.max(FAST_MS, Math.min(SLOW_MS, Math.round(SLOW_MS - out * 400)));
        if (now - lastStep >= every) {
            lastStep = now;
            return want;
        }
        return Gestures.G.NONE;
    }

    private static boolean sameAxis(Gestures.G d, double ox, double oy) {
        boolean horiz = d == Gestures.G.SWIPE_LEFT || d == Gestures.G.SWIPE_RIGHT;
        return horiz ? ox > OFF : oy > OFF;
    }

    private static double px(List<NormalizedLandmark> lm, int x0, int w) {
        return x0 + lm.get(8).x() * w;   // the index fingertip
    }

    private static double py(List<NormalizedLandmark> lm, int y0, int h) {
        return y0 + lm.get(8).y() * h;
    }

    private static double len(List<NormalizedLandmark> lm, int w, int h) {
        return Math.max(4, Math.hypot((lm.get(9).x() - lm.get(0).x()) * w, (lm.get(9).y() - lm.get(0).y()) * h));
    }
}
