#!/system/bin/sh
MODDIR=${0%/*}

# 用 MT 管理器打开模块目录（精准定位到 system/bin 方便执行 chkfreq.sh）
TARGET="$MODDIR/system/bin"
[ -d "$TARGET" ] || TARGET="$MODDIR"

am start -n bin.mt.plus/bin.mt.plus.Main \
  -d "file://$TARGET" >/dev/null 2>&1 || \
am start -n bin.mt.plus.canary/bin.mt.plus.Main \
  -d "file://$TARGET" >/dev/null 2>&1

echo "已打开 MT 管理器: $TARGET"

