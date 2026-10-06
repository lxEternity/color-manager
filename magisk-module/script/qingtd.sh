BASEDIR="$(dirname $(readlink -f "$0"))"
. $BASEDIR/quanj.sh
shij="[$(date '%T')]"
echo "$shij 动态切换模式启动成功" > $rizhidz
cpudiz=/sys/devices/system/cpu/cpufreq/policy0

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
