#!/system/bin/sh
MODDIR=${0%/*}

chmod 0755 "$MODDIR/powerd.sh" 2>/dev/null

SH=/system/bin/sh

while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 2; done
sleep 10

is_alive() {
  local pid
  pid=$(cat "$MODDIR/powerd.lock/pid" 2>/dev/null)
  case "$pid" in
    ''|*[!0-9]*) ;;
    *) [ -d "/proc/$pid" ] && return 0 ;;
  esac
  for pid in $(pgrep -f "powerd.sh start" 2>/dev/null); do
    [ -d "/proc/$pid" ] && return 0
  done
  return 1
}

while true; do
  if ! is_alive && [ ! -f "$MODDIR/pause" ]; then
    nohup "$SH" "$MODDIR/powerd.sh" start > /dev/null 2>&1 &
  fi

  [ -f "$MODDIR/pwlogd.sh" ] && nohup $SH "$MODDIR/pwlogd.sh" > /dev/null 2>&1 &

  sleep 30
done
