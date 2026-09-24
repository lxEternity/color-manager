#!/system/bin/sh
# Color管理器 WebUI 开机自启服务（Magisk/KSU 模块）
MODDIR=${0%/*}

# 已在运行则退出（防重复）
pgrep -f "webui/server.py" >/dev/null 2>&1 && exit 0

# 后台守候: 每30秒检查 python, 找到即启动; 崩溃后自动重启
(
  while true; do
    PY=""
    for p in /data/data/com.termux/files/usr/bin/python3 \
             /data/data/com.termux/files/usr/bin/python \
             /system/bin/python3 /system/bin/python; do
      [ -x "$p" ] && PY="$p" && break
    done
    if [ -n "$PY" ]; then
      export LD_LIBRARY_PATH=/data/data/com.termux/files/usr/lib:$LD_LIBRARY_PATH
      export HOME=/data/data/com.termux/files/home
      cd "$MODDIR/webui" || exit 1
      nohup "$PY" server.py >/dev/null 2>&1 &
      while true; do
        sleep 60
        pgrep -f "webui/server.py" >/dev/null 2>&1 || break
      done
    fi
    sleep 30
  done
) &
