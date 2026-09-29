#!/system/bin/sh
# 卸载：停看门狗、清运行目录（只读锁随重启自动失效，无需额外处理）
pkill -f "antifmt/daemon.sh" 2>/dev/null
rm -rf /data/adb/antifmt
exit 0
