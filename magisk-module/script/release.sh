BASEDIR="$(dirname $(readlink -f "$0"))"
. $BASEDIR/quanj.sh
shij="[$(date '+%T')]"

for p in /sys/devices/system/cpu/cpufreq/policy*; do
    [ -d "$p" ] || continue
    imf=$(cat "$p/cpuinfo_max_freq" 2>/dev/null)
    imn=$(cat "$p/cpuinfo_min_freq" 2>/dev/null)
    [ -z "$imf" ] && continue
    chmod 666 "$p/scaling_max_freq" "$p/scaling_min_freq" 2>/dev/null
    echo "$imf" > "$p/scaling_max_freq" 2>/dev/null
    [ -n "$imn" ] && echo "$imn" > "$p/scaling_min_freq" 2>/dev/null
done

llccpath=/sys/devices/system/cpu/bus_dcvs/LLCC
if [ -d "$llccpath" ]; then
    hw=$(cat "$llccpath"/*/available_frequencies 2>/dev/null | tr ' ' '\n' | sort -n | tail -1)
    if [ -n "$hw" ]; then
        for f in "$llccpath"/*/max_freq; do
            [ -e "$f" ] || continue
            chmod 644 "$f" 2>/dev/null
            echo "$hw" > "$f" 2>/dev/null
        done
    fi
fi

for f in max_freq min_freq boost_freq; do
    chattr -i "/sys/class/devfreq/soc:qcom,memlat-drv/$f" 2>/dev/null
done

for g in display ssfg touch multimedia rt top-app; do
    [ -e "/dev/cpuctl/$g/cpu.uclamp.min" ] && echo 0 > "/dev/cpuctl/$g/cpu.uclamp.min" 2>/dev/null
done

for d in /sys/devices/system/cpu/cpu*/core_ctl; do
    [ -d "$d" ] || continue
    chmod 666 "$d/enable" 2>/dev/null
    echo 1 > "$d/enable" 2>/dev/null
    chmod 666 "$d/max_cpus" 2>/dev/null
    sz=$(cat /sys/devices/system/cpu/$(basename "${d%/core_ctl}")/topology/package_cpus_list 2>/dev/null)
    case "$sz" in
        *-*) echo "$sz" | awk -F'-' '{print $2-$1+1}' > "$d/max_cpus" 2>/dev/null;;
        *) echo 1 > "$d/max_cpus" 2>/dev/null;;
    esac
done

echo "$shij 已解除Color调度全部锁定并恢复系统默认，游戏期间不再介入" >> $rizhidz
