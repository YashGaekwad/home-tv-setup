package com.yashg.gestures;

import com.google.mediapipe.tasks.components.containers.Category;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult;

import java.util.List;

/**
 * Turns one MediaPipe result into one of our gestures. MediaPipe's own classes cover palm, fist, thumbs and the
 * victory / "I love you" signs; pointing direction and the pinch come from the 21 hand landmarks.
 * Landmark x/y are in the camera's own (unmirrored) image, so the user's right is the image's left.
 */
final class Gestures {
    enum G {
        NONE("—", ""),
        LEFT("⬅", "Left"), RIGHT("➡", "Right"), UP("⬆", "Up"), DOWN("⬇", "Down"),
        OK("✊", "Select"),
        SWIPE_LEFT("⇦", "Scroll left"), SWIPE_RIGHT("⇨", "Scroll right"),
        SWIPE_UP("⇧", "Swipe up"), SWIPE_DOWN("⇩", "Swipe down"),
        PLAY_PAUSE("✊", "Play / pause"),
        VOL_UP("👍", "Volume up"), VOL_DOWN("👎", "Volume down"),
        MUTE("🤟", "Mute"),
        BACK("✌", "Back"),
        HOME("🤟", "Home");

        final String icon, label;

        G(String icon, String label) {
            this.icon = icon;
            this.label = label;
        }
    }

    private Gestures() {}

    /** An open hand: MediaPipe's Open_Palm, or all four fingers out. */
    static boolean isPalm(GestureRecognizerResult r) {
        return isPalm(r, 0);
    }

    static boolean isPalm(GestureRecognizerResult r, int i) {
        if (r == null || r.landmarks().size() <= i || i < 0) return false;
        if (r.gestures().size() > i && !r.gestures().get(i).isEmpty()) {
            Category top = r.gestures().get(i).get(0);
            // From the sofa Open_Palm often wins with a low score (0.3-0.5): the top label is enough.
            if ("Open_Palm".equals(top.categoryName())) return true;
            if ("Closed_Fist".equals(top.categoryName())) return false;
        }
        List<NormalizedLandmark> p = r.landmarks().get(i);
        int n = (extended(p, 8, 6) ? 1 : 0) + (extended(p, 12, 10) ? 1 : 0) + (extended(p, 16, 14) ? 1 : 0) + (extended(p, 20, 18) ? 1 : 0);
        return n >= 3;   // at distance one finger often reads bent
    }

    static G classify(GestureRecognizerResult r) {
        return classify(r, 0);
    }

    /** Tilt of hand i: wrist to knuckles, 0 = fingers up, +-180 = pointing down. */
    static double tilt(GestureRecognizerResult r, int i) {
        List<NormalizedLandmark> p = r.landmarks().get(i);
        double kx = (p.get(5).x() + p.get(9).x() + p.get(13).x()) / 3, ky = (p.get(5).y() + p.get(9).y() + p.get(13).y()) / 3;
        return Math.toDegrees(Math.atan2(kx - p.get(0).x(), p.get(0).y() - ky));
    }

    /** The raised hand to follow: the most upright one, if any is within 70 deg of upright; else -1. */
    static int raisedHand(GestureRecognizerResult r) {
        int best = -1;
        double bestTilt = 70;
        for (int i = 0; r != null && i < r.landmarks().size(); i++) {
            double t = Math.abs(tilt(r, i));
            if (t < bestTilt) {
                bestTilt = t;
                best = i;
            }
        }
        return best;
    }

    /** An open, raised palm (the wake hand) - the most upright one; -1 if none. */
    static int raisedPalm(GestureRecognizerResult r) {
        int best = -1;
        double bestTilt = 60;
        for (int i = 0; r != null && i < r.landmarks().size(); i++) {
            double t = Math.abs(tilt(r, i));
            if (t < bestTilt && isPalm(r, i)) {
                bestTilt = t;
                best = i;
            }
        }
        return best;
    }

    /** The raised hand nearest to (x, y) in the frame, within maxDist; -1 if none. */
    static int nearestRaised(GestureRecognizerResult r, double x, double y, double maxDist) {
        int best = -1;
        double bestD = maxDist;
        for (int i = 0; r != null && i < r.landmarks().size(); i++) {
            
            NormalizedLandmark c = r.landmarks().get(i).get(9);
            double d = Math.hypot(c.x() - x, c.y() - y);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    /** Index finger out, the other three folded (MediaPipe's Pointing_Up, or from the landmarks in any direction). */
    static boolean isPointer(GestureRecognizerResult r, int i) {
        if (r == null || i < 0 || r.landmarks().size() <= i) return false;
        if (r.gestures().size() > i && !r.gestures().get(i).isEmpty()) {
            String c = r.gestures().get(i).get(0).categoryName();
            if ("Pointing_Up".equals(c)) return true;
            if ("Closed_Fist".equals(c) || "Thumb_Up".equals(c) || "Thumb_Down".equals(c)) return false;
        }
        List<NormalizedLandmark> p = r.landmarks().get(i);
        return extended(p, 8, 6) && !extended(p, 12, 10) && !extended(p, 16, 14) && !extended(p, 20, 18);
    }

    /** A pointing hand (the wake hand), -1 if none. */
    static int pointerHand(GestureRecognizerResult r) {
        for (int i = 0; r != null && i < r.landmarks().size(); i++) if (isPointer(r, i)) return i;
        return -1;
    }

    /**
     * The easy set: point (index finger) to move - handled by PalmMove; thumbs up / down = volume; closing the
     * pointing finger into a fist = select; victory = back; "I love you" = mute.
     */
    static G simple(GestureRecognizerResult r, int i) {
        if (r == null || i < 0 || r.gestures().size() <= i || r.gestures().get(i).isEmpty()) return G.NONE;
        Category top = r.gestures().get(i).get(0);
        if (top.score() < 0.5f) return G.NONE;
        switch (top.categoryName()) {
            case "Thumb_Up": return G.VOL_UP;
            case "Thumb_Down": return G.VOL_DOWN;
            case "Closed_Fist": return G.OK;
            case "Victory": return G.BACK;
            case "ILoveYou": return G.MUTE;
            default: return G.NONE;
        }
    }

    static G classify(GestureRecognizerResult r, int i) {
        if (r == null || r.landmarks().size() <= i || i < 0) return G.NONE;
        List<NormalizedLandmark> p = r.landmarks().get(i);
        double hand = dist(p.get(0), p.get(9));          // wrist to middle-finger base
        if (hand <= 0) return G.NONE;

        boolean index = extended(p, 8, 6), middle = extended(p, 12, 10), ring = extended(p, 16, 14),
                pinky = extended(p, 20, 18);

        // Pinch (OK sign): thumb tip on index tip, the other three fingers out.
        if (dist(p.get(4), p.get(8)) < 0.20 * hand && middle && ring && pinky) return G.OK;

        // Pointing: only the index finger out; its direction picks the arrow.
        if (index && !middle && !ring && !pinky) {
            double dx = p.get(8).x() - p.get(5).x(), dy = p.get(8).y() - p.get(5).y();
            if (Math.abs(dx) > Math.abs(dy) * 1.2) return dx > 0 ? G.LEFT : G.RIGHT;  // mirrored
            if (Math.abs(dy) > Math.abs(dx) * 1.2) return dy < 0 ? G.UP : G.DOWN;
            return G.NONE;
        }

        if (r.gestures().size() <= i || r.gestures().get(i).isEmpty()) return G.NONE;
        Category top = r.gestures().get(i).get(0);
        if (top.score() < 0.6f) return G.NONE;
        switch (top.categoryName()) {
            case "Open_Palm": return G.NONE;   // movement decides: swipe, or held still = play/pause
            case "Closed_Fist": return G.PLAY_PAUSE;
            case "Thumb_Up": return G.UP;       // thumbs read well from the sofa; a pointing-down finger did not
            case "Thumb_Down": return G.DOWN;
            case "Victory": return G.BACK;
            case "ILoveYou": return G.HOME;
            case "Pointing_Up": return G.UP;
            default: return G.NONE;
        }
    }

    /** A finger is out when its tip is clearly farther from the wrist than its middle joint. */
    private static boolean extended(List<NormalizedLandmark> p, int tip, int pip) {
        return dist(p.get(tip), p.get(0)) > dist(p.get(pip), p.get(0)) * 1.12;
    }

    private static double dist(NormalizedLandmark a, NormalizedLandmark b) {
        double dx = a.x() - b.x(), dy = a.y() - b.y();
        return Math.sqrt(dx * dx + dy * dy);
    }
}
