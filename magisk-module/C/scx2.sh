#!/system/bin/sh
# C 方案性能模式：walt 调速器（小核 cpu0/3 =74，大核 cpu5/7 =66）

chmod 777 /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor
chmod 777 /sys/devices/system/cpu/cpu3/cpufreq/scaling_governor
chmod 777 /sys/devices/system/cpu/cpu5/cpufreq/scaling_governor
chmod 777 /sys/devices/system/cpu/cpu7/cpufreq/scaling_governor

echo "walt" > /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor
echo "walt" > /sys/devices/system/cpu/cpu3/cpufreq/scaling_governor
echo "walt" > /sys/devices/system/cpu/cpu5/cpufreq/scaling_governor
echo "walt" > /sys/devices/system/cpu/cpu7/cpufreq/scaling_governor

echo "74" > /sys/devices/system/cpu/cpu0/cpufreq/walt/target_loads
echo "74" > /sys/devices/system/cpu/cpu3/cpufreq/walt/target_loads
echo "66" > /sys/devices/system/cpu/cpu5/cpufreq/walt/target_loads
echo "66" > /sys/devices/system/cpu/cpu7/cpufreq/walt/target_loads

echo "1" > /sys/devices/system/cpu/cpu1/online
echo "1" > /sys/devices/system/cpu/cpu2/online
echo "1" > /sys/devices/system/cpu/cpu3/online
echo "1" > /sys/devices/system/cpu/cpu4/online
echo "1" > /sys/devices/system/cpu/cpu5/online
echo "1" > /sys/devices/system/cpu/cpu6/online
echo "1" > /sys/devices/system/cpu/cpu7/online

echo "1" > /proc/hmbird_sched/walt_enable 2>/dev/null
echo "1" > /proc/game_opt/early_detect/ed_enable 2>/dev/null
