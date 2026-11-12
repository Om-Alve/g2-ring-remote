package io.github.omalve.g2ringremote;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Raw evdev events from grabbed nodes -> named events -> Faceclaw gestures, or back
 * into Android. Each node is routed through the profile that matched it, so two
 * remotes keep separate maps, repeat windows and touch state.
 */
final class Router {
    interface GestureSink {
        void deliver(String event, String gesture, String source);
    }

    /** Runs {@code input keyevent}; injectable so tests never spawn a process. */
    interface CommandRunner {
        int run(String... argv) throws IOException, InterruptedException;
    }

    private static final int INJECT_TIMEOUT_SECONDS = 5;

    private final Config config;
    private final GestureSink sink;
    private final CommandRunner commands;
    private final Map<String, Map<String, TouchDecoder>> decoders = new HashMap<>();
    private final Map<String, Long> lastKeyDown = new HashMap<>();
    /** Key downs of the current report, per profile/node, for profiles that map key chords. */
    private final Map<String, List<Integer>> pendingKeys = new HashMap<>();
    private ExecutorService androidKeys;
    private String lastEvent = "none";

    Router(Config config, GestureSink sink) {
        this(config, sink, Router::inputKeyevent);
    }

    Router(Config config, GestureSink sink, CommandRunner commands) {
        this.config = config;
        this.sink = sink;
        this.commands = commands;
    }

    /** Routes through the default profile: the top-level settings and map. */
    synchronized void onEvent(String node, int type, int code, int value, long nowMs) {
        onEvent(node, config.defaultProfile(), type, code, value, nowMs);
    }

    synchronized void onEvent(String node, Config.Profile profile, int type, int code, int value, long nowMs) {
        if (type == TouchDecoder.EV_KEY && KeyNames.isKey(code)) {
            if (value == 1) {
                if (hasChords(profile)) {
                    String slot = profile.name + "/" + node;
                    List<Integer> keys = pendingKeys.get(slot);
                    if (keys == null) {
                        keys = new ArrayList<>();
                        pendingKeys.put(slot, keys);
                    }
                    keys.add(code);
                } else {
                    onKeyDown(node, profile, "key." + KeyNames.name(code), nowMs);
                }
            }
            return; // key up (0) and kernel auto-repeat (2) carry no gesture
        }
        if (type == TouchDecoder.EV_SYN && code == TouchDecoder.SYN_REPORT) {
            List<Integer> keys = pendingKeys.remove(profile.name + "/" + node);
            if (keys != null && !keys.isEmpty()) {
                onKeyDown(node, profile, chordName(keys), nowMs);
            }
        }
        Map<String, TouchDecoder> byNode = decoders.get(profile.name);
        if (byNode == null) {
            byNode = new HashMap<>();
            decoders.put(profile.name, byNode);
        }
        TouchDecoder decoder = byNode.get(node);
        if (decoder == null) {
            decoder = new TouchDecoder(profile.tapThreshold);
            byNode.put(node, decoder);
        }
        String touch = decoder.feed(type, code, value);
        if (touch != null) {
            dispatch(profile, "touch." + touch);
        }
    }

    /** Forgets per-node touch state when a node goes away. */
    synchronized void forget(String node) {
        for (Map<String, TouchDecoder> byNode : decoders.values()) {
            byNode.remove(node);
        }
    }

    /** Runs an event through the default profile's map as if its device sent it (no repeat filtering). */
    synchronized String simulate(String event) {
        return dispatch(config.defaultProfile(), event);
    }

    synchronized String simulate(Config.Profile profile, String event) {
        return dispatch(profile, event);
    }

    synchronized String lastEvent() {
        return lastEvent;
    }

    /**
     * A profile that maps a chord (key.A+B) reads keys per report: keys pressed in the same
     * report form one event. The CMF watch sends a swipe as two keys in one report.
     */
    private static boolean hasChords(Config.Profile profile) {
        for (String event : profile.map.keySet()) {
            if (event.startsWith("key.") && event.indexOf('+') >= 0) {
                return true;
            }
        }
        return false;
    }

    /** key.NAME for one key, key.A+B (names sorted) for keys pressed together. */
    static String chordName(List<Integer> codes) {
        List<String> names = new ArrayList<>();
        for (int code : codes) {
            String name = KeyNames.name(code);
            if (!names.contains(name)) {
                names.add(name);
            }
        }
        Collections.sort(names);
        StringBuilder event = new StringBuilder("key.");
        for (int i = 0; i < names.size(); i++) {
            event.append(i == 0 ? "" : "+").append(names.get(i));
        }
        return event.toString();
    }

    private void onKeyDown(String node, Config.Profile profile, String event, long nowMs) {
        String action = profile.map.get(event);
        Long previous = lastKeyDown.put(profile.name + "/" + node + "/" + event, nowMs);
        // The K02 repeats a held key every ~390 ms. A held scroll should keep
        // scrolling, but a held back or menu key must fire once, so repeats of
        // other gestures are dropped until the key has been quiet for the window.
        boolean repeat = previous != null && nowMs - previous < profile.repeatWindowMs;
        if (repeat && action != null && !action.startsWith("scroll-")) {
            lastEvent = label(profile) + event + " (repeat ignored)";
            Log.i(label(profile) + event + " repeat ignored");
            return;
        }
        dispatch(profile, event);
    }

    private String dispatch(Config.Profile profile, String event) {
        String action = profile.map.get(event);
        if (action == null || action.equals(Config.NONE)) {
            lastEvent = label(profile) + event + " (unmapped)";
            Log.i(label(profile) + event + " -> nothing (unmapped)");
            return null;
        }
        if (action.startsWith(Config.ANDROID)) {
            String keycode = action.substring(Config.ANDROID.length());
            lastEvent = label(profile) + event + " -> " + action;
            Log.i(label(profile) + event + " -> " + keycode + " (android)");
            inject(keycode);
            return action;
        }
        lastEvent = label(profile) + event + " -> " + action;
        Log.i(label(profile) + event + " -> " + action);
        sink.deliver(event, action, profile.source);
        return action;
    }

    /** "watch " for a device profile, "" for the default so single-ring logs read as they always have. */
    private static String label(Config.Profile profile) {
        return profile.name.equals(Config.DEFAULT_PROFILE) ? "" : profile.name + " ";
    }

    private void inject(String keycode) {
        androidKeys().execute(() -> {
            int exit;
            try {
                exit = commands.run("input", "keyevent", keycode);
            } catch (IOException e) {
                Log.i("input keyevent " + keycode + " failed: " + e);
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Log.i("input keyevent " + keycode + " interrupted");
                return;
            }
            Log.i("input keyevent " + keycode + (exit == 0 ? ": ok" : ": exit " + exit));
        });
    }

    private synchronized ExecutorService androidKeys() {
        if (androidKeys == null) {
            androidKeys = Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "android-keys");
                thread.setDaemon(true);
                return thread;
            });
        }
        return androidKeys;
    }

    private static int inputKeyevent(String... argv) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(argv).redirectErrorStream(true).start();
        if (!process.waitFor(INJECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroy();
            return -1;
        }
        return process.exitValue();
    }
}
