#!/system/bin/sh
# Magisk 模块页「执行」按钮：立即重新扫描并加锁
# （修改 /data/adb/antifmt/config 后点此按钮立即生效，无需重启）
MODDIR=${0%/*}
sh "$MODDIR/apply.sh" action
