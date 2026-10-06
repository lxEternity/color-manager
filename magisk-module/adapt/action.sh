#!/system/bin/sh
MODDIR=${0%/*}

TARGET="$MODDIR/system/bin"
[ -d "$TARGET" ] || TARGET="$MODDIR"

am start -n bin.mt.plus/bin.mt.plus.Main \
  -d "file://$TARGET" >/dev/null 2>&1 || \
am start -n bin.mt.plus.canary/bin.mt.plus.Main \
  -d "file://$TARGET" >/dev/null 2>&1

echo "已打开 MT 管理器: $TARGET"
