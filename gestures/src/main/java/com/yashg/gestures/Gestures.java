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
        OK("🤏", "OK / open"),
        SWIPE_LEFT("⇦", "Swipe left"), SWIPE_RIGHT("⇨", "Swipe right"),
        SWIPE_UP("⇧", "Swipe up"), SWIPE_DOWN("⇩", "Swipe down"),
        PLAY_PAUSE("✋", "Play / pause"),
        VOL_UP("👍", "Volume up"), VOL_DOWN("👎", "Volume down"),
        MUTE("✊", "Mute"),
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
        if (r == null || r.landmarks().isEmpty()) return false;
        if (!r.gestures().isEmpty() && !r.gestures().get(0).isEmpty()) {
            Category top = r.gestures().get(0).get(0);
            if ("Open_Palm".equals(top.categoryName()) && top.score() >= 0.5f) return true;
        }
        List<NormalizedLandmark> p = r.landmarks().get(0);
        return extended(p, 8, 6) && extended(p, 12, 10) && extended(p, 16, 14) && extended(p, 20, 18);
    }

    static G classify(GestureRecognizerResult r) {
        if (r == null || r.landmarks().isEmpty()) return G.NONE;
        List<NormalizedLandmark> p = r.landmarks().get(0);
        double hand = dist(p.get(0), p.get(9));          // wrist to middle-finger base
        if (hand <= 0) return G.NONE;

        boolean index = extended(p, 8, 6), middle = extended(p, 12, 10), ring = extended(p, 16, 14),
                pinky = extended(p, 20, 18);

        // Pinch: thumb tip on index tip.
        if (dist(p.get(4), p.get(8)) < 0.30 * hand && (middle || ring || pinky)) return G.OK;

        // Pointing: only the index finger out; its direction picks the arrow.
        if (index && !middle && !ring && !pinky) {
            double dx = p.get(8).x() - p.get(5).x(), dy = p.get(8).y() - p.get(5).y();
            if (Math.abs(dx) > Math.abs(dy) * 1.2) return dx > 0 ? G.LEFT : G.RIGHT;  // mirrored
            if (Math.abs(dy) > Math.abs(dx) * 1.2) return dy < 0 ? G.UP : G.DOWN;
            return G.NONE;
        }

        if (r.gestures().isEmpty() || r.gestures().get(0).isEmpty()) return G.NONE;
        Category top = r.gestures().get(0).get(0);
        if (top.score() < 0.6f) return G.NONE;
        switch (top.categoryName()) {
            case "Open_Palm": return G.NONE;   // movement decides: swipe, or held still = play/pause
            case "Closed_Fist": return G.MUTE;
            case "Thumb_Up": return G.VOL_UP;
            case "Thumb_Down": return G.VOL_DOWN;
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
