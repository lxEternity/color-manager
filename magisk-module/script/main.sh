#!/system/bin/sh

BASEDIR="$(dirname $(readlink -f "$0"))"
. $BASEDIR/quanj.sh
. $BASEDIR/fangan.sh

action=$1
mosdz=$2
cpuxh=$3
pan1="$mokml/cur_powermode.txt"

# qhz 死锁自愈：上次切换被中断（进程被杀/报错）会把锁留在 0，
# 之后所有切换都会静默跳过（表现为"点了没反应/切换不生效"）。
# 锁龄超过 60 秒视为死锁，强制接管（正常切换在秒级完成）
if [ "$(cat $mosdz/qhz 2>/dev/null)" != "1" ]; then
	now=$(date +%s)
	lock=$(stat -c %Y $mosdz/qhz 2>/dev/null || echo $now)
	if [ $((now - lock)) -gt 60 ]; then
		echo "1" > $mosdz/qhz
	fi
fi

# core_ctl 按模式控制（超大核"一直活跃"的根因修复）：
# 非省电：enable=0 + min/max_cpus=全核 —— 强制全核在线（原 qingtd.sh cpus() 行为）
# 省电  ：enable=1 + min_cpus=1        —— 交还系统热插拔，空闲核心（含超大核）自动下线休闲
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
	#无堵塞

	#切换中
	echo "0" > $mosdz/qhz

	mokzdz="${mosdz%\/files}"

	# 方案选择统一走 fangan.sh（与 install.sh / WebUI 同一实现）：
	# scx→A / hmbird→B / sugov_next→A(调速器改sugov_next) / 都没有→C
	fangan_detect

	szwj="$mokzdz/config/$FANGAN.$cpuxh.sh"
	# 平台专属配置缺失时回退 all 配置（保留用户按平台自定义能力）
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

	# core_ctl 按模式（在配置应用后执行：省电交还热插拔，其他模式全核在线）
	if [ "$action" = "powersave" ]; then
		corectl_on
	else
		corectl_off
	fi

	# sugov_next 内核：把调速器统一设为 sugov_next（用户规则：检测到 sugov_next 启用 A 配置并把调速器改为 sugov_next）
	if [ "$FANGAN_GOV" = "sugov_next" ]; then
		for g in /sys/devices/system/cpu/cpufreq/policy*/scaling_governor; do
			chmod 777 "$g" 2>/dev/null
			echo sugov_next > "$g" 2>/dev/null
		done
	fi

	#切换结束
	echo "1" > $mosdz/qhz

fi
