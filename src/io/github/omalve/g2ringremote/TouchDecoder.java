package io.github.omalve.g2ringremote;

/**
 * Turns the K02's synthetic touchscreen reports into one of {@code up}, {@code down},
 * {@code left}, {@code right} or {@code tap} per touch.
 *
 * <p>The ring has no touch surface: each short D-pad press emits a scripted swipe
 * (BTN_TOUCH down, a few ABS moves, BTN_TOUCH up) and the center press a stationary
 * touch. The kernel drops ABS values that did not change, so a vertical swipe that
 * starts at the previous X reports no X at all. Positions are therefore tracked
 * across touches, and an axis that never moved counts as zero movement. Directions
 * are the finger's direction of travel, not the D-pad button's label.
 */
final class TouchDecoder {
    static final int EV_SYN = 0;
    static final int EV_KEY = 1;
    static final int EV_ABS = 3;
    static final int SYN_REPORT = 0;
    static final int SYN_DROPPED = 3;
    static final int BTN_TOUCH = 0x14a;
    static final int ABS_X = 0x00;
    static final int ABS_Y = 0x01;
    static final int ABS_MT_POSITION_X = 0x35;
    static final int ABS_MT_POSITION_Y = 0x36;

    private static final int UNKNOWN = Integer.MIN_VALUE;

    private final int tapThreshold;
    private int x = UNKNOWN;
    private int y = UNKNOWN;
    private boolean touching;
    private boolean pendingDown;
    private boolean pendingUp;
    private int startX = UNKNOWN;
    private int startY = UNKNOWN;

    TouchDecoder(int tapThreshold) {
        this.tapThreshold = tapThreshold;
    }

    /** Feeds one evdev event. Returns the completed touch's direction or "tap", else null. */
    String feed(int type, int code, int value) {
        if (type == EV_ABS) {
            if (code == ABS_X || code == ABS_MT_POSITION_X) {
                x = value;
            } else if (code == ABS_Y || code == ABS_MT_POSITION_Y) {
                y = value;
            }
            return null;
        }
        if (type == EV_KEY && code == BTN_TOUCH) {
            if (value == 1) {
                pendingDown = true;
            } else if (value == 0) {
                pendingUp = true;
            }
            return null;
        }
        if (type != EV_SYN) {
            return null;
        }
        if (code == SYN_DROPPED) {
            // The kernel buffer overflowed; the current touch is unreliable.
            touching = false;
            pendingDown = false;
            pendingUp = false;
            return null;
        }
        if (code != SYN_REPORT) {
            return null;
        }
        String result = null;
        if (pendingDown) {
            touching = true;
            startX = x;
            startY = y;
        } else if (touching) {
            // An axis first seen mid-touch starts where it was first reported.
            if (startX == UNKNOWN) {
                startX = x;
            }
            if (startY == UNKNOWN) {
                startY = y;
            }
        }
        if (pendingUp && touching) {
            result = classify(delta(startX, x), delta(startY, y));
            touching = false;
        }
        pendingDown = false;
        pendingUp = false;
        return result;
    }

    private static int delta(int start, int end) {
        return start == UNKNOWN || end == UNKNOWN ? 0 : end - start;
    }

    private String classify(int dx, int dy) {
        if (Math.max(Math.abs(dx), Math.abs(dy)) < tapThreshold) {
            return "tap";
        }
        if (Math.abs(dy) >= Math.abs(dx)) {
            return dy > 0 ? "down" : "up";
        }
        return dx > 0 ? "right" : "left";
    }
}
