# G2 Ring Remote

Use a cheap Bluetooth "TikTok remote" ring (the **K02**) as a remote control for your
Even Realities G2 glasses, through [Faceclaw](https://github.com/jimrandomh/faceclaw),
the unofficial Android app for the G2. **No root needed.**

**Demo:** [watch it in action on X](https://x.com/alve_om/status/2116800999570678031)

**The ring:** [K02 Bluetooth remote ring on Amazon India](https://amzn.in/d/0g2CrdIc)

g2-ring-remote is a small background process that takes exclusive control of the ring's
input devices and turns its presses into Faceclaw gestures. It runs as the adb shell
user, and can be restarted after a reboot without a computer through
[Shizuku](https://shizuku.rikka.app/).

```
K02 ──BLE HID──▶ Android Bluetooth ──▶ /dev/input/eventN
                                          │  EVIOCGRAB (exclusive)
                                          ▼
                                     g2-ring-remote ──▶ 127.0.0.1:8791 ──▶ Faceclaw ──▶ G2
                                                        (Faceclaw's input API)
```

While g2-ring-remote holds the grab, the kernel delivers the ring's events **only** to
g2-ring-remote. Android's input pipeline receives nothing from the ring:

- no taps or scrolls on whatever is on the phone screen;
- no volume changes, no music play/pause/skip;
- no screen wake and no lock-screen actions;
- it works with the phone locked and the screen off, whatever app is in front.

The phone's own buttons, touchscreen and other Bluetooth devices are not affected. Only
input nodes named `K02` or starting with `K02 ` are grabbed (configurable). Stopping
g2-ring-remote, or a reboot, releases the grab and the ring behaves normally again.

> Unofficial. Not affiliated with Even Realities, the Faceclaw authors, the ring's
> manufacturer, or Shizuku. Use at your own risk.

## Requirements

- An Android phone with **Android 11 or newer**, with
  [USB or wireless debugging](https://developer.android.com/tools/adb) enabled.
- **Faceclaw** installed and paired with your G2, with its **input API** enabled (see
  [Faceclaw input token](#faceclaw-input-token)).
- A **K02** Bluetooth ring ([the one used here](https://amzn.in/d/0g2CrdIc)) paired
  with the phone. Other HID remotes can work with a config change; see
  [Other rings](#other-rings).
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

## Install and run

From this directory, with the phone connected over adb:

```sh
./g2-ring-remote.sh install                 # build and copy to /data/local/tmp/g2-ring-remote
./g2-ring-remote.sh token FILE              # store a Faceclaw input token (- reads stdin)
./g2-ring-remote.sh start
./g2-ring-remote.sh status                  # grabbed nodes, Faceclaw ping, delivery counts
./g2-ring-remote.sh logs -f                 # every press: decoded event, gesture, Faceclaw reply
./g2-ring-remote.sh simulate touch.tap      # send a mapped gesture without the ring
./g2-ring-remote.sh stop
```

Set `ANDROID_SERIAL` if more than one device is connected.

g2-ring-remote keeps running after adb disconnects or the cable is unplugged. It stops at
reboot. To bring it back, use the Shizuku launcher below, or run `./g2-ring-remote.sh start`
from a computer.

### Faceclaw input token

In Faceclaw on the glasses, go to **Settings → API Keys → Input tokens → Create
token**. Give it the **Provide input** permission only, then use **Copy token to phone
clipboard**. Save it with `./g2-ring-remote.sh token -` (paste, then Ctrl-D), or with the
launcher app's **Paste** and **Save token** buttons.

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

### Other rings

Any Bluetooth remote that shows up as an input device can work:

1. Find its input node name with `adb shell getevent -i` and set `match.name`. To ignore
   other devices with the same name, also set `match.uniq` to the remote's Bluetooth
   address.
2. Start g2-ring-remote and watch `./g2-ring-remote.sh logs -f` while pressing each control.
3. Map what you see (`touch.*` for scripted touches, `key.*` for keys) to Faceclaw
   gestures in `g2-ring-remote.conf`.

## How it works

- `DeviceWatcher` watches `/dev/input` with inotify, plus a 5 s rescan. It identifies
  each new node with `getevent -i`, because Java cannot issue `EVIOCGNAME`. It then opens
  the node and calls `EVIOCGRAB` through libcore's `ioctlInt`, which `app_process` can
  reach. The ring's nodes disappear when it sleeps and come back on reconnect; they are
  grabbed again within milliseconds.
- `TouchDecoder` turns each scripted touch into a direction or a tap. The kernel drops
  unchanged ABS values, so a vertical swipe often reports no X. Positions carry across
  touches for that reason.
- `Router` maps events through `g2-ring-remote.conf` and drops repeats of held non-scroll
  keys.
- `FaceclawSender` sends one JSON request per connection over a bounded in-order queue.
  It never retries: a timed-out request may already have reached the glasses.
- The abstract socket `g2-ring-remote` serves `status`, `stop` and `simulate`,
  and prevents a second copy from starting.

## Tests

```sh
./test.sh    # JVM only: the decoder replays recorded K02 captures; mapping, config, sender, STT proxy
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
