#!/system/bin/sh
# Start g2-ring-remote as the shell user (adb shell, Shizuku's rish, or any other
# shell-level launcher; no root). Running it again leaves a running copy alone.
dir=/data/local/tmp/g2-ring-remote
cls=io.github.omalve.g2ringremote.G2RingRemote
export CLASSPATH=$dir/g2-ring-remote.dex

if app_process /system/bin $cls status >/dev/null 2>&1; then
  echo "g2-ring-remote is already running"
  app_process /system/bin $cls status
  exit 0
fi

[ -f $dir/g2-ring-remote.log ] && mv -f $dir/g2-ring-remote.log $dir/g2-ring-remote.log.1
# setsid detaches it from this shell session, so it keeps running after adb
# disconnects or the USB cable is unplugged. It stops at reboot.
setsid app_process /system/bin --nice-name=g2-ring-remote $cls run $dir/g2-ring-remote.conf \
  > $dir/g2-ring-remote.log 2>&1 < /dev/null &

for i in 1 2 3 4 5 6 7 8 9 10; do
  sleep 0.5
  app_process /system/bin $cls status >/dev/null 2>&1 && break
done
app_process /system/bin $cls status || { echo "g2-ring-remote did not start; log:"; cat $dir/g2-ring-remote.log; exit 1; }
