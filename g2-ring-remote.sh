#!/usr/bin/env bash
# Control g2-ring-remote on the phone over adb. Set ANDROID_SERIAL to pick a device.
#
#   ./g2-ring-remote.sh install            build and copy g2-ring-remote to the phone
#   ./g2-ring-remote.sh token FILE         copy a Faceclaw input token (or - for stdin)
#   ./g2-ring-remote.sh deepgram-key FILE  copy a Deepgram API key for speech-to-text (or - for stdin)
#   ./g2-ring-remote.sh start|stop|restart|status
#   ./g2-ring-remote.sh logs [-f]
#   ./g2-ring-remote.sh simulate [PROFILE] EVENT   e.g. touch.tap, watch key.MUTE (tests delivery)
#   ./g2-ring-remote.sh push-config FILE   replace the phone's config (then restart)
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
dir=/data/local/tmp/g2-ring-remote
cls=io.github.omalve.g2ringremote.G2RingRemote

# </dev/null: otherwise adb shell swallows stdin meant for a later command (the token).
phone() { adb shell "$@" < /dev/null; }
control() { phone "CLASSPATH=$dir/g2-ring-remote.dex app_process /system/bin $cls $*"; }

# Copy a secret to $dir/NAME through stdin (never on a command line), readable by shell only.
# exec-in, not shell: `adb shell` can drop piped stdin. exec-in can return before the phone
# has finished writing, so wait for the file instead of racing it.
secret_to_phone() {
  local name=$1 src=$2
  phone "rm -f $dir/$name"
  if [[ "$src" == - ]]; then
    adb exec-in "sh -c 'umask 077 && cat > $dir/$name'"
  else
    adb exec-in "sh -c 'umask 077 && cat > $dir/$name'" < "$src"
  fi
  for _ in $(seq 1 25); do
    phone "[ -s $dir/$name ]" && { phone "chmod 600 $dir/$name"; return 0; }
    sleep 0.2
  done
  echo "error: $dir/$name on the phone is missing or empty" >&2
  exit 1
}

cmd=${1:-}
shift || true
case "$cmd" in
  install)
    "$here/build.sh"
    phone "mkdir -p $dir && chmod 700 $dir"
    adb push "$here/build/g2-ring-remote.dex" "$dir/g2-ring-remote.dex" >/dev/null
    adb push "$here/phone/start.sh" "$dir/start.sh" >/dev/null
    adb push "$here/phone/stop.sh" "$dir/stop.sh" >/dev/null
    phone "chmod 755 $dir/start.sh $dir/stop.sh"
    if phone "[ -f $dir/g2-ring-remote.conf ]"; then
      echo "kept the phone's existing config ($dir/g2-ring-remote.conf)"
    else
      adb push "$here/g2-ring-remote.conf" "$dir/g2-ring-remote.conf" >/dev/null
      echo "installed the default config"
    fi
    phone "[ -s $dir/token ]" || echo "next: ./g2-ring-remote.sh token FILE   (a Faceclaw token with only Provide input)"
    echo "installed to $dir; restart g2-ring-remote if it is running"
    ;;
  token)
    src=${1:?usage: g2-ring-remote.sh token FILE|-}
    phone "mkdir -p $dir && chmod 700 $dir"
    # Through stdin so the token never appears on a command line. exec-in, not
    # shell: `adb shell` can drop piped stdin.
    secret_to_phone token "$src"
    echo "token saved to $dir/token (shell user only)"
    ;;
  deepgram-key)
    src=${1:?usage: g2-ring-remote.sh deepgram-key FILE|-}
    phone "mkdir -p $dir && chmod 700 $dir"
    secret_to_phone deepgram-key "$src"
    echo "Deepgram key saved to $dir/deepgram-key (shell user only)"
    ;;
  push-config)
    src=${1:?usage: g2-ring-remote.sh push-config FILE}
    adb push "$src" "$dir/g2-ring-remote.conf" >/dev/null
    echo "config pushed; run ./g2-ring-remote.sh restart"
    ;;
  start) phone "sh $dir/start.sh" ;;
  stop) phone "sh $dir/stop.sh" ;;
  restart) phone "sh $dir/stop.sh; sleep 1; sh $dir/start.sh" ;;
  status) control status ;;
  simulate) control simulate "$@" ;;
  logs)
    if [[ "${1:-}" == -f ]]; then adb shell "tail -n 50 -f $dir/g2-ring-remote.log"; else phone "cat $dir/g2-ring-remote.log"; fi
    ;;
  *)
    sed -n '2,11p' "$0" | sed 's/^# \{0,1\}//'
    exit 2
    ;;
esac
