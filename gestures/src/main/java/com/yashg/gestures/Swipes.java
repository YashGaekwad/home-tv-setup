package com.yashg.gestures;

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;

import java.util.ArrayDeque;
import java.util.List;

/**
 * Whole-hand movement: an open palm moved left, right, up or down is a swipe (one arrow press); an open palm held
 * still for a moment is play/pause. Works at the TV's ~4 frames a second by looking at the last second of palm
 * positions. Positions are in the unmirrored camera image, so the user's right is the image's left.
 */
final class Swipes {
    static final long WINDOW_MS = 1000, COOLDOWN_MS = 700, HOLD_MS = 1200;
    static final double MOVE = 0.15;        // share of the frame the palm must travel
    static final double STILL = 0.04;       // a palm that stays within this is "held"

    private static final class P {
        final long t;
        final double x, y;

        P(long t, double x, double y) {
            this.t = t;
            this.x = x;
            this.y = y;
        }
    }

    private final ArrayDeque<P> track = new ArrayDeque<>();
    private long quietUntil, holdStart = -1, lastSeen;
    private boolean holdFired;

    /** Feed one frame; returns a swipe or PLAY_PAUSE when one completes, else NONE. */
    Gestures.G update(long now, List<NormalizedLandmark> lm, boolean palm) {
        if (lm == null || !palm) {
            // A fast move blurs the hand for a frame or two: keep the track through a short gap.
            if (now - lastSeen > 500) {
                track.clear();
                holdStart = -1;
                holdFired = false;
            }
            return Gestures.G.NONE;
        }
        lastSeen = now;
        // Palm centre: wrist and the four finger bases.
        double x = (lm.get(0).x() + lm.get(5).x() + lm.get(9).x() + lm.get(13).x() + lm.get(17).x()) / 5;
        double y = (lm.get(0).y() + lm.get(5).y() + lm.get(9).y() + lm.get(13).y() + lm.get(17).y()) / 5;
        track.addLast(new P(now, x, y));
        while (!track.isEmpty() && now - track.peekFirst().t > WINDOW_MS) track.removeFirst();
        if (now < quietUntil || track.size() < 2) return Gestures.G.NONE;

        P first = track.peekFirst();
        double dx = x - first.x, dy = y - first.y;
        if (Math.abs(dx) > MOVE && Math.abs(dx) > 1.5 * Math.abs(dy)) {
            return fire(now, dx > 0 ? Gestures.G.SWIPE_LEFT : Gestures.G.SWIPE_RIGHT);
        }
        if (Math.abs(dy) > MOVE && Math.abs(dy) > 1.5 * Math.abs(dx)) {
            return fire(now, dy < 0 ? Gestures.G.SWIPE_UP : Gestures.G.SWIPE_DOWN);
        }

        // Held still: play / pause, once per hold.
        double spread = 0;
        for (P p : track) spread = Math.max(spread, Math.hypot(p.x - x, p.y - y));
        if (spread < STILL) {
            if (holdStart < 0) holdStart = first.t;
            if (!holdFired && now - holdStart >= HOLD_MS) {
                holdFired = true;
                return Gestures.G.PLAY_PAUSE;
            }
        } else {
            holdStart = -1;
            holdFired = false;
        }
        return Gestures.G.NONE;
    }

    private Gestures.G fire(long now, Gestures.G g) {
        track.clear();
        quietUntil = now + COOLDOWN_MS;
        holdStart = -1;
        holdFired = true;   // a swipe is not also a hold
        return g;
    }
}
