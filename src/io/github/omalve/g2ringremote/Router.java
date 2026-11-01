package io.github.omalve.g2ringremote;

import java.util.HashMap;
import java.util.Map;

/** Raw evdev events from grabbed nodes -> named ring events -> Faceclaw gestures. */
final class Router {
    interface GestureSink {
        void deliver(String event, String gesture);
    }

    private final Config config;
    private final GestureSink sink;
    private final Map<String, TouchDecoder> decoders = new HashMap<>();
    private final Map<String, Long> lastKeyDown = new HashMap<>();
    private String lastEvent = "none";

    Router(Config config, GestureSink sink) {
        this.config = config;
        this.sink = sink;
    }

    synchronized void onEvent(String node, int type, int code, int value, long nowMs) {
        if (type == TouchDecoder.EV_KEY && KeyNames.isKey(code)) {
            if (value == 1) {
                onKeyDown(code, nowMs);
            }
            return; // key up (0) and kernel auto-repeat (2) carry no gesture
        }
        TouchDecoder decoder = decoders.get(node);
        if (decoder == null) {
            decoder = new TouchDecoder(config.tapThreshold);
            decoders.put(node, decoder);
        }
        String touch = decoder.feed(type, code, value);
        if (touch != null) {
            dispatch("touch." + touch);
        }
    }

    /** Forgets per-node touch state when a node goes away. */
    synchronized void forget(String node) {
        decoders.remove(node);
    }

    /** Runs an event name through the mapping as if the ring sent it (no repeat filtering). */
    synchronized String simulate(String event) {
        return dispatch(event);
    }

    synchronized String lastEvent() {
        return lastEvent;
    }

    private void onKeyDown(int code, long nowMs) {
        String event = "key." + KeyNames.name(code);
        String gesture = config.map.get(event);
        Long previous = lastKeyDown.put(event, nowMs);
        // The K02 repeats a held key every ~390 ms. A held scroll should keep
        // scrolling, but a held back or menu key must fire once, so repeats of
        // other gestures are dropped until the key has been quiet for the window.
        boolean repeat = previous != null && nowMs - previous < config.repeatWindowMs;
        if (repeat && gesture != null && !gesture.startsWith("scroll-")) {
            lastEvent = event + " (repeat ignored)";
            Log.i(event + " repeat ignored");
            return;
        }
        dispatch(event);
    }

    private String dispatch(String event) {
        String gesture = config.map.get(event);
        if (gesture == null || gesture.equals(Config.NONE)) {
            lastEvent = event + " (unmapped)";
            Log.i(event + " -> nothing (unmapped)");
            return null;
        }
        lastEvent = event + " -> " + gesture;
        Log.i(event + " -> " + gesture);
        sink.deliver(event, gesture);
        return gesture;
    }
}
