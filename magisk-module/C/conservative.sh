#!/system/bin/sh
# C 方案省电模式：conservative 调速器（小核 cpu0-3 / 大核 cpu4-7 分组参数）

# 小核 cpu0-3
chmod 777 /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor
echo "conservative" > /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor
echo "96" > /sys/devices/system/cpu/cpu0/cpufreq/conservative/up_threshold
echo "88" > /sys/devices/system/cpu/cpu0/cpufreq/conservative/down_threshold
echo "1" > /sys/devices/system/cpu/cpu0/cpufreq/conservative/freq_step
echo "10000" > /sys/devices/system/cpu/cpu0/cpufreq/conservative/sampling_rate

chmod 777 /sys/devices/system/cpu/cpu1/cpufreq/scaling_governor
echo "conservative" > /sys/devices/system/cpu/cpu1/cpufreq/scaling_governor
echo "96" > /sys/devices/system/cpu/cpu1/cpufreq/conservative/up_threshold
echo "88" > /sys/devices/system/cpu/cpu1/cpufreq/conservative/down_threshold
echo "1" > /sys/devices/system/cpu/cpu1/cpufreq/conservative/freq_step
echo "10000" > /sys/devices/system/cpu/cpu1/cpufreq/conservative/sampling_rate

chmod 777 /sys/devices/system/cpu/cpu2/cpufreq/scaling_governor
echo "conservative" > /sys/devices/system/cpu/cpu2/cpufreq/scaling_governor
echo "96" > /sys/devices/system/cpu/cpu2/cpufreq/conservative/up_threshold
echo "88" > /sys/devices/system/cpu/cpu2/cpufreq/conservative/down_threshold
echo "1" > /sys/devices/system/cpu/cpu2/cpufreq/conservative/freq_step
echo "10000" > /sys/devices/system/cpu/cpu2/cpufreq/conservative/sampling_rate

chmod 777 /sys/devices/system/cpu/cpu3/cpufreq/scaling_governor
echo "conservative" > /sys/devices/system/cpu/cpu3/cpufreq/scaling_governor
echo "96" > /sys/devices/system/cpu/cpu3/cpufreq/conservative/up_threshold
echo "88" > /sys/devices/system/cpu/cpu3/cpufreq/conservative/down_threshold
echo "1" > /sys/devices/system/cpu/cpu3/cpufreq/conservative/freq_step
echo "10000" > /sys/devices/system/cpu/cpu3/cpufreq/conservative/sampling_rate

# 大核 cpu4-7
chmod 777 /sys/devices/system/cpu/cpu4/cpufreq/scaling_governor
echo "conservative" > /sys/devices/system/cpu/cpu4/cpufreq/scaling_governor
echo "98" > /sys/devices/system/cpu/cpu4/cpufreq/conservative/up_threshold
echo "85" > /sys/devices/system/cpu/cpu4/cpufreq/conservative/down_threshold
echo "1" > /sys/devices/system/cpu/cpu4/cpufreq/conservative/freq_step
echo "12000" > /sys/devices/system/cpu/cpu4/cpufreq/conservative/sampling_rate

chmod 777 /sys/devices/system/cpu/cpu5/cpufreq/scaling_governor
echo "conservative" > /sys/devices/system/cpu/cpu5/cpufreq/scaling_governor
echo "98" > /sys/devices/system/cpu/cpu5/cpufreq/conservative/up_threshold
echo "85" > /sys/devices/system/cpu/cpu5/cpufreq/conservative/down_threshold
echo "1" > /sys/devices/system/cpu/cpu5/cpufreq/conservative/freq_step
echo "12000" > /sys/devices/system/cpu/cpu5/cpufreq/conservative/sampling_rate

chmod 777 /sys/devices/system/cpu/cpu6/cpufreq/scaling_governor
echo "conservative" > /sys/devices/system/cpu/cpu6/cpufreq/scaling_governor
echo "98" > /sys/devices/system/cpu/cpu6/cpufreq/conservative/up_threshold
echo "85" > /sys/devices/system/cpu/cpu6/cpufreq/conservative/down_threshold
echo "1" > /sys/devices/system/cpu/cpu6/cpufreq/conservative/freq_step
echo "12000" > /sys/devices/system/cpu/cpu6/cpufreq/conservative/sampling_rate

chmod 777 /sys/devices/system/cpu/cpu7/cpufreq/scaling_governor
echo "conservative" > /sys/devices/system/cpu/cpu7/cpufreq/scaling_governor
echo "98" > /sys/devices/system/cpu/cpu7/cpufreq/conservative/up_threshold
echo "85" > /sys/devices/system/cpu/cpu7/cpufreq/conservative/down_threshold
echo "1" > /sys/devices/system/cpu/cpu7/cpufreq/conservative/freq_step
echo "12000" > /sys/devices/system/cpu/cpu7/cpufreq/conservative/sampling_rate

echo "1" > /sys/devices/system/cpu/cpu1/online
echo "1" > /sys/devices/system/cpu/cpu2/online
echo "1" > /sys/devices/system/cpu/cpu3/online
echo "1" > /sys/devices/system/cpu/cpu4/online
echo "1" > /sys/devices/system/cpu/cpu5/online
echo "1" > /sys/devices/system/cpu/cpu6/online
echo "1" > /sys/devices/system/cpu/cpu7/online
