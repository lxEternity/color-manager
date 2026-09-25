

BASEDIR="$(dirname $(readlink -f "$0"))"
. $BASEDIR/quanj.sh
shij="[$(date '+%T')]"
echo "$shij 动态切换模式启动成功" > $rizhidz
cpudiz=/sys/devices/system/cpu/cpufreq/policy0
echo "$shij 检测到刚开机自动fast模式。" >> $rizhidz





cpus() {
    for file in /sys/devices/system/cpu/cpu[0-9]/core_ctl/
    do
        for line in $file/enable
        do
            chmod 666 $line
            echo "0" > $line
            chmod 444 $line
        done

        sz=$(cat /sys/devices/system/cpu/cpu$(echo "$file" | grep -o '[0-9]')/topology/package_cpus_list)


        # 判断sz是否包含"-"，以决定是计算范围内的整数个数还是单个数字
        if [[ "$sz" == *-* ]]; then
            num_count=$(echo $sz | awk -F'-' '{start=$1;end=$2} END{print end-start+1}')
        else
            # sz是一个单个数字
            num_count=1
        fi

        for line in $file/not_preferred
        do
            chmod 666 $line
            if [ "$num_count" -eq 1 ]; then
                echo "0 " > $line
            elif [ "$num_count" -eq 2 ]; then
                echo "0 0 " > $line
            elif [ "$num_count" -eq 3 ]; then
                echo "0 0 0 " > $line
            elif [ "$num_count" -eq 4 ]; then
                echo "0 0 0 0 " > $line
            else
                echo "null"
            fi
            chmod 444 $line
        done

        for line in $file/max_cpus
        do
            chmod 666 $line
            echo $num_count > $line
            chmod 444 $line
        done

        for line in $file/min_cpus
        do
            chmod 666 $line
            echo $num_count > $line
            chmod 444 $line
        done
    done
}


while true; do
    #开机优化
    wj_zr "0" "/sys/module/migt/parameters/*cluster"
	wj_zr "0" "/sys/module/perfmgr/parameters/perfmgr_enable"
	wj_zr "1" "/sys/module/migt/parameters/glk_disable"
	wj_zr "0" "/sys/module/migt/parameters/boost_policy"
    cpus
    wj_zr "0" "/sys/module/cpufreq_bouncing/parameters/enable"

    ms="fast"
    kzlx=1
    . /data/powercfg.sh
    sleep 10
    if [[ $(cat $cpudiz/scaling_max_freq) != $(cat $cpudiz/scaling_min_freq) ]]; then
        shij="[$(date '+%T')]"
        echo "$shij 检测到最大'值和最小值不相等，退出fast" >> $rizhidz
        break
    fi
done



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