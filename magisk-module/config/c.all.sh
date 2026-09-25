SOC_PLAT=$(getprop ro.board.platform)
if [[ $action == "powersave" ]]; then
	# 省电
	echo "powersave" > $pan1
    $mokzdz/C/opt2 0
    $mokzdz/C/conservative.sh
    $mokzdz/C/json_cpu_max_min "36" "4"
    sh $mokzdz/C/freq0.sh 2>/dev/null
    $mokzdz/C/llcc.sh set_max_freq 300000

    echo "4" > /dev/cpuctl/display/cpu.uclamp.min
    echo "3" > /dev/cpuctl/ssfg/cpu.uclamp.min
    echo "6" > /dev/cpuctl/touch/cpu.uclamp.min
    echo "4" > /dev/cpuctl/multimedia/cpu.uclamp.min
    echo "2" > /dev/cpuctl/rt/cpu.uclamp.min
    echo "8" > /dev/cpuctl/top-app/cpu.uclamp.min
fi

if [[ $action == "balance" ]]; then
	# 均衡
    $mokzdz/C/llcc.sh unlock_llcc
    chattr -i /sys/class/devfreq/soc:qcom,memlat-drv/max_freq
    chattr -i /sys/class/devfreq/soc:qcom,memlat-drv/min_freq
    chattr -i /sys/class/devfreq/soc:qcom,memlat-drv/boost_freq

	echo "balance" > $pan1
    $mokzdz/C/opt2 32
    $mokzdz/C/scx1.sh
    $mokzdz/C/json_cpu_max_min "68" "24"
    sh $mokzdz/C/freq1.sh 2>/dev/null
    $mokzdz/C/llcc.sh set_max_freq 720000

    echo "34" > /dev/cpuctl/display/cpu.uclamp.min
    echo "30" > /dev/cpuctl/ssfg/cpu.uclamp.min
    echo "44" > /dev/cpuctl/touch/cpu.uclamp.min
    echo "36" > /dev/cpuctl/multimedia/cpu.uclamp.min
    echo "44" > /dev/cpuctl/rt/cpu.uclamp.min
    echo "30" > /dev/cpuctl/top-app/cpu.uclamp.min
fi

if [[ $action == "performance" ]]; then
	# 性能
    $mokzdz/C/llcc.sh unlock_llcc
    chattr -i /sys/class/devfreq/soc:qcom,memlat-drv/max_freq
    chattr -i /sys/class/devfreq/soc:qcom,memlat-drv/min_freq
    chattr -i /sys/class/devfreq/soc:qcom,memlat-drv/boost_freq

	echo "performance" > $pan1
    $mokzdz/C/opt2 60
    $mokzdz/C/scx2.sh
    $mokzdz/C/json_cpu_max_min "94" "38"
    sh $mokzdz/C/freq2.sh 2>/dev/null
    $mokzdz/C/llcc.sh set_max_freq 1350000
    echo "82"  > /dev/cpuctl/display/cpu.uclamp.min
    echo "78"  > /dev/cpuctl/ssfg/cpu.uclamp.min
    echo "94" > /dev/cpuctl/touch/cpu.uclamp.min
    echo "84"  > /dev/cpuctl/multimedia/cpu.uclamp.min
    echo "94" > /dev/cpuctl/rt/cpu.uclamp.min
    echo "76"  > /dev/cpuctl/top-app/cpu.uclamp.min
fi

if [[ $action == "fast" ]]; then
	# 极速
    $mokzdz/C/llcc.sh unlock_llcc
    chattr -i /sys/class/devfreq/soc:qcom,memlat-drv/max_freq
    chattr -i /sys/class/devfreq/soc:qcom,memlat-drv/min_freq
    chattr -i /sys/class/devfreq/soc:qcom,memlat-drv/boost_freq

	echo "fast" > $pan1
	$mokzdz/C/walt_up_rate_limit_us "0" "1500"
    $mokzdz/C/opt2 92
    $mokzdz/C/scx3.sh
    $mokzdz/C/llcc.sh set_max_freq 1920000
    $mokzdz/C/json_cpu_max_min "100" "46"
    sh $mokzdz/C/freq3.sh 2>/dev/null

    echo "86" > /dev/cpuctl/display/cpu.uclamp.min
    echo "82" > /dev/cpuctl/ssfg/cpu.uclamp.min
    echo "96" > /dev/cpuctl/touch/cpu.uclamp.min
    echo "88" > /dev/cpuctl/multimedia/cpu.uclamp.min
    echo "96" > /dev/cpuctl/rt/cpu.uclamp.min
    echo "80" > /dev/cpuctl/top-app/cpu.uclamp.min
fi
