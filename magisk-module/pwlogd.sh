#!/system/bin/sh
# ============================================================
# ColorFC 功耗记录守护：低频采样电池 功率/温度/电量/充放状态 → CSV
# 数据目录 /data/adb/colorFC_store/pwlog/（形态切换/重刷模块均保留）
# 供 WebUI「功耗记录」页绘制历史曲线
#
# v1.3.9.6 修复：
#  1. 电流/电压单位自适应（µA/mA/A、µV/mV/V），修复部分机型
#     节点报 mA/mV 时功率被缩小 1000 倍（充电显示 0.06W）
#  2. 温度单位自适应（0.01℃/0.1℃/℃ → ℃），修复记录页 363℃ 异常
#  3. 支持电芯模式 /data/adb/colorFC_store/cellmode（WebUI 手动切换），
#     双芯且节点只报单芯数据时功率×2（对齐 APP applyCellMode）
#  4. 启动时清理旧版本遗留的 pwlogd 进程（旧进程按旧公式持续写坏数据）
# ============================================================
STORE=/data/adb/colorFC_store/pwlog
PIDF=/data/adb/colorFC_store/pwlogd.pid
CELLF=/data/adb/colorFC_store/cellmode   # 电芯模式：0=自动 1=单芯 2=双芯（持久化）
INTERVAL=30      # 采样间隔（秒），一天约 2880 条
KEEP_DAYS=7      # 历史保留天数

# 清理旧版/其他路径遗留的 pwlogd 进程（模块升级后旧进程不会自行退出）
if command -v pgrep >/dev/null 2>&1; then
    for p in $(pgrep -f pwlogd.sh 2>/dev/null); do
        [ "$p" = "$$" ] || kill "$p" 2>/dev/null
    done
else
    # 兜底：PID 文件方式（校验 cmdline 防止误杀复用 PID 的无关进程）
    if [ -f "$PIDF" ]; then
        op=$(cat "$PIDF" 2>/dev/null)
        if [ -n "$op" ] && [ -r "/proc/$op/cmdline" ] \
            && grep -q pwlogd "/proc/$op/cmdline" 2>/dev/null; then
            kill "$op" 2>/dev/null
        fi
    fi
fi
sleep 1
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
        # 功率：电流/电压单位自适应（µA/mA/A、µV/mV/V，对齐 APP 端 PowerMonitor 校准）
        # 电芯模式=双芯 且 节点只报单芯电压（<5.5V）时功率×2
        w=$(awk -v c="$cur" -v v="$volt" -v cm="$(cat "$CELLF" 2>/dev/null)" 'BEGIN{
            a = c<0 ? -c : c; A = a>100000 ? c/1e6 : (a>1 ? c/1000 : c);
            b = v<0 ? -v : v; V = b>1000000 ? v/1e6 : (b>2500 ? v/1000 : v);
            w = A*V; if (w<0) w = -w;
            if (cm==2 && V>0 && V<5.5) w = w*2;
            printf "%.2f", w;
        }')
        # 温度：单位自适应（0.01℃/0.1℃/℃ → ℃），超物理范围写 NA
        tv=NA
        [ -n "$temp" ] && tv=$(awk -v t="$temp" 'BEGIN{
            if (t>600) t/=100; else if (t>60) t/=10;
            if (t>=0 && t<=90) printf "%.1f", t; else printf "NA";
        }')
        # 状态映射：1=充电/满/接电，0=放电
        case "$st" in
            Charging*|Full|Not\ charg*) c=1;;
            *) c=0;;
        esac
        echo "$now,$w,${tv},${cap:-NA},$c" >> "$STORE/pwlog-$(date +%Y%m%d).csv"
        # 每 30 分钟滚动清理一次
        [ $((now % 1800)) -lt $INTERVAL ] && cleanup
    }
    sleep $INTERVAL
done
