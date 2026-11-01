#!/usr/bin/env bash
# JVM-only tests for g2-ring-remote (decoder, mapping, config, Faceclaw sender).
# No device or Android SDK needed; the Android device layer is tested on the phone.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
source "$here/env.sh"
[[ -x "$java_home/bin/javac" ]] || { echo "error: no JDK found; set JAVA_HOME" >&2; exit 1; }
out=$here/build/test-classes
src=$here/src/io/github/omalve/g2ringremote

rm -rf "$out"
mkdir -p "$out"
"$java_home/bin/javac" -source 8 -target 8 -Xlint:-options -d "$out" \
  "$src/Log.java" "$src/KeyNames.java" "$src/TouchDecoder.java" "$src/Config.java" \
  "$src/Router.java" "$src/FaceclawSender.java" "$src/SttProxy.java" \
  "$here/test/io/github/omalve/g2ringremote/G2RingRemoteTest.java" \
  "$here/test/io/github/omalve/g2ringremote/SttProxyTest.java"
"$java_home/bin/java" -cp "$out" io.github.omalve.g2ringremote.G2RingRemoteTest "$here/test/fixtures"
"$java_home/bin/java" -cp "$out" io.github.omalve.g2ringremote.SttProxyTest
