SOC_PLAT=$(getprop ro.board.platform)
if [[ $action == "powersave" ]]; then
	# 省电
	echo "powersave" > $pan1
    $mokzdz/A/opt2 0
    $mokzdz/A/conservative.sh
    $mokzdz/A/json_cpu_max_min "42" "5"
    $mokzdz/A/llcc.sh set_max_freq 350000
    
    echo "6" > /dev/cpuctl/display/cpu.uclamp.min
    echo "5" > /dev/cpuctl/ssfg/cpu.uclamp.min
    echo "8" > /dev/cpuctl/touch/cpu.uclamp.min
    echo "6" > /dev/cpuctl/multimedia/cpu.uclamp.min
    echo "3" > /dev/cpuctl/rt/cpu.uclamp.min
    echo "10" > /dev/cpuctl/top-app/cpu.uclamp.min
fi

if [[ $action == "balance" ]]; then
	# 均衡
    $mokzdz/A/llcc.sh unlock_llcc
    chattr -i /sys/class/devfreq/soc:qcom,memlat-drv/max_freq
    chattr -i /sys/class/devfreq/soc:qcom,memlat-drv/min_freq
    chattr -i /sys/class/devfreq/soc:qcom,memlat-drv/boost_freq

	echo "balance" > $pan1
    $mokzdz/A/opt2 26
    $mokzdz/A/scx1.sh
    $mokzdz/A/json_cpu_max_min "64" "20"
    $mokzdz/A/llcc.sh set_max_freq 680000
   
    echo "30" > /dev/cpuctl/display/cpu.uclamp.min
    echo "28" > /dev/cpuctl/ssfg/cpu.uclamp.min
    echo "40" > /dev/cpuctl/touch/cpu.uclamp.min
    echo "32" > /dev/cpuctl/multimedia/cpu.uclamp.min
    echo "40" > /dev/cpuctl/rt/cpu.uclamp.min
    echo "26" > /dev/cpuctl/top-app/cpu.uclamp.min
fi

if [[ $action == "performance" ]]; then
	# 性能
    $mokzdz/A/llcc.sh unlock_llcc
    chattr -i /sys/class/devfreq/soc:qcom,memlat-drv/max_freq
    chattr -i /sys/class/devfreq/soc:qcom,memlat-drv/min_freq
    chattr -i /sys/class/devfreq/soc:qcom,memlat-drv/boost_freq

	echo "performance" > $pan1
    $mokzdz/A/opt2 52
    $mokzdz/A/scx2.sh
    $mokzdz/A/json_cpu_max_min "90" "35"
    $mokzdz/A/llcc.sh set_max_freq 1220000
    echo "78"  > /dev/cpuctl/display/cpu.uclamp.min
    echo "76"  > /dev/cpuctl/ssfg/cpu.uclamp.min
    echo "92" > /dev/cpuctl/touch/cpu.uclamp.min
    echo "80"  > /dev/cpuctl/multimedia/cpu.uclamp.min
    echo "92" > /dev/cpuctl/rt/cpu.uclamp.min
    echo "74"  > /dev/cpuctl/top-app/cpu.uclamp.min
fi

if [[ $action == "fast" ]]; then
	# 极速
    $mokzdz/A/llcc.sh unlock_llcc
    chattr -i /sys/class/devfreq/soc:qcom,memlat-drv/max_freq
    chattr -i /sys/class/devfreq/soc:qcom,memlat-drv/min_freq
    chattr -i /sys/class/devfreq/soc:qcom,memlat-drv/boost_freq

	echo "fast" > $pan1
	$mokzdz/A/walt_up_rate_limit_us "0" "1500"
    $mokzdz/A/opt2 88
    $mokzdz/A/scx3.sh
    $mokzdz/A/llcc.sh set_max_freq 1800000
    $mokzdz/A/json_cpu_max_min "100" "42"

    echo "82" > /dev/cpuctl/display/cpu.uclamp.min
    echo "80" > /dev/cpuctl/ssfg/cpu.uclamp.min
    echo "94" > /dev/cpuctl/touch/cpu.uclamp.min
    echo "84" > /dev/cpuctl/multimedia/cpu.uclamp.min
    echo "94" > /dev/cpuctl/rt/cpu.uclamp.min
    echo "78" > /dev/cpuctl/top-app/cpu.uclamp.min
fi 
