#!/system/bin/sh
MODDIR=${0%/*}

chmod 0755 "$MODDIR/powerd.sh" 2>/dev/null
chmod 0755 "$MODDIR/proc_monitor.sh" 2>/dev/null

# 固定用 mksh：busybox ash 的 $SECONDS 赋值后恒为 0，会让游戏退出迟滞失效
SH=/system/bin/sh

while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 2; done
sleep 10


while true; do
  alive=0
  for pid in $(pgrep -f "powerd.sh start" 2>/dev/null); do
    [ -d "/proc/$pid" ] && alive=1 && break
  done
  if [ "$alive" = "0" ] && [ ! -f "$MODDIR/pause" ]; then
    nohup $SH "$MODDIR/powerd.sh" start > /dev/null 2>&1 &
  fi

  # 事件源：proc_monitor（无自看护，仅此处 30s 探活；其自身带单实例保护）
  malive=0
  for pid in $(pgrep -f "proc_monitor.sh" 2>/dev/null); do
    [ -d "/proc/$pid" ] && malive=1 && break
  done
  if [ "$malive" = "0" ] && [ ! -f "$MODDIR/pause" ]; then
    nohup $SH "$MODDIR/proc_monitor.sh" "$MODDIR" "$MODDIR/run" "$MODDIR/powerd.log" > /dev/null 2>&1 &
  fi

  sleep 30
done

