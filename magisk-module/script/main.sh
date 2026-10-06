#!/system/bin/sh
BASEDIR="$(dirname $(readlink -f "$0"))"
. $BASEDIR/quanj.sh
. $BASEDIR/fangan.sh

action=$1
mosdz=$2
cpuxh=$3
pan1="$mokml/cur_powermode.txt"

if [ "$(cat $mosdz/qhz 2>/dev/null)" != "1" ]; then
	now=$(date +%s)
	lock=$(stat -c %Y $mosdz/qhz 2>/dev/null || echo $now)
	if [ $((now - lock)) -gt 60 ]; then
		echo "1" > $mosdz/qhz
	fi
fi

corectl_off(){
    for d in /sys/devices/system/cpu/cpu*/core_ctl; do
        [ -d "$d" ] || continue
        n=$(basename "${d%/core_ctl}"); n=${n#cpu}
        sz=$(cat /sys/devices/system/cpu/cpu$n/topology/package_cpus_list 2>/dev/null)
        case "$sz" in
            *-*) num=$(echo "$sz" | awk -F'-' '{print $2-$1+1}');;
            "")  num=1;;
            *)   num=1;;
        esac
        chmod 666 "$d/enable" 2>/dev/null;      echo 0 > "$d/enable" 2>/dev/null;      chmod 444 "$d/enable" 2>/dev/null
        chmod 666 "$d/min_cpus" 2>/dev/null;    echo "$num" > "$d/min_cpus" 2>/dev/null;    chmod 444 "$d/min_cpus" 2>/dev/null
        chmod 666 "$d/max_cpus" 2>/dev/null;    echo "$num" > "$d/max_cpus" 2>/dev/null;    chmod 444 "$d/max_cpus" 2>/dev/null
        chmod 666 "$d/not_preferred" 2>/dev/null
        i=0; np=""
        while [ $i -lt "$num" ]; do np="$np 0"; i=$((i+1)); done
        echo "$np" > "$d/not_preferred" 2>/dev/null
        chmod 444 "$d/not_preferred" 2>/dev/null
    done
}
corectl_on(){
    for d in /sys/devices/system/cpu/cpu*/core_ctl; do
        [ -d "$d" ] || continue
        chmod 666 "$d/enable" 2>/dev/null;      echo 1 > "$d/enable" 2>/dev/null;      chmod 444 "$d/enable" 2>/dev/null
        chmod 666 "$d/min_cpus" 2>/dev/null;    echo 1 > "$d/min_cpus" 2>/dev/null;    chmod 444 "$d/min_cpus" 2>/dev/null
        chmod 666 "$d/max_cpus" 2>/dev/null
        sz=$(cat /sys/devices/system/cpu/$(basename "${d%/core_ctl}")/topology/package_cpus_list 2>/dev/null)
        case "$sz" in
            *-*) echo "$sz" | awk -F'-' '{print $2-$1+1}' > "$d/max_cpus";;
            *)   echo 1 > "$d/max_cpus";;
        esac
        chmod 444 "$d/max_cpus" 2>/dev/null
        chmod 666 "$d/not_preferred" 2>/dev/null
        echo "1" > "$d/not_preferred" 2>/dev/null
        chmod 444 "$d/not_preferred" 2>/dev/null
    done
}

if test $(cat $mosdz/qhz) -eq 1 ; then

	echo "0" > $mosdz/qhz

	mokzdz="${mosdz%\/files}"

	fangan_detect

	szwj="$mokzdz/config/$FANGAN.$cpuxh.sh"
	if [ ! -f "$szwj" ]; then
		szwj="$mokzdz/config/$FANGAN.all.sh"
	fi

	if [ -f "$szwj" ]; then
		case "$FANGAN" in
			a) . $mokzdz/script/a.main.sh 2>/dev/null;;
			b) . $mokzdz/script/b.main.sh 2>/dev/null;;
			c) . $mokzdz/script/c.main.sh 2>/dev/null;;
		esac
		. "$szwj"
	else
		echo "方案 $FANGAN 的配置文件不存在（$cpuxh / all 均缺失）"
	fi

	if [ "$action" = "powersave" ]; then
		corectl_on
	else
		corectl_off
	fi

	if [ "$FANGAN_GOV" = "sugov_next" ]; then
		for g in /sys/devices/system/cpu/cpufreq/policy*/scaling_governor; do
			chmod 777 "$g" 2>/dev/null
			echo sugov_next > "$g" 2>/dev/null
		done
	fi

	echo "1" > $mosdz/qhz

fi
