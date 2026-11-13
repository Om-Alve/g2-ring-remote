package io.github.omalve.g2ringremote;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** {@code key=value} settings; see g2-ring-remote.conf for every key and its default. */
final class Config {
    static final List<String> GESTURES = Collections.unmodifiableList(Arrays.asList(
            "click", "double-click", "long-press", "short-then-long-press",
            "scroll-up", "scroll-down", "swipe-up", "swipe-down", "swipe-left", "swipe-right",
            "voice-toggle"));
    static final String NONE = "none";
    /** Map value prefix that re-injects the key into Android instead of sending a gesture. */
    static final String ANDROID = "android:";
    static final String DEFAULT_PROFILE = "default";

    private static final String DEVICE = "device.";
    private static final Pattern KEYCODE = Pattern.compile("KEYCODE_[A-Z0-9_]+");

    /** One input device: which node to grab and how to decode and map its events. */
    static final class Profile {
        final String name;
        String matchName = "K02";
        String matchUniq = "";
        String source = "watch";
        int tapThreshold = 100;
        long repeatWindowMs = 600;
        /** Decodes the watch's REWIND+bit reports as typed ASCII characters. */
        boolean textChords;
        /** Event name (touch.up, key.PLAYPAUSE, ...) to gesture, "none" or "android:KEYCODE_...". */
        final Map<String, String> map = new LinkedHashMap<>();

        Profile(String name) {
            this.name = name;
        }

        /** "K02" matches "K02" and "K02 Consumer Control"; match.uniq, if set, must equal the device id. */
        boolean matches(String name, String uniq) {
            if (name == null) {
                return false;
            }
            boolean nameOk = name.equals(matchName) || name.startsWith(matchName + " ");
            boolean uniqOk = matchUniq.isEmpty() || matchUniq.equalsIgnoreCase(uniq == null ? "" : uniq);
            return nameOk && uniqOk;
        }
    }

    String host = "127.0.0.1";
    int port = 8791;
    String tokenFile = "/data/local/tmp/g2-ring-remote/token";
    /** Deepgram speech-to-text for Faceclaw's "Whisper server" provider; see SttProxy. */
    boolean sttEnabled = true;
    int sttPort = 8004;
    String sttKeyFile = "/data/local/tmp/g2-ring-remote/deepgram-key";
    String sttModel = "nova-3";
    /** Used when Faceclaw sends no language; "multi" is Deepgram's multilingual mode. */
    String sttLanguage = "en";
    /** If set, requests must send "Authorization: Bearer <this>" (Faceclaw's Whisper server API key). */
    String sttLocalKey = "";
    String sttEndpoint = "https://api.deepgram.com/v1/listen";

    final Profile defaultProfile = new Profile(DEFAULT_PROFILE);
    /** Definition order: the default profile first, then the device profiles. */
    private final Map<String, Profile> profiles = new LinkedHashMap<>();

    Config() {
        profiles.put(DEFAULT_PROFILE, defaultProfile);
        // Faceclaw's watch scheme: swipes navigate (outside spatial screens, right
        // selects and left goes back), a tap selects. The center hold is
        // double-click because only a double-click wakes a sleeping or locked display.
        // The K02 scripts each D-pad swipe opposite to the button, TikTok style
        // (the down button drags the finger up), so the finger direction is inverted.
        // Verified on the ring on 2026-11-01.
        defaultProfile.map.put("touch.up", "swipe-down");
        defaultProfile.map.put("touch.down", "swipe-up");
        defaultProfile.map.put("touch.left", "swipe-right");
        defaultProfile.map.put("touch.right", "swipe-left");
        defaultProfile.map.put("touch.tap", "click");
        defaultProfile.map.put("key.PLAYPAUSE", "double-click");
        defaultProfile.map.put("key.NEXTSONG", "long-press");
        defaultProfile.map.put("key.PREVIOUSSONG", "short-then-long-press");
        defaultProfile.map.put("key.VOLUMEUP", "voice-toggle");
        defaultProfile.map.put("key.VOLUMEDOWN", "scroll-down");
    }

    static Config load(File file) throws IOException {
        return parse(Files.readAllLines(file.toPath(), StandardCharsets.UTF_8));
    }

    static Config parse(List<String> lines) {
        Config c = new Config();
        // device.NAME.SETTING -> {setting, value, line}; applied after the top-level
        // lines so that a profile inherits the final top-level values, whatever their order.
        Map<String, List<String[]>> devices = new LinkedHashMap<>();
        int number = 0;
        for (String raw : lines) {
            number++;
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException("line " + number + ": expected key=value");
            }
            String key = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();
            try {
                if (key.startsWith(DEVICE)) {
                    int dot = key.indexOf('.', DEVICE.length());
                    if (dot < 0) {
                        throw new IllegalArgumentException("device settings are device.NAME.SETTING: " + key);
                    }
                    String name = key.substring(DEVICE.length(), dot);
                    String setting = key.substring(dot + 1);
                    if (name.isEmpty()) {
                        throw new IllegalArgumentException("device.NAME.SETTING needs a profile name");
                    }
                    if (name.equals(DEFAULT_PROFILE)) {
                        throw new IllegalArgumentException("the top-level settings are the default profile");
                    }
                    List<String[]> settings = devices.get(name);
                    if (settings == null) {
                        settings = new ArrayList<>();
                        devices.put(name, settings);
                    }
                    settings.add(new String[] {setting, value, Integer.toString(number)});
                } else {
                    c.set(c.defaultProfile, key, value);
                }
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("line " + number + ": " + e.getMessage());
            }
        }
        for (Map.Entry<String, List<String[]>> entry : devices.entrySet()) {
            Profile p = new Profile(entry.getKey());
            // Everything except the map is inherited from the top-level profile; a
            // device profile's map starts empty so it lists only that device's events.
            p.matchName = c.defaultProfile.matchName;
            p.matchUniq = c.defaultProfile.matchUniq;
            p.source = c.defaultProfile.source;
            p.tapThreshold = c.defaultProfile.tapThreshold;
            p.repeatWindowMs = c.defaultProfile.repeatWindowMs;
            p.textChords = c.defaultProfile.textChords;
            for (String[] setting : entry.getValue()) {
                try {
                    c.set(p, setting[0], setting[1]);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException(
                            "line " + setting[2] + ": device." + p.name + " " + e.getMessage());
                }
            }
            c.profiles.put(p.name, p);
        }
        return c;
    }

    Profile defaultProfile() {
        return defaultProfile;
    }

    /** The default profile first, then every device profile in definition order. */
    List<Profile> profiles() {
        return new ArrayList<>(profiles.values());
    }

    Profile profile(String name) {
        return profiles.get(name);
    }

    /** True when any profile types, so the Faceclaw token needs the text permission too. */
    boolean typesText() {
        for (Profile p : profiles.values()) {
            if (p.textChords) {
                return true;
            }
        }
        return false;
    }

    /** Profiles that match a node, in definition order; the first one grabs it. */
    List<Profile> matching(String name, String uniq) {
        List<Profile> found = new ArrayList<>();
        for (Profile p : profiles.values()) {
            if (p.matches(name, uniq)) {
                found.add(p);
            }
        }
        return found;
    }

    private void set(Profile profile, String key, String value) {
        if (key.startsWith("map.")) {
            String event = key.substring(4);
            if (!event.startsWith("touch.") && !event.startsWith("key.")) {
                throw new IllegalArgumentException("map events start with touch. or key.: " + key);
            }
            if (value.startsWith(ANDROID)) {
                if (!KEYCODE.matcher(value.substring(ANDROID.length())).matches()) {
                    throw new IllegalArgumentException(
                            "android: needs KEYCODE_[A-Z0-9_]+ for " + key + ": " + value);
                }
            } else if (!value.equals(NONE) && !GESTURES.contains(value)) {
                throw new IllegalArgumentException("unknown gesture '" + value + "' for " + key);
            }
            profile.map.put(event, value);
            return;
        }
        switch (key) {
            case "match.name":
                if (value.isEmpty()) {
                    throw new IllegalArgumentException("match.name must not be empty");
                }
                profile.matchName = value;
                return;
            case "match.uniq":
                profile.matchUniq = value;
                return;
            case "faceclaw.source":
                if (!value.equals("watch") && !value.equals("ring")) {
                    throw new IllegalArgumentException("faceclaw.source is watch or ring");
                }
                profile.source = value;
                return;
            case "touch.tap-threshold":
                profile.tapThreshold = parseInt(key, value, 1, 100000);
                return;
            case "key.repeat-window-ms":
                profile.repeatWindowMs = parseInt(key, value, 0, 60000);
                return;
            case "text.enabled":
                if (!value.equals("true") && !value.equals("false")) {
                    throw new IllegalArgumentException("text.enabled is true or false");
                }
                profile.textChords = value.equals("true");
                return;
            default:
                if (profile != defaultProfile) {
                    throw new IllegalArgumentException("is not a per-device setting: " + key);
                }
                setGlobal(key, value);
        }
    }

    private void setGlobal(String key, String value) {
        switch (key) {
            case "faceclaw.host":
                host = value;
                break;
            case "faceclaw.port":
                port = parseInt(key, value, 1, 65535);
                break;
            case "token.file":
                tokenFile = value;
                break;
            case "stt.enabled":
                if (!value.equals("true") && !value.equals("false")) {
                    throw new IllegalArgumentException("stt.enabled is true or false");
                }
                sttEnabled = value.equals("true");
                break;
            case "stt.port":
                sttPort = parseInt(key, value, 1024, 65535);
                break;
            case "stt.key-file":
                sttKeyFile = value;
                break;
            case "stt.model":
                if (value.isEmpty()) {
                    throw new IllegalArgumentException("stt.model must not be empty");
                }
                sttModel = value;
                break;
            case "stt.language":
                sttLanguage = value;
                break;
            case "stt.local-key":
                sttLocalKey = value;
                break;
            case "stt.endpoint":
                if (!value.startsWith("https://") && !value.startsWith("http://127.0.0.1")) {
                    throw new IllegalArgumentException("stt.endpoint must be https://");
                }
                sttEndpoint = value;
                break;
            default:
                throw new IllegalArgumentException("unknown setting " + key);
        }
    }

    private static int parseInt(String key, String value, int min, int max) {
        try {
            int n = Integer.parseInt(value);
            if (n < min || n > max) {
                throw new NumberFormatException();
            }
            return n;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be an integer in " + min + ".." + max);
        }
    }
}
