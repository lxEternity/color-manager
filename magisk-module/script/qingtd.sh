

BASEDIR="$(dirname $(readlink -f "$0"))"
. $BASEDIR/quanj.sh
shij="[$(date '%T')]"
echo "$shij 动态切换模式启动成功" > $rizhidz
cpudiz=/sys/devices/system/cpu/cpufreq/policy0

# 恢复上次模式（service.sh 已把重启前的模式备份到 files/lastmode）。
# 旧逻辑为"开机 while 循环每 10 秒强制 fast 直到 max!=min"，存在三个问题：
#   1. 重启后模式总是被强制成极速（不管用户之前选的是什么）
#   2. 循环期间用户手动切换，10 秒内又被拉回 fast（表现为"无法切换"）
#   3. 循环内 cpus() 每 10 秒强制全核在线 + core_ctl 禁用（省电模式超大核永远活跃）
# 现统一走 powercfg.sh 接口（kzlx=1 内部调用：仅应用，不动 moren、不停动态）
lm=$(cat $BASEDIR/../files/lastmode 2>/dev/null)
case "$lm" in
    powersave|balance|performance|fast)
        ms="$lm"
        kzlx=1
        . /data/powercfg.sh
        echo "$shij 检测到刚开机，恢复上次模式: $lm" >> $rizhidz
        ;;
    *)
        echo "$shij 无上次模式记录，跳过恢复（等待前台监视按 moren 切换）" >> $rizhidz
        ;;
esac

# 开机全局优化参数（与模式无关，执行一次即可）
wj_zr "0" "/sys/module/migt/parameters/*cluster"
wj_zr "0" "/sys/module/perfmgr/parameters/perfmgr_enable"
wj_zr "1" "/sys/module/migt/parameters/glk_disable"
wj_zr "0" "/sys/module/migt/parameters/boost_policy"
wj_zr "0" "/sys/module/cpufreq_bouncing/parameters/enable"


directories=(
    "/sys/devices/system/cpu/cpufreq/policy0/conservative/"
    "/sys/devices/system/cpu/cpufreq/policy0/scx/"
)

for dir in "${directories[@]}"; do
    if [[ -d "$dir" ]]; then
        shij="[$(date +"%H:%M:%S")]"
        last_dirname=$(basename "$dir")
        echo "$shij cpu调速器: $last_dirname" >> $rizhidz

        for file in "$dir"*; do
            if [[ -e "$file" ]]; then
                echo "$shij 调速器支持: ${file##*/}" >> $rizhidz
            fi
        done

    fi
done

found=true
for file in /sys/devices/system/cpu/cpu[0-9]
do
    dz="$file/sched_load_boost"

    if [ -e "$dz" ]; then
        found=true
        break
    fi
done

shij="[$(date +"%H:%M:%S")]"

if [ "$found" = true ]; then
    echo "$shij 非调速器下支持：boost" >> "$rizhidz"
fi

echo "$shij 开启动态切换。" >> $rizhidz

cpuset "qingtd.sh" "background"

kill $(pgrep -f "qingtdjc")

kill $(pgrep -f "qtbh")

sleep 1
nohup $BASEDIR/qingtdjc1.sh >/dev/null 2>&1 &
#nohup $BASEDIR/qingtdjc2.sh >/dev/null 2>&1 &
