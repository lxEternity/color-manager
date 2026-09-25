#!/system/bin/sh
# ============================================================
# ColorFC 功耗记录守护：低频采样电池 功率/温度/电量/充放状态 → CSV
# 数据目录 /data/adb/colorFC_store/pwlog/（形态切换/重刷模块均保留）
# 供 WebUI「功耗记录」页绘制历史曲线；幂等启动，已在运行则退出
# ============================================================
STORE=/data/adb/colorFC_store/pwlog
PIDF=/data/adb/colorFC_store/pwlogd.pid
INTERVAL=30      # 采样间隔（秒），一天约 2880 条
KEEP_DAYS=7      # 历史保留天数

# 防重入
if [ -f "$PIDF" ]; then
    oldpid=$(cat "$PIDF" 2>/dev/null)
    if [ -n "$oldpid" ] && kill -0 "$oldpid" 2>/dev/null; then
        exit 0
    fi
fi
mkdir -p "$STORE"
echo $$ > "$PIDF"

B=/sys/class/power_supply/battery

# 清理过期文件（启动时执行一次；toybox date 不支持 -d N days 时静默跳过）
cleanup() {
    cutoff=$(date -d "-${KEEP_DAYS} days" +%Y%m%d 2>/dev/null) || return 0
    [ -n "$cutoff" ] || return 0
    for f in "$STORE"/pwlog-*.csv; do
        [ -e "$f" ] || continue
        fd=$(basename "$f" .csv); fd=${fd#pwlog-}
        case "$fd" in *[!0-9]*|"") continue;; esac
        [ "$fd" -lt "$cutoff" ] && rm -f "$f"
    done
}
cleanup

while true; do
    cur=$(cat $B/current_now 2>/dev/null)
    volt=$(cat $B/voltage_now 2>/dev/null)
    [ -n "$cur" ] && [ -n "$volt" ] && [ "$cur" != 0 ] && [ "$volt" != 0 ] && {
        now=$(date +%s)
        temp=$(cat $B/temp 2>/dev/null)
        cap=$(cat $B/capacity 2>/dev/null)
        st=$(cat $B/status 2>/dev/null)
        # battery/temp 为 0.1℃ 内核单位，写入前统一归一为 ℃（保留 1 位小数）
        [ -n "$temp" ] && temp=$(awk "BEGIN{printf \"%.1f\", $temp/10}")
        # µA×µV/1e12 → W；状态映射：1=充电/满/接电，0=放电
        w=$(awk "BEGIN{c=$cur+0; v=$volt+0; printf \"%.2f\", (c<0?-c:c)*v/1000000000000}")
        case "$st" in
            Charging*|Full|Not\ charg*) c=1;;
            *) c=0;;
        esac
        echo "$now,$w,${temp:-NA},${cap:-NA},$c" >> "$STORE/pwlog-$(date +%Y%m%d).csv"
        # 每 30 分钟滚动清理一次
        [ $((now % 1800)) -lt $INTERVAL ] && cleanup
    }
    sleep $INTERVAL
done
