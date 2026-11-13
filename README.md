# G2 Ring Remote

Use a cheap Bluetooth "TikTok remote" ring (the **K02**) as a remote control for your
Even Realities G2 glasses, through [Faceclaw](https://github.com/jimrandomh/faceclaw),
the unofficial Android app for the G2. **No root needed.**

**Demo:** [watch it in action on X](https://x.com/alve_om/status/2116800999570678031)

**The ring:** [K02 Bluetooth remote ring on Amazon India](https://amzn.in/d/0g2CrdIc)

g2-ring-remote is a small background process that takes exclusive control of its remotes'
input devices and turns their presses into Faceclaw gestures. It can grab several
Bluetooth HID remotes at once — the K02 ring plus, say, a CMF Watch Pro 2 — each with its
own key map. It runs as the adb shell user, and can be restarted after a reboot without a
computer through [Shizuku](https://shizuku.rikka.app/).

```
K02, watch ──Bluetooth HID──▶ Android ──▶ /dev/input/eventN
                                            │  EVIOCGRAB (exclusive)
                                            ▼
                              g2-ring-remote ──▶ 127.0.0.1:8791 ──▶ Faceclaw ──▶ G2
                                              (Faceclaw's input API)
```

While g2-ring-remote holds the grab, the kernel delivers the remotes' events **only** to
g2-ring-remote. Android's input pipeline receives nothing from them:

- no taps or scrolls on whatever is on the phone screen;
- no volume changes, no music play/pause/skip;
- no screen wake and no lock-screen actions;
- it works with the phone locked and the screen off, whatever app is in front.

The phone's own buttons, touchscreen and other Bluetooth devices are not affected. Only
input nodes that match a configured device profile are grabbed: the default profile
grabs nodes named `K02` or starting with `K02 `, and each `device.<name>.match.name`
adds one more remote. Stopping g2-ring-remote, or a reboot, releases the grab and the
remotes behave normally again.

> Unofficial. Not affiliated with Even Realities, the Faceclaw authors, the ring's
> manufacturer, or Shizuku. Use at your own risk.

## Requirements

- An Android phone with **Android 11 or newer**, with
  [USB or wireless debugging](https://developer.android.com/tools/adb) enabled.
- **Faceclaw** installed and paired with your G2, with its **input API** enabled (see
  [Faceclaw input token](#faceclaw-input-token)).
- A **K02** Bluetooth ring ([the one used here](https://amzn.in/d/0g2CrdIc)) paired
  with the phone. Any other HID remote can be added as a device profile; see
  [Other remotes](#other-remotes).
- To build: a JDK (17 or 21) and the Android SDK with platform 35 and build-tools. The
  scripts find them through `ANDROID_HOME` and `JAVA_HOME`; see `env.sh`. No Gradle.
- Optional, for restarting after a reboot without a computer:
  [Shizuku](https://shizuku.rikka.app/).

Tested on a Nothing Phone (2a) with Android 15 and SELinux enforcing.

## Controls

| Ring | Arrives as | Faceclaw gesture |
| --- | --- | --- |
| D-pad short press, up/down/left/right | scripted touchscreen swipe, finger moving the opposite way | `swipe-up/down/left/right`, matching the button |
| Center short press | stationary touch | `click` (select) |
| Center hold | `KEY_PLAYPAUSE` | `double-click` (back; wakes a sleeping or locked display) |
| Hold that sends `KEY_NEXTSONG` | key | `long-press` (system menu) |
| Hold that sends `KEY_PREVIOUSSONG` | key | `short-then-long-press` (app menu) |
| Camera button tap | `KEY_VOLUMEUP` | start voice input; tap again to stop |
| Hold that sends `KEY_VOLUMEUP` | key, repeats while held | same voice toggle; repeats are ignored |
| Hold that sends `KEY_VOLUMEDOWN` | key, repeats while held | `scroll-down` |

Swipes use Faceclaw's watch scheme. In grids and menus they move spatially. Elsewhere, up
and down scroll, right selects, and left goes back. A held key other than a scroll fires
once per hold. The camera button and the held up direction both send `KEY_VOLUMEUP`,
so this mapping replaces held scroll-up. Short up presses still navigate. After the
second camera tap, select Send in the voice dialog to deliver the transcript.
The voice toggle requires a Faceclaw build that accepts the `voice-toggle` input gesture.

In the log and in `g2-ring-remote.conf`, `touch.*` names the direction the **finger** travels
in the ring's scripted swipe. The K02 drags the finger opposite to the button, so the
default config maps each finger direction to its opposite (`touch.up=swipe-down` and so
on). Holds are named after the key the ring sends. Watch `./g2-ring-remote.sh logs -f` while
pressing a control, and remap in `g2-ring-remote.conf` if your ring differs.

### A second remote: the CMF Watch Pro 2

The watch appears as its own Classic Bluetooth HID device. A `device.watch.*` profile in
`g2-ring-remote.conf` grabs it next to the ring; its map is separate from the ring's.

| Watch | Arrives as | Faceclaw gesture |
| --- | --- | --- |
| Scroll up | `KEY_VOLUMEUP` | `scroll-up` |
| Scroll down | `KEY_LAST` (`CODE_0x195`) | `scroll-down` |
| Tap | `KEY_PLAYPAUSE` | `click` |
| Double tap | `KEY_REWIND` (`CODE_0xa8`) | `double-click` |
| Hold | `KEY_NEXTSONG` | `long-press` |
| Hold, other side | `KEY_PREVIOUSSONG` | `short-then-long-press` |
| Microphone | `KEY_MUTE` | `voice-toggle` |
| Camera shutter | `KEY_VOLUMEDOWN` | `android:KEYCODE_VOLUME_DOWN` (back to Android) |

The watch repeats nothing, so its profile drops repeats inside 150 ms instead of the
ring's 600 ms. The camera shutter is re-injected into Android with `input keyevent`, so
the phone's camera app still takes the photo while the watch is grabbed. See the
commented example block in `g2-ring-remote.conf`.

#### Typing on the watch

Set `device.watch.text.enabled=true` to turn the watch's on-watch keyboard into typed
text. Each keystroke arrives as `KEY_REWIND` plus the bits of one ASCII byte, so the
report is `0x80 | c`:

| Report | Character |
| --- | --- |
| `0x88` | backspace |
| `0xa0` | space |
| `0xb0`..`0xb9` | `0`..`9` |
| `0xc1`..`0xda` | `A`..`Z` |
| `0xe1`..`0xfa` | `a`..`z` |

`KEY_REWIND` alone is still `double-click`. A report with `KEY_REWIND` plus any other
key is sent to Faceclaw as text and never as a gesture, and it is not repeat-filtered,
so two of the same character both arrive. Text goes through the same ordered queue as
gestures; consecutive keystrokes are merged into one request of at most 64 characters.
A rejected or failed request is logged with the error and the character count, never
the text.

The Faceclaw token needs the **Text to foreground window** permission as well as
**Provide input**. When any profile types, `status` pings for both permissions and
shows each result.

## Install and run

From this directory, with the phone connected over adb:

```sh
./g2-ring-remote.sh install                 # build and copy to /data/local/tmp/g2-ring-remote
./g2-ring-remote.sh token FILE              # store a Faceclaw input token (- reads stdin)
./g2-ring-remote.sh start
./g2-ring-remote.sh status                  # grabbed nodes, Faceclaw ping, delivery counts
./g2-ring-remote.sh logs -f                 # every press: decoded event, gesture, Faceclaw reply
./g2-ring-remote.sh simulate touch.tap      # send a mapped gesture without the ring
./g2-ring-remote.sh simulate watch key.MUTE # the same, for a device profile
./g2-ring-remote.sh stop
```

Set `ANDROID_SERIAL` if more than one device is connected.

g2-ring-remote keeps running after adb disconnects or the cable is unplugged. It stops at
reboot. To bring it back, use the Shizuku launcher below, or run `./g2-ring-remote.sh start`
from a computer.

### Faceclaw input token

In Faceclaw on the glasses, go to **Settings → API Keys → Input tokens → Create
token**. Give it the **Provide input** permission, plus **Text to foreground window**
if a profile types on the watch. Then use **Copy token to phone clipboard**. Save it
with `./g2-ring-remote.sh token -` (paste, then Ctrl-D), or with the launcher app's
**Paste** and **Save token** buttons.

The token is stored at `/data/local/tmp/g2-ring-remote/token` with mode 600, so only the
shell user can read it. It is sent only to Faceclaw on localhost and is never logged.
g2-ring-remote reads the file on every press, so replacing it needs no restart.

Faceclaw accepts input only while it is ready for it, which in stock Faceclaw means
while the glasses are connected. Otherwise each press logs
`unavailable: Faceclaw is not ready for input`.

## After a reboot: the Shizuku launcher (no computer)

`launcher/` builds **G2 Ring Remote**, a one-screen app that runs g2-ring-remote's scripts as the
shell user through Shizuku. Shizuku's server calls the app's `ShizukuProvider` whenever
the server starts. That call wakes the app even when it is closed, and the app runs
`start.sh`. So after a reboot, starting Shizuku also brings g2-ring-remote back.

The app also has Start, Stop and Status buttons, and a switch to turn auto-start off.
Pressing Stop in the app keeps auto-start from restarting g2-ring-remote until you press
Start.

```sh
launcher/build.sh
adb install -r launcher/build/g2-ring-remote-launcher.apk
```

Open **G2 Ring Remote**, tap **Grant Shizuku permission**, then **Allow**.

The first build creates `launcher/launcher-debug.keystore`. Keep it: an APK signed with
a different key cannot update the installed app, so you would have to uninstall and
reinstall it.

The app also handles the Faceclaw input token, so no computer is needed when Faceclaw is
reinstalled. In Faceclaw, copy a new token to the clipboard. In G2 Ring Remote, tap
**Paste**, then **Save token**. The app writes the token over stdin to
`/data/local/tmp/g2-ring-remote/token` (mode 600), keeps no copy of its own, and shows
whether Faceclaw accepts it.

Shizuku itself has to be started after each reboot. Without root that happens through
**Wireless debugging**, which needs Wi-Fi. Shizuku 13.6 can do it at boot by itself once
it holds `WRITE_SECURE_SETTINGS`:

```sh
adb shell pm grant moe.shizuku.privileged.api android.permission.WRITE_SECURE_SETTINGS
```

The launcher uses the Shizuku 13.1.5 client library from Maven Central, vendored in
`launcher/libs/` with the original `.aar`/`.pom` files. It calls the library's private
`newProcess` by reflection, because a one-shot script does not need a bound
UserService.

## Optional: Deepgram speech-to-text for Faceclaw

Faceclaw has no Deepgram provider, but it has **Whisper server**, which speaks the
OpenAI transcription API to any URL (`GET {url}/models`, then
`POST {url}/audio/transcriptions` with a WAV file). g2-ring-remote can answer that API on
`127.0.0.1:8004` (`SttProxy`) and send each recording to Deepgram's pre-recorded
`/v1/listen` with `model=nova-3&smart_format=true` and Faceclaw's language (or
`stt.language`). It returns `{"text": …}`.

1. Save a Deepgram API key: in the G2 Ring Remote app, use **Deepgram voice → Paste → Save
   key**, or run `./g2-ring-remote.sh deepgram-key FILE`. It is stored at
   `/data/local/tmp/g2-ring-remote/deepgram-key` (mode 600) and read on every request.
2. In Faceclaw's Voice Input settings, set **Whisper server URL** to
   `http://127.0.0.1:8004/v1`. Leave **Whisper server model** empty or set it to
   `nova-3`. Then set **Transcription Provider** to **Whisper server**.

Transcripts are never logged; `status` shows only counts and timings. Faceclaw
re-transcribes a growing utterance for interim results, so Deepgram bills somewhat more
audio than you speak. To stop other apps on the phone from using the port, set
`stt.local-key` in `g2-ring-remote.conf` and put the same value in Faceclaw's **Whisper
server key**. Set `stt.enabled=false` to turn the proxy off.

## Configuration

`g2-ring-remote.conf` documents every setting. It lives on the phone at
`/data/local/tmp/g2-ring-remote/g2-ring-remote.conf`. Edit your copy, then:

```sh
./g2-ring-remote.sh push-config g2-ring-remote.conf
./g2-ring-remote.sh restart
```

The top-level settings are the default device profile (the K02). `device.<name>.SETTING`
adds another profile: `match.name`, `match.uniq`, `faceclaw.source`,
`touch.tap-threshold`, `key.repeat-window-ms`, `text.enabled` and `map.*` can all be
set per device. A profile's map starts empty, so it does not inherit the ring's map;
every other setting falls back to the top-level value. A node is grabbed by the first
profile that matches it. Profile names are one word.

A map value of `android:KEYCODE_NAME` re-injects that key into Android with
`input keyevent` instead of sending a Faceclaw gesture. For example,
`device.watch.map.key.VOLUMEDOWN=android:KEYCODE_VOLUME_DOWN` keeps the watch's camera
shutter working with the phone's camera app. The name must be `KEYCODE_` plus capital
letters, digits and underscores.

### Other remotes

Any Bluetooth remote that shows up as an input device can work:

1. Find its input node name with `adb shell getevent -i` and add a profile with
   `device.<name>.match.name`. To ignore other devices with the same name, also set
   `device.<name>.match.uniq` to the remote's Bluetooth address.
2. Start g2-ring-remote and watch `./g2-ring-remote.sh logs -f` while pressing each control.
3. Map what you see (`touch.*` for scripted touches, `key.*` for keys) to Faceclaw
   gestures or `android:KEYCODE_...` under `device.<name>.map.*`.

## How it works

- `DeviceWatcher` watches `/dev/input` with inotify, plus a 5 s rescan. It identifies
  each new node with `getevent -i`, because Java cannot issue `EVIOCGNAME`. The first
  profile that matches the node's name and uniq grabs it. It then opens
  the node and calls `EVIOCGRAB` through libcore's `ioctlInt`, which `app_process` can
  reach. Remotes' nodes disappear when they sleep and come back on reconnect; they are
  grabbed again within milliseconds.
- `TouchDecoder` turns each scripted touch into a direction or a tap. The kernel drops
  unchanged ABS values, so a vertical swipe often reports no X. Positions carry across
  touches for that reason. Each node has its own decoder and repeat state.
- `Router` maps events through the matched profile and drops repeats of held non-scroll
  keys. A map value of `android:KEYCODE_...` goes back to Android through
  `input keyevent` on its own thread, not to Faceclaw.
- `FaceclawSender` sends one JSON request per connection over a bounded in-order queue.
  It never retries: a timed-out request may already have reached the glasses.
- The abstract socket `g2-ring-remote` serves `status`, `stop` and
  `simulate [PROFILE] EVENT`, and prevents a second copy from starting.

## Tests

```sh
./test.sh    # JVM only: decoder, mapping, device profiles, passthrough, config, sender, STT proxy
./build.sh   # build/g2-ring-remote.dex
```

The shell user cannot write to input nodes, so physical presses cannot be faked over
adb. Checking the grab with real presses has to be done by hand.

## Known limitations

- No wakelock is held while delivering. If presses lag after the screen has been off for
  a long time, that is the likely cause.
- Shizuku must be running for the launcher to restart g2-ring-remote after a reboot.

## License

See [LICENSE](LICENSE). The vendored Shizuku client libraries in `launcher/libs/` are
under the Apache License 2.0; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
