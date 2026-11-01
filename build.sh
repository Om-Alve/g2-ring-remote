#!/usr/bin/env bash
# Build g2-ring-remote into a single dex for app_process (no APK, no Gradle).
#
# Needs an Android SDK (platform 35 and build-tools) and a JDK; see env.sh for
# how they are found and the variables that override them.
#
# Output: build/g2-ring-remote.dex
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
source "$here/env.sh"

[[ -f "$android_jar" ]] || { echo "error: android.jar not found: $android_jar" >&2; exit 1; }
[[ -x "$build_tools/d8" ]] || { echo "error: d8 not found in $build_tools" >&2; exit 1; }
[[ -x "$java_home/bin/javac" ]] || { echo "error: javac not found under $java_home" >&2; exit 1; }
export JAVA_HOME=$java_home

build=$here/build
rm -rf "$build/classes" "$build/dex" "$build/g2-ring-remote.dex"
mkdir -p "$build/classes" "$build/dex"

"$java_home/bin/javac" -source 8 -target 8 -Xlint:-options -cp "$android_jar" -d "$build/classes" \
  "$here"/src/io/github/omalve/g2ringremote/*.java
"$build_tools/d8" --release --min-api 30 --lib "$android_jar" --output "$build/dex" \
  $(find "$build/classes" -name '*.class')
mv "$build/dex/classes.dex" "$build/g2-ring-remote.dex"
echo "built $build/g2-ring-remote.dex"
