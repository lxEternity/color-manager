#!/system/bin/sh
MODDIR=${0%/*}

chmod 0755 "$MODDIR/powerd.sh" 2>/dev/null

# 固定用 mksh：busybox ash 的 $SECONDS 赋值后恒为 0，会让游戏退出迟滞失效
SH=/system/bin/sh

while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 2; done
sleep 10

is_alive() {
  # 先看 lock 里记录的 pid，避免每轮都 pgrep 全表扫描
  local pid
  pid=$(cat "$MODDIR/powerd.lock/pid" 2>/dev/null)
  case "$pid" in
    ''|*[!0-9]*) ;;
    *) [ -d "/proc/$pid" ] && return 0 ;;
  esac
  # lock 丢失/过期时的兜底：全表扫描
  for pid in $(pgrep -f "powerd.sh start" 2>/dev/null); do
    [ -d "/proc/$pid" ] && return 0
  done
  return 1
}

while true; do
  if ! is_alive && [ ! -f "$MODDIR/pause" ]; then
    nohup "$SH" "$MODDIR/powerd.sh" start > /dev/null 2>&1 &
  fi

  # ColorFC 功耗记录守护（幂等，两形态常驻）
  [ -f "$MODDIR/pwlogd.sh" ] && nohup $SH "$MODDIR/pwlogd.sh" > /dev/null 2>&1 &

  sleep 30
done
