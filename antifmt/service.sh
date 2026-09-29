#!/system/bin/sh
# late_start 阶段执行：系统完全启动、所有挂载完成后再加锁，
# 不干扰开机流程（只读锁不影响任何读取，加锁失败也只写日志）
MODDIR=${0%/*}
sh "$MODDIR/apply.sh" boot >/dev/null 2>&1
