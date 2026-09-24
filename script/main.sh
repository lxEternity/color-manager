
BASEDIR="$(dirname $(readlink -f "$0"))"
. $BASEDIR/quanj.sh

action=$1
mosdz=$2
cpuxh=$3
pan1="$mokml/cur_powermode.txt"

if test $(cat $mosdz/qhz) -eq 1 ; then
	#无堵塞

	#切换中
	echo "0" > $mosdz/qhz



	mokzdz="${mosdz%\/files}"
	szwja=$mokzdz/config/a.$cpuxh.sh
	szwjb=$mokzdz/config/b.$cpuxh.sh



	pan=$(cat $pan1)




	if cat /sys/devices/system/cpu/cpufreq/policy0/scaling_available_governors | grep -q "scx"; then
		#首选walt调速器
		echo "a方案walt可以使用"

		if test ! -f $szwja ;then
			#不存在可修改


			#对b进行判断是否存在
			if test ! -f $szwjb ;then
				echo "没有配置文件"
			else
				. $mokzdz/script/b.main.sh
				. $mokzdz/config/b.$cpuxh.sh
				echo "用b方案schedutil调速器"
			fi

		else
			#存在可修改
			. $mokzdz/script/a.main.sh
			. $mokzdz/config/a.$cpuxh.sh
			echo "用a方案walt调速器"
		fi


	else
		#没有walt调速器则后选schedutil调速器
		if cat /sys/devices/system/cpu/cpufreq/policy0/scaling_available_governors | grep -q "conservative"; then
			echo "只有b方案schedutil可用"
			. $mokzdz/script/b.main.sh
			. $mokzdz/config/b.$cpuxh.sh
		else
			echo "无walt和schedutil调速器"
		fi
	fi



	#切换结束
	echo "1" > $mosdz/qhz

fi



