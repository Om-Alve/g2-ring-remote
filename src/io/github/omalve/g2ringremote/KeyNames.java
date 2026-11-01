package io.github.omalve.g2ringremote;

import java.util.HashMap;
import java.util.Map;

/** Linux key codes (input-event-codes.h) that HID remotes like the K02 can send, by config name. */
final class KeyNames {
    private static final Map<Integer, String> NAMES = new HashMap<>();

    static {
        NAMES.put(113, "MUTE");
        NAMES.put(114, "VOLUMEDOWN");
        NAMES.put(115, "VOLUMEUP");
        NAMES.put(116, "POWER");
        NAMES.put(139, "MENU");
        NAMES.put(150, "WWW");
        NAMES.put(158, "BACK");
        NAMES.put(163, "NEXTSONG");
        NAMES.put(164, "PLAYPAUSE");
        NAMES.put(165, "PREVIOUSSONG");
        NAMES.put(166, "STOPCD");
        NAMES.put(171, "CONFIG");
        NAMES.put(172, "HOMEPAGE");
        NAMES.put(200, "PLAYCD");
        NAMES.put(201, "PAUSECD");
        NAMES.put(207, "PLAY");
        NAMES.put(240, "UNKNOWN");
        NAMES.put(241, "VIDEO_NEXT");
        NAMES.put(0x17b, "VCR2");
        NAMES.put(0x1a2, "ZOOMIN");
        NAMES.put(0x1a3, "ZOOMOUT");
    }

    private KeyNames() {}

    /** The config name for a key code, e.g. 164 -> PLAYPAUSE; unnamed codes become CODE_0x1a5. */
    static String name(int code) {
        String name = NAMES.get(code);
        return name != null ? name : "CODE_0x" + Integer.toHexString(code);
    }

    /** Keyboard keys, as opposed to BTN_* pointer/touch buttons (0x100-0x15f, 0x2c0+). */
    static boolean isKey(int code) {
        return code < 0x100 || (code >= 0x160 && code < 0x2c0);
    }
}
