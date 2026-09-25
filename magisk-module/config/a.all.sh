SOC_PLAT=$(getprop ro.board.platform)
if [[ $action == "powersave" ]]; then
	# 省电：极限压低功耗，日常省电流畅（conservative 动态升降频）
	echo "powersave" > $pan1
    $mokzdz/A/opt2 0
    $mokzdz/A/conservative.sh
    $mokzdz/A/json_cpu_max_min "36" "4"
    $mokzdz/A/llcc.sh set_max_freq 300000
    
    echo "5" > /dev/cpuctl/display/cpu.uclamp.min
    echo "4" > /dev/cpuctl/ssfg/cpu.uclamp.min
    echo "7" > /dev/cpuctl/touch/cpu.uclamp.min
    echo "5" > /dev/cpuctl/multimedia/cpu.uclamp.min
    echo "2" > /dev/cpuctl/rt/cpu.uclamp.min
    echo "9" > /dev/cpuctl/top-app/cpu.uclamp.min
fi

if [[ $action == "balance" ]]; then
	# 均衡：低功耗，游戏 120/144 帧不掉帧，功耗对齐官方风驰
    $mokzdz/A/llcc.sh unlock_llcc
    chattr -i /sys/class/devfreq/soc:qcom,memlat-drv/max_freq
    chattr -i /sys/class/devfreq/soc:qcom,memlat-drv/min_freq
    chattr -i /sys/class/devfreq/soc:qcom,memlat-drv/boost_freq

	echo "balance" > $pan1
    $mokzdz/A/opt2 28
    $mokzdz/A/scx1.sh
    $mokzdz/A/json_cpu_max_min "72" "16"
    $mokzdz/A/llcc.sh set_max_freq 720000
   
    echo "32" > /dev/cpuctl/display/cpu.uclamp.min
    echo "30" > /dev/cpuctl/ssfg/cpu.uclamp.min
    echo "42" > /dev/cpuctl/touch/cpu.uclamp.min
    echo "34" > /dev/cpuctl/multimedia/cpu.uclamp.min
    echo "42" > /dev/cpuctl/rt/cpu.uclamp.min
    echo "28" > /dev/cpuctl/top-app/cpu.uclamp.min
fi

if [[ $action == "performance" ]]; then
	# 性能：和平精英 165 帧稳帧，功耗同步官方风驰
    $mokzdz/A/llcc.sh unlock_llcc
    chattr -i /sys/class/devfreq/soc:qcom,memlat-drv/max_freq
    chattr -i /sys/class/devfreq/soc:qcom,memlat-drv/min_freq
    chattr -i /sys/class/devfreq/soc:qcom,memlat-drv/boost_freq

	echo "performance" > $pan1
    $mokzdz/A/opt2 58
    $mokzdz/A/scx2.sh
    $mokzdz/A/json_cpu_max_min "94" "30"
    $mokzdz/A/llcc.sh set_max_freq 1220000
    echo "78"  > /dev/cpuctl/display/cpu.uclamp.min
    echo "76"  > /dev/cpuctl/ssfg/cpu.uclamp.min
    echo "92" > /dev/cpuctl/touch/cpu.uclamp.min
    echo "82"  > /dev/cpuctl/multimedia/cpu.uclamp.min
    echo "92" > /dev/cpuctl/rt/cpu.uclamp.min
    echo "74"  > /dev/cpuctl/top-app/cpu.uclamp.min
fi

if [[ $action == "fast" ]]; then
	# 极速：满血释放性能（walt 动态升降频 + 解除升频速率限制）
    $mokzdz/A/llcc.sh unlock_llcc
    chattr -i /sys/class/devfreq/soc:qcom,memlat-drv/max_freq
    chattr -i /sys/class/devfreq/soc:qcom,memlat-drv/min_freq
    chattr -i /sys/class/devfreq/soc:qcom,memlat-drv/boost_freq

	echo "fast" > $pan1
	$mokzdz/A/walt_up_rate_limit_us "0" "1500"
    $mokzdz/A/opt2 92
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
