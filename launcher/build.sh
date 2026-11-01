#!/usr/bin/env bash
# Build the G2 Ring Remote launcher APK without Gradle.
#
# Needs an Android SDK and a JDK, found as described in ../env.sh.
#   KEYSTORE   signing keystore (default: launcher/launcher-debug.keystore, created on first build;
#              keep it, because an APK signed with a new key cannot update the installed app)
#
# Output: launcher/build/g2-ring-remote-launcher.apk
# Shizuku client 13.1.5 jars in libs/ come from Maven Central (dev.rikka.shizuku:{api,provider,aidl,shared});
# the .aar and .pom files next to them are the originals, SHA-1 checked when downloaded.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
source "$here/../env.sh"
keystore=${KEYSTORE:-"$here/launcher-debug.keystore"}
ks_pass=g2-ring-remote-launcher

[[ -x "$java_home/bin/javac" ]] || { echo "error: no JDK found; set JAVA_HOME" >&2; exit 1; }
for tool in aapt2 d8 zipalign apksigner; do
  [[ -x "$build_tools/$tool" ]] || { echo "error: $build_tools/$tool not found" >&2; exit 1; }
done
[[ -f "$android_jar" ]] || { echo "error: android.jar not found: $android_jar" >&2; exit 1; }
export JAVA_HOME=$java_home
export PATH="$JAVA_HOME/bin:$PATH"

libs=$(ls "$here"/libs/shizuku-*.jar | tr '\n' ':')
build=$here/build
rm -rf "$build"
mkdir -p "$build/classes" "$build/dex"

javac -source 8 -target 8 -Xlint:-options -cp "$android_jar:$libs" -d "$build/classes" \
  "$here"/src/io/github/omalve/g2ringremote/launcher/*.java
"$build_tools/d8" --release --min-api 30 --lib "$android_jar" --output "$build/dex" \
  $(find "$build/classes" -name '*.class') "$here"/libs/shizuku-*.jar

"$build_tools/aapt2" link -o "$build/unsigned.apk" -I "$android_jar" \
  --manifest "$here/AndroidManifest.xml" \
  --min-sdk-version 30 --target-sdk-version 35 --version-code 4 --version-name 0.3.0
(cd "$build/dex" && zip -q "$build/unsigned.apk" classes.dex)
"$build_tools/zipalign" -p -f 4 "$build/unsigned.apk" "$build/aligned.apk"

if [[ ! -f "$keystore" ]]; then
  keytool -genkeypair -keystore "$keystore" -alias launcher -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass "$ks_pass" -keypass "$ks_pass" -dname 'CN=G2 Ring Remote Launcher' >/dev/null
  echo "created $keystore (keep it: a new key means uninstall/reinstall)"
fi
"$build_tools/apksigner" sign --ks "$keystore" --ks-key-alias launcher \
  --ks-pass "pass:$ks_pass" --key-pass "pass:$ks_pass" \
  --out "$build/g2-ring-remote-launcher.apk" "$build/aligned.apk"
"$build_tools/apksigner" verify "$build/g2-ring-remote-launcher.apk"
echo "built $build/g2-ring-remote-launcher.apk"
