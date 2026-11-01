#!/system/bin/sh
# Stop g2-ring-remote. Exiting releases the grab, so the ring works with Android again.
dir=/data/local/tmp/g2-ring-remote
CLASSPATH=$dir/g2-ring-remote.dex app_process /system/bin io.github.omalve.g2ringremote.G2RingRemote stop && exit 0
pid=$(pidof g2-ring-remote) && kill $pid && echo "killed g2-ring-remote ($pid)"
exit 0
