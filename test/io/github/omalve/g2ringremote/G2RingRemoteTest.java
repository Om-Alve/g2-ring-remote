package io.github.omalve.g2ringremote;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** JVM-only checks for everything except the Android device layer. Run by test.sh. */
public final class G2RingRemoteTest {
    private static int passed;
    private static String fixtures;

    public static void main(String[] args) throws Exception {
        fixtures = args.length > 0 ? args[0] : "test/fixtures";
        Log.quiet = true;
        decodesRecordedK02Swipes();
        decodesSingleFrameTapAndDroppedBuffer();
        firstTouchWithUnknownAxisIsTap();
        defaultMappingFollowsFaceclawWatchScheme();
        recordedKeysMapToGestures();
        heldDiscreteKeyFiresOnce();
        heldScrollKeyRepeats();
        cameraTapTogglesOncePerHold();
        keyUpAndAutorepeatAreIgnored();
        unmappedAndDisabledEventsSendNothing();
        configOverridesAndValidation();
        shippedConfigMatchesDefaults();
        deviceNameMatching();
        profilesParseAndInherit();
        profileMapStartsEmpty();
        profilesMatchInDefinitionOrder();
        rejectsBadDeviceSettings();
        routesEachNodeThroughItsProfile();
        repeatFilteringIsPerProfile();
        watchChordsBecomeSwipes();
        passthroughParsingAndInjection();
        passthroughRepeatsAreDropped();
        simulateAcceptsProfileQualifiedEvents();
        keyNames();
        senderSendsOneRequestAndParsesReplies();
        senderNeverRetries();
        senderWithoutTokenDoesNotConnect();
        System.out.println("g2-ring-remote: " + passed + " checks passed");
    }

    // --- getevent -l fixture replay -------------------------------------------------

    private static final Pattern LINE = Pattern.compile("^\\[\\s*[0-9.]+\\]\\s+(\\S+)\\s+(\\S+)\\s+(\\S+)");
    private static final Map<String, Integer> CODES = new HashMap<>();

    static {
        CODES.put("SYN_REPORT", 0);
        CODES.put("ABS_X", 0x00);
        CODES.put("ABS_Y", 0x01);
        CODES.put("ABS_MT_POSITION_X", 0x35);
        CODES.put("ABS_MT_POSITION_Y", 0x36);
        CODES.put("ABS_MT_TRACKING_ID", 0x39);
        CODES.put("BTN_TOUCH", 0x14a);
        CODES.put("MSC_SCAN", 0x04);
        CODES.put("KEY_VOLUMEUP", 115);
        CODES.put("KEY_VOLUMEDOWN", 114);
        CODES.put("KEY_NEXTSONG", 163);
        CODES.put("KEY_PREVIOUSSONG", 165);
        CODES.put("KEY_PLAYPAUSE", 164);
    }

    /** {type, code, value} triples from a `getevent -l -t` capture. */
    private static List<int[]> replay(String file) throws Exception {
        List<int[]> events = new ArrayList<>();
        for (String line : Files.readAllLines(new File(fixtures, file).toPath(), StandardCharsets.UTF_8)) {
            Matcher m = LINE.matcher(line);
            if (!m.find()) {
                continue;
            }
            int type = m.group(1).equals("EV_SYN") ? 0 : m.group(1).equals("EV_KEY") ? 1
                    : m.group(1).equals("EV_ABS") ? 3 : m.group(1).equals("EV_MSC") ? 4 : -1;
            Integer code = CODES.get(m.group(2));
            check(type >= 0 && code != null, "fixture label " + m.group(1) + " " + m.group(2));
            String v = m.group(3);
            int value = v.equals("DOWN") ? 1 : v.equals("UP") ? 0 : (int) Long.parseLong(v, 16);
            events.add(new int[] {type, code, value});
        }
        return events;
    }

    private static void decodesRecordedK02Swipes() throws Exception {
        TouchDecoder d = new TouchDecoder(100);
        List<String> got = new ArrayList<>();
        for (int[] e : replay("k02_touch.getevent.log")) {
            String g = d.feed(e[0], e[1], e[2]);
            if (g != null) {
                got.add(g);
            }
        }
        // The same sequence an independent decode of this HID capture reports.
        equal(Arrays.asList("down", "up", "left", "right", "tap", "down", "left", "up", "right", "up", "tap"),
                got, "recorded K02 touch gestures");
    }

    private static void decodesSingleFrameTapAndDroppedBuffer() {
        TouchDecoder d = new TouchDecoder(100);
        d.feed(3, TouchDecoder.ABS_X, 500);
        d.feed(3, TouchDecoder.ABS_Y, 400);
        d.feed(1, TouchDecoder.BTN_TOUCH, 1);
        d.feed(1, TouchDecoder.BTN_TOUCH, 0);
        equal("tap", d.feed(0, TouchDecoder.SYN_REPORT, 0), "down and up in one frame");

        d.feed(1, TouchDecoder.BTN_TOUCH, 1);
        d.feed(0, TouchDecoder.SYN_REPORT, 0);
        d.feed(0, TouchDecoder.SYN_DROPPED, 0);
        d.feed(3, TouchDecoder.ABS_Y, 900);
        d.feed(1, TouchDecoder.BTN_TOUCH, 0);
        equal(null, d.feed(0, TouchDecoder.SYN_REPORT, 0), "touch cut by SYN_DROPPED is discarded");
    }

    private static void firstTouchWithUnknownAxisIsTap() {
        // Started mid-stream: the ring's X is unknown and never reported.
        TouchDecoder d = new TouchDecoder(100);
        d.feed(1, TouchDecoder.BTN_TOUCH, 1);
        d.feed(3, TouchDecoder.ABS_Y, 590);
        d.feed(0, TouchDecoder.SYN_REPORT, 0);
        d.feed(3, TouchDecoder.ABS_Y, 0);
        d.feed(0, TouchDecoder.SYN_REPORT, 0);
        d.feed(1, TouchDecoder.BTN_TOUCH, 0);
        equal("up", d.feed(0, TouchDecoder.SYN_REPORT, 0), "vertical swipe with no X ever reported");
    }

    // --- mapping ------------------------------------------------------------------

    private static final class Recorder implements Router.GestureSink {
        final List<String> gestures = new ArrayList<>();
        final List<String> sources = new ArrayList<>();

        @Override
        public void deliver(String event, String gesture, String source) {
            gestures.add(gesture);
            sources.add(source);
        }
    }

    private static void defaultMappingFollowsFaceclawWatchScheme() {
        Map<String, String> m = new Config().defaultProfile().map;
        // The K02's D-pad drags the finger opposite to the button.
        equal("swipe-down", m.get("touch.up"), "touch.up");
        equal("swipe-up", m.get("touch.down"), "touch.down");
        equal("swipe-right", m.get("touch.left"), "touch.left");
        equal("swipe-left", m.get("touch.right"), "touch.right");
        equal("click", m.get("touch.tap"), "touch.tap");
        equal("double-click", m.get("key.PLAYPAUSE"), "center hold wakes / goes back");
        equal("long-press", m.get("key.NEXTSONG"), "system menu");
        equal("voice-toggle", m.get("key.VOLUMEUP"), "camera shutter toggles voice capture");
        for (String gesture : m.values()) {
            check(Config.GESTURES.contains(gesture), "default gesture is valid: " + gesture);
        }
    }

    private static void recordedKeysMapToGestures() throws Exception {
        Recorder r = new Recorder();
        Router router = new Router(new Config(), r);
        long t = 0;
        for (int[] e : replay("k02_keys.getevent.log")) {
            router.onEvent("/dev/input/event7", e[0], e[1], e[2], t);
            t += 1000; // spaced out: no repeat filtering
        }
        equal(Arrays.asList("voice-toggle", "voice-toggle", "long-press", "scroll-down", "scroll-down",
                "short-then-long-press", "voice-toggle"), r.gestures, "recorded K02 keys");
    }

    private static void heldDiscreteKeyFiresOnce() {
        Recorder r = new Recorder();
        Router router = new Router(new Config(), r);
        long[] times = {0, 390, 780, 1170, 2500}; // held for ~1.2 s, then a new press
        for (long t : times) {
            router.onEvent("k", 1, 164, 1, t);
            router.onEvent("k", 1, 164, 0, t + 19);
        }
        equal(Arrays.asList("double-click", "double-click"), r.gestures, "held PLAYPAUSE fires once per hold");
    }

    private static void heldScrollKeyRepeats() {
        Recorder r = new Recorder();
        Router router = new Router(new Config(), r);
        router.onEvent("k", 1, 114, 1, 0);
        router.onEvent("k", 1, 114, 1, 390);
        router.onEvent("k", 1, 114, 1, 780);
        equal(3, r.gestures.size(), "held VOLUMEDOWN keeps scrolling");
    }

    private static void cameraTapTogglesOncePerHold() {
        Recorder r = new Recorder();
        Router router = new Router(new Config(), r);
        router.onEvent("k", 1, 115, 1, 0);
        router.onEvent("k", 1, 115, 0, 19);
        router.onEvent("k", 1, 115, 1, 390);
        router.onEvent("k", 1, 115, 0, 409);
        router.onEvent("k", 1, 115, 1, 1200);
        equal(Arrays.asList("voice-toggle", "voice-toggle"), r.gestures,
                "camera hold repeats are ignored; next tap toggles speech off");
    }

    private static void keyUpAndAutorepeatAreIgnored() {
        Recorder r = new Recorder();
        Router router = new Router(new Config(), r);
        router.onEvent("k", 1, 163, 0, 0);
        router.onEvent("k", 1, 163, 2, 10);
        equal(0, r.gestures.size(), "key up / autorepeat values");
    }

    private static void unmappedAndDisabledEventsSendNothing() {
        Config c = Config.parse(Collections.singletonList("map.touch.tap=none"));
        Recorder r = new Recorder();
        Router router = new Router(c, r);
        router.onEvent("k", 1, 113, 1, 0); // MUTE: no default mapping
        equal(null, router.simulate("touch.tap"), "disabled tap");
        equal(0, r.gestures.size(), "nothing delivered");
        equal("click", new Router(new Config(), r).simulate("touch.tap"), "simulate uses the mapping");
    }

    // --- config -------------------------------------------------------------------

    private static void configOverridesAndValidation() {
        Config c = Config.parse(Arrays.asList(
                "# comment", "", "match.name = MyRing", "match.uniq=AA:BB:CC:DD:EE:FF",
                "faceclaw.port=9000", "map.touch.up=scroll-up", "map.key.ZOOMIN=click",
                "key.repeat-window-ms=0", "touch.tap-threshold=50"));
        equal("MyRing", c.defaultProfile().matchName, "match.name");
        equal(9000, c.port, "faceclaw.port");
        equal("scroll-up", c.defaultProfile().map.get("touch.up"), "override");
        equal("click", c.defaultProfile().map.get("key.ZOOMIN"), "new key mapping");
        equal("swipe-up", c.defaultProfile().map.get("touch.down"), "untouched default");
        equal(0L, c.defaultProfile().repeatWindowMs, "repeat window");
        equal(50, c.defaultProfile().tapThreshold, "tap threshold");
        rejects("map.touch.up=jump", "unknown gesture");
        rejects("map.foo=click", "map event prefix");
        rejects("faceclaw.port=0", "port range");
        rejects("colour=blue", "unknown setting");
        rejects("no equals sign", "key=value");
        rejects("faceclaw.source=phone", "source");
    }

    private static void shippedConfigMatchesDefaults() throws Exception {
        Config shipped = Config.load(new File(fixtures, "../../g2-ring-remote.conf"));
        Config defaults = new Config();
        equal(defaults.defaultProfile().map, shipped.defaultProfile().map,
                "g2-ring-remote.conf mapping equals the built-in defaults");
        equal(defaults.defaultProfile().matchName, shipped.defaultProfile().matchName,
                "g2-ring-remote.conf match.name");
        equal(defaults.tokenFile, shipped.tokenFile, "g2-ring-remote.conf token.file");
        equal(defaults.defaultProfile().repeatWindowMs, shipped.defaultProfile().repeatWindowMs,
                "g2-ring-remote.conf repeat window");
        equal(1, shipped.profiles().size(), "an existing config defines only the default profile");
        equal(Arrays.asList("default"), profileNames(shipped), "no device profile is enabled by default");
    }

    private static void rejects(String line, String what) {
        try {
            Config.parse(Collections.singletonList(line));
        } catch (IllegalArgumentException e) {
            check(e.getMessage().startsWith("line 1: "), "error names the line: " + e.getMessage());
            passed++;
            return;
        }
        throw new AssertionError("accepted invalid config (" + what + "): " + line);
    }

    private static void deviceNameMatching() {
        Config c = new Config();
        check(c.defaultProfile().matches("K02", ""), "K02");
        check(c.defaultProfile().matches("K02 Consumer Control", "x"), "K02 Consumer Control");
        check(!c.defaultProfile().matches("K020", ""), "K020");
        check(!c.defaultProfile().matches("gpio-keys", ""), "gpio-keys");
        check(!c.defaultProfile().matches(null, ""), "null name");
        Config pinned = Config.parse(Collections.singletonList("match.uniq=AA:BB:CC:DD:EE:FF"));
        check(pinned.defaultProfile().matches("K02", "aa:bb:cc:dd:ee:ff"), "uniq is case-insensitive");
        check(!pinned.defaultProfile().matches("K02", "00:11:22:33:44:55"), "another K02");
    }

    // --- device profiles ----------------------------------------------------------

    private static final String WATCH_NAME = "CMF Watch Pro 2";

    private static Config twoRemotes() {
        return Config.parse(Arrays.asList(
                // Device lines before the top-level ones: inheritance still uses the
                // final top-level values.
                "device.watch.match.name=" + WATCH_NAME,
                "device.watch.map.key.PLAYPAUSE=click",
                "device.watch.map.key.MUTE=voice-toggle",
                "device.watch.map.touch.tap=long-press",
                "match.name=K02",
                "faceclaw.source=ring",
                "key.repeat-window-ms=600",
                "touch.tap-threshold=80",
                "device.watch.faceclaw.source=watch",
                "device.watch.key.repeat-window-ms=150"));
    }

    private static void profilesParseAndInherit() {
        Config c = twoRemotes();
        Config.Profile ring = c.defaultProfile();
        Config.Profile watch = c.profile("watch");
        check(watch != null, "device.watch parses");
        equal(Arrays.asList("default", "watch"), profileNames(c), "default profile comes first");
        equal("K02", ring.matchName, "ring match.name");
        equal(WATCH_NAME, watch.matchName, "watch match.name");
        equal("", watch.matchUniq, "watch inherits an unset match.uniq");
        equal(150L, watch.repeatWindowMs, "watch repeat window");
        equal(600L, ring.repeatWindowMs, "ring repeat window is untouched");
        equal("watch", watch.source, "watch faceclaw.source");
        equal("ring", ring.source, "ring faceclaw.source");
        equal(80, watch.tapThreshold, "watch inherits the tap threshold");
        equal(80, ring.tapThreshold, "ring tap threshold");
    }

    private static void profileMapStartsEmpty() {
        Config c = twoRemotes();
        Config.Profile watch = c.profile("watch");
        equal(3, watch.map.size(), "only the device's own map lines");
        equal("click", watch.map.get("key.PLAYPAUSE"), "watch PLAYPAUSE");
        equal(null, watch.map.get("key.NEXTSONG"), "the default map is not inherited");
        equal(null, watch.map.get("touch.up"), "no inherited ring swipes");
        equal("double-click", c.defaultProfile().map.get("key.PLAYPAUSE"), "ring map unchanged");
        equal(null, c.defaultProfile().map.get("key.MUTE"), "the watch map does not leak into the ring");
    }

    private static void profilesMatchInDefinitionOrder() {
        Config c = Config.parse(Arrays.asList(
                "device.first.match.name=CMF",
                "device.second.match.name=CMF Watch Pro 2"));
        List<Config.Profile> matches = c.matching(WATCH_NAME, "");
        equal(2, matches.size(), "both profiles match the name");
        equal("first", matches.get(0).name, "first defined wins");
        equal("first", c.matching(WATCH_NAME, "").get(0).name, "matching is ordered");
        equal(0, c.matching("gpio-keys", "").size(), "unmatched node");
    }

    private static void rejectsBadDeviceSettings() {
        rejects("device.watch.map.key.MUTE=android:VOLUME_DOWN", "android keycode format");
        rejects("device.watch.map.key.MUTE=android:KEYCODE_volume_down", "lowercase keycode");
        rejects("device.watch.map.key.MUTE=android:", "empty keycode");
        rejects("device.watch.stt.port=9000", "global setting in a profile");
        rejects("device.watch.unknown=1", "unknown profile setting");
        rejects("device.default.match.name=K02", "reserved profile name");
        rejects("device..match.name=K02", "empty profile name");
        rejects("device.watch=CMF Watch Pro 2", "missing setting");
        rejects("device.watch.key.repeat-window-ms=later", "profile integer");
    }

    private static void routesEachNodeThroughItsProfile() {
        Config c = twoRemotes();
        Recorder r = new Recorder();
        Router router = new Router(c, r);
        Config.Profile ring = c.defaultProfile();
        Config.Profile watch = c.profile("watch");
        router.onEvent("/dev/input/event7", ring, 1, 164, 1, 0);
        router.onEvent("/dev/input/event9", watch, 1, 164, 1, 0);
        equal(Arrays.asList("double-click", "click"), r.gestures, "the same key through two profiles");
        equal(Arrays.asList("ring", "watch"), r.sources, "each profile's faceclaw source");
        tap(router, "/dev/input/event7", ring, 500, 400);
        tap(router, "/dev/input/event9", watch, 500, 400);
        equal(Arrays.asList("double-click", "click", "click", "long-press"), r.gestures,
                "touch decoding uses each profile's map");
        tap(router, "/dev/input/event9", watch, 900, 400);
        equal("long-press", r.gestures.get(r.gestures.size() - 1), "a second watch tap still uses its map");
    }

    private static void watchChordsBecomeSwipes() {
        Config c = Config.parse(Arrays.asList(
                "device.watch.match.name=" + WATCH_NAME,
                "device.watch.key.repeat-window-ms=150",
                "device.watch.map.key.VOLUMEUP=scroll-up",
                "device.watch.map.key.CODE_0x195=scroll-down",
                "device.watch.map.key.PLAYPAUSE=click",
                "device.watch.map.key.CODE_0x195+VOLUMEUP=swipe-up",
                "device.watch.map.key.CODE_0x195+MUTE=swipe-down",
                "device.watch.map.key.CODE_0x195+PREVIOUSSONG=swipe-left",
                "device.watch.map.key.CODE_0x195+NEXTSONG=swipe-right"));
        Config.Profile watch = c.profile("watch");
        Recorder r = new Recorder();
        Router router = new Router(c, r);
        int[][] reports = {
                {115}, {0x195}, {164},                 // single keys still work
                {115, 0x195}, {113, 0x195}, {165, 0x195}, {163, 0x195}, // swipes (kernel order)
        };
        long t = 0;
        for (int[] keys : reports) {
            for (int k : keys) {
                router.onEvent("/watch", watch, 4, 4, 0xc0000, t); // EV_MSC scan code: ignored
                router.onEvent("/watch", watch, 1, k, 1, t);
            }
            router.onEvent("/watch", watch, 0, 0, 0, t);
            for (int k : keys) {
                router.onEvent("/watch", watch, 1, k, 0, t + 5);
            }
            router.onEvent("/watch", watch, 0, 0, 0, t + 5);
            t += 1000;
        }
        equal(Arrays.asList("scroll-up", "scroll-down", "click",
                "swipe-up", "swipe-down", "swipe-left", "swipe-right"), r.gestures, "watch keys and chords");
        equal("key.CODE_0x195+NEXTSONG", Router.chordName(Arrays.asList(163, 0x195)), "chord name is sorted");

        // A profile without chord mappings dispatches on the key down, without waiting for SYN.
        Recorder plain = new Recorder();
        Router ring = new Router(new Config(), plain);
        ring.onEvent("k", 1, 164, 1, 0);
        equal(1, plain.gestures.size(), "no chords: immediate");
    }

    private static void repeatFilteringIsPerProfile() {
        Config c = twoRemotes();
        Recorder r = new Recorder();
        Router router = new Router(c, r);
        Config.Profile ring = c.defaultProfile();
        Config.Profile watch = c.profile("watch");
        router.onEvent("/ring", ring, 1, 164, 1, 0);
        router.onEvent("/ring", ring, 1, 164, 1, 390); // inside the ring's 600 ms window
        router.onEvent("/watch", watch, 1, 164, 1, 0);
        router.onEvent("/watch", watch, 1, 164, 1, 100); // inside the watch's 150 ms window
        router.onEvent("/watch", watch, 1, 164, 1, 260); // quiet for 160 ms: fires
        equal(Arrays.asList("double-click", "click", "click"), r.gestures,
                "each profile keeps its own repeat window");
        check(router.lastEvent().startsWith("watch key.PLAYPAUSE"), "the last event is profile-qualified");
    }

    private static void passthroughParsingAndInjection() throws Exception {
        Config c = Config.parse(Arrays.asList(
                "device.watch.match.name=" + WATCH_NAME,
                "device.watch.map.key.VOLUMEDOWN=android:KEYCODE_VOLUME_DOWN"));
        final List<String> commands = Collections.synchronizedList(new ArrayList<String>());
        Recorder r = new Recorder();
        Router router = new Router(c, r, argv -> {
            commands.add(String.join(" ", argv));
            return 0;
        });
        Config.Profile watch = c.profile("watch");
        router.onEvent("/watch", watch, 1, 114, 1, 0);
        waitFor(() -> !commands.isEmpty(), "input keyevent runs");
        equal(Collections.singletonList("input keyevent KEYCODE_VOLUME_DOWN"), commands,
                "the payload is exactly the configured key code");
        equal(0, r.gestures.size(), "a passthrough never reaches Faceclaw");
        equal("android:KEYCODE_VOLUME_DOWN", router.simulate(watch, "key.VOLUMEDOWN"),
                "simulate reports the passthrough");
    }

    private static void passthroughRepeatsAreDropped() throws Exception {
        Config c = Config.parse(Arrays.asList(
                "device.watch.match.name=" + WATCH_NAME,
                "device.watch.key.repeat-window-ms=150",
                "device.watch.map.key.VOLUMEDOWN=android:KEYCODE_VOLUME_DOWN"));
        final List<String> commands = Collections.synchronizedList(new ArrayList<String>());
        Router router = new Router(c, new Recorder(), argv -> {
            commands.add(String.join(" ", argv));
            return 0;
        });
        Config.Profile watch = c.profile("watch");
        router.onEvent("/watch", watch, 1, 114, 1, 0);
        router.onEvent("/watch", watch, 1, 114, 1, 100);
        check(router.lastEvent().contains("repeat ignored"), "the second press is filtered");
        waitFor(() -> commands.size() >= 1, "the first press is injected");
        Thread.sleep(100);
        equal(1, commands.size(), "one injection per hold");
    }

    private static void simulateAcceptsProfileQualifiedEvents() {
        Config c = twoRemotes();
        Recorder r = new Recorder();
        Router router = new Router(c, r);
        Config.Profile watch = c.profile("watch");
        equal("voice-toggle", router.simulate(watch, "key.MUTE"), "profile simulate");
        equal("click", router.simulate("touch.tap"), "the old single-argument form uses the default profile");
        equal(null, router.simulate("key.MUTE"), "the watch map does not leak into the default simulate");
        equal(null, router.simulate(watch, "touch.up"), "unmapped profile event");
        equal(Arrays.asList("voice-toggle", "click"), r.gestures, "simulate delivers like a device event");
    }

    private static void tap(Router router, String node, Config.Profile profile, int x, int y) {
        router.onEvent(node, profile, 3, TouchDecoder.ABS_X, x, 0);
        router.onEvent(node, profile, 3, TouchDecoder.ABS_Y, y, 0);
        router.onEvent(node, profile, 1, TouchDecoder.BTN_TOUCH, 1, 0);
        router.onEvent(node, profile, 1, TouchDecoder.BTN_TOUCH, 0, 0);
        router.onEvent(node, profile, 0, TouchDecoder.SYN_REPORT, 0, 0);
    }

    private static void keyNames() {
        equal("PLAYPAUSE", KeyNames.name(164), "164");
        equal("CODE_0x1a5", KeyNames.name(0x1a5), "unnamed code");
        check(KeyNames.isKey(115), "VOLUMEUP is a key");
        check(KeyNames.isKey(0x1a2), "ZOOMIN is a key");
        check(!KeyNames.isKey(0x14a), "BTN_TOUCH is not a key");
        check(!KeyNames.isKey(0x110), "BTN_LEFT is not a key");
    }

    // --- Faceclaw sender against a local fake -------------------------------------

    private static Config senderConfig(int port, String token) throws Exception {
        Config c = new Config();
        c.port = port;
        if (token == null) {
            c.tokenFile = "/nonexistent/g2-ring-remote-token";
        } else {
            File f = File.createTempFile("g2-ring-remote-token", ".txt");
            f.deleteOnExit();
            Files.write(f.toPath(), (token + "\n").getBytes(StandardCharsets.UTF_8));
            c.tokenFile = f.getPath();
        }
        return c;
    }

    private static void senderSendsOneRequestAndParsesReplies() throws Exception {
        final List<String> requests = Collections.synchronizedList(new ArrayList<String>());
        final String[] replies = {
            "{\"ok\":false,\"error\":\"unauthorized\",\"message\":\"Unknown token.\"}",
            "{\"ok\":true}",
            "{\"ok\":false,\"error\":\"unavailable\",\"message\":\"Faceclaw is not ready for input.\"}",
        };
        try (ServerSocket server = new ServerSocket(0)) {
            Thread fake = new Thread(() -> {
                for (String reply : replies) {
                    try (Socket s = server.accept()) {
                        BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                        requests.add(in.readLine());
                        OutputStream out = s.getOutputStream();
                        out.write((reply + "\n").getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    } catch (Exception e) {
                        return;
                    }
                }
            });
            fake.start();
            FaceclawSender sender = new FaceclawSender(senderConfig(server.getLocalPort(), "fc1_te\"st"));
            equal("unauthorized: Unknown token.", sender.ping(), "ping rejection");
            sender.start();
            sender.deliver("touch.tap", "click", "watch");
            sender.deliver("touch.left", "swipe-left", "watch");
            fake.join(5000);
            waitFor(() -> sender.stats().startsWith("delivered 1, failed 1"), "stats settle");
        }
        equal("{\"version\":1,\"token\":\"fc1_te\\\"st\",\"action\":\"ping\",\"permission\":\"input\"}",
                requests.get(0), "ping request (token escaped)");
        equal("{\"version\":1,\"token\":\"fc1_te\\\"st\",\"action\":\"input\",\"gesture\":\"click\",\"source\":\"watch\"}",
                requests.get(1), "input request");
        check(requests.get(2).contains("\"gesture\":\"swipe-left\""), "second gesture in order");
    }

    private static void senderNeverRetries() throws Exception {
        final AtomicInteger connections = new AtomicInteger();
        try (ServerSocket server = new ServerSocket(0)) {
            Thread fake = new Thread(() -> {
                try (Socket s = server.accept()) {
                    connections.incrementAndGet();
                    // Close without replying: the gesture may or may not have been applied.
                } catch (Exception ignored) {
                    // done
                }
                try {
                    server.setSoTimeout(1500);
                    server.accept();
                    connections.incrementAndGet();
                } catch (Exception ignored) {
                    // no retry arrived: expected
                }
            });
            fake.start();
            FaceclawSender sender = new FaceclawSender(senderConfig(server.getLocalPort(), "fc1_x"));
            sender.start();
            sender.deliver("key.PLAYPAUSE", "double-click", "watch");
            fake.join(5000);
            waitFor(() -> sender.stats().startsWith("delivered 0, failed 1"), "failure recorded");
        }
        equal(1, connections.get(), "a failed request is not retried");
    }

    private static void senderWithoutTokenDoesNotConnect() throws Exception {
        FaceclawSender sender = new FaceclawSender(senderConfig(1, null));
        check(sender.ping().contains("no token file"), "missing token reported: " + sender.ping());
    }

    // --- helpers ------------------------------------------------------------------

    private static List<String> profileNames(Config config) {
        List<String> names = new ArrayList<>();
        for (Config.Profile profile : config.profiles()) {
            names.add(profile.name);
        }
        return names;
    }

    private interface Condition {
        boolean holds();
    }

    private static void waitFor(Condition c, String what) throws InterruptedException {
        for (int i = 0; i < 100 && !c.holds(); i++) {
            Thread.sleep(20);
        }
        check(c.holds(), what);
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            throw new AssertionError("failed: " + what);
        }
        passed++;
    }

    private static void equal(Object expected, Object actual, String what) {
        boolean same = expected == null ? actual == null : expected.equals(actual);
        if (!same) {
            throw new AssertionError(what + ": expected <" + expected + "> but got <" + actual + ">");
        }
        passed++;
    }
}
