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
        keyUpAndAutorepeatAreIgnored();
        unmappedAndDisabledEventsSendNothing();
        configOverridesAndValidation();
        shippedConfigMatchesDefaults();
        deviceNameMatching();
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

        @Override
        public void deliver(String event, String gesture) {
            gestures.add(gesture);
        }
    }

    private static void defaultMappingFollowsFaceclawWatchScheme() {
        Map<String, String> m = new Config().map;
        // The K02's D-pad drags the finger opposite to the button.
        equal("swipe-down", m.get("touch.up"), "touch.up");
        equal("swipe-up", m.get("touch.down"), "touch.down");
        equal("swipe-right", m.get("touch.left"), "touch.left");
        equal("swipe-left", m.get("touch.right"), "touch.right");
        equal("click", m.get("touch.tap"), "touch.tap");
        equal("double-click", m.get("key.PLAYPAUSE"), "center hold wakes / goes back");
        equal("long-press", m.get("key.NEXTSONG"), "system menu");
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
        equal(Arrays.asList("scroll-up", "scroll-up", "long-press", "scroll-down", "scroll-down",
                "short-then-long-press", "scroll-up"), r.gestures, "recorded K02 keys");
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
        router.onEvent("k", 1, 115, 1, 0);
        router.onEvent("k", 1, 115, 1, 390);
        router.onEvent("k", 1, 115, 1, 780);
        equal(3, r.gestures.size(), "held VOLUMEUP keeps scrolling");
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
        equal("MyRing", c.matchName, "match.name");
        equal(9000, c.port, "faceclaw.port");
        equal("scroll-up", c.map.get("touch.up"), "override");
        equal("click", c.map.get("key.ZOOMIN"), "new key mapping");
        equal("swipe-up", c.map.get("touch.down"), "untouched default");
        equal(0L, c.repeatWindowMs, "repeat window");
        equal(50, c.tapThreshold, "tap threshold");
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
        equal(defaults.map, shipped.map, "g2-ring-remote.conf mapping equals the built-in defaults");
        equal(defaults.matchName, shipped.matchName, "g2-ring-remote.conf match.name");
        equal(defaults.tokenFile, shipped.tokenFile, "g2-ring-remote.conf token.file");
        equal(defaults.repeatWindowMs, shipped.repeatWindowMs, "g2-ring-remote.conf repeat window");
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
        check(c.matches("K02", ""), "K02");
        check(c.matches("K02 Consumer Control", "x"), "K02 Consumer Control");
        check(!c.matches("K020", ""), "K020");
        check(!c.matches("gpio-keys", ""), "gpio-keys");
        check(!c.matches(null, ""), "null name");
        Config pinned = Config.parse(Collections.singletonList("match.uniq=AA:BB:CC:DD:EE:FF"));
        check(pinned.matches("K02", "aa:bb:cc:dd:ee:ff"), "uniq is case-insensitive");
        check(!pinned.matches("K02", "00:11:22:33:44:55"), "another K02");
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
            sender.deliver("touch.tap", "click");
            sender.deliver("touch.left", "swipe-left");
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
            sender.deliver("key.PLAYPAUSE", "double-click");
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
