# Tool lookup shared by build.sh, launcher/build.sh and test.sh (sourced, not run).
#
# Override any of these in the environment:
#   ANDROID_HOME  Android SDK (default: $ANDROID_SDK_ROOT, ~/Library/Android/sdk or ~/Android/Sdk)
#   ANDROID_JAR   platform android.jar (default: $ANDROID_HOME/platforms/android-35/android.jar)
#   BUILD_TOOLS   build-tools directory with d8, aapt2, zipalign and apksigner
#                 (default: the newest one in $ANDROID_HOME/build-tools)
#   JAVA_HOME     a JDK, 17 or 21 (default: /usr/libexec/java_home on macOS, else the JDK that owns `javac`)

sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
if [[ -z "$sdk" ]]; then
  for candidate in "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
    if [[ -d "$candidate" ]]; then sdk=$candidate; break; fi
  done
fi

android_jar=${ANDROID_JAR:-"$sdk/platforms/android-35/android.jar"}

build_tools=${BUILD_TOOLS:-}
if [[ -z "$build_tools" && -d "$sdk/build-tools" ]]; then
  build_tools=$(ls -d "$sdk"/build-tools/*/ 2>/dev/null | sort | tail -n 1)
  build_tools=${build_tools%/}
fi

java_home=${JAVA_HOME:-}
if [[ -z "$java_home" && -x /usr/libexec/java_home ]]; then
  java_home=$(/usr/libexec/java_home 2>/dev/null || true)
fi
if [[ -z "$java_home" ]]; then
  javac_path=$(command -v javac || true)
  if [[ -n "$javac_path" ]]; then
    java_home=$(cd "$(dirname "$(readlink -f "$javac_path")")/.." && pwd)
  fi
fi
