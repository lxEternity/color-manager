#!/system/bin/sh
# 看门狗：周期性重新下发只读锁，封堵「blockdev --setrw 后抢写」竞争窗口。
# 模块被禁用/删除或配置关闭时自动退出；看门狗文件出现 $RUN/stop 也可手动停。
RUN=/data/adb/antifmt

if command -v blockdev >/dev/null 2>&1; then
    RO="blockdev --setro"
elif [ -x /data/adb/busybox ]; then
    RO="/data/adb/busybox blockdev --setro"
else
    exit 0
fi

while [ -d /data/adb/modules/antifmt ] && [ ! -f "$RUN/stop" ]; do
    [ -f "$RUN/config" ] && . "$RUN/config"
    [ "${ENABLED:-1}" = "1" ] || exit 0
    if [ -s "$RUN/protected.list" ]; then
        while read -r name dev; do
            $RO "$dev" >/dev/null 2>&1
        done < "$RUN/protected.list"
    fi
    sleep "${INTERVAL:-5}"
done
