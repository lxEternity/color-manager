#!/system/bin/sh
SELF=$(readlink -f "$0" 2>/dev/null)
[ -n "$SELF" ] || SELF="$0"
MODDIR=${SELF%/*}

if [ "$1" != "detached" ]; then
  nohup /system/bin/sh "$SELF" detached </dev/null >/dev/null 2>&1 &
  exit 0
fi

chmod 0755 "$MODDIR/powerd.sh" 2>/dev/null
chmod 0755 "$MODDIR"/webroot/*.sh 2>/dev/null

SH=/system/bin/sh

while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 2; done
sleep 10

is_alive() {
  local pid
  pid=$(cat "$MODDIR/powerd.lock/pid" 2>/dev/null)
  case "$pid" in
    ''|*[!0-9]*) ;;
    *) [ -r "/proc/$pid/cmdline" ] && grep -q "powerd.sh" "/proc/$pid/cmdline" 2>/dev/null && return 0 ;;
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

  if [ -f "$MODDIR/pwlogd.sh" ] && ! pgrep -f "pwlogd.sh" >/dev/null 2>&1; then
    nohup "$SH" "$MODDIR/pwlogd.sh" > /dev/null 2>&1 &
  fi

  sleep 30
done
