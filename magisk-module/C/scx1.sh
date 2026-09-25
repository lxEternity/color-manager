#!/system/bin/sh
# C 方案均衡模式：walt 调速器（小核 cpu0/3 =82，大核 cpu5/7 =76）

chmod 777 /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor
chmod 777 /sys/devices/system/cpu/cpu3/cpufreq/scaling_governor
chmod 777 /sys/devices/system/cpu/cpu5/cpufreq/scaling_governor
chmod 777 /sys/devices/system/cpu/cpu7/cpufreq/scaling_governor

echo "walt" > /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor
echo "walt" > /sys/devices/system/cpu/cpu3/cpufreq/scaling_governor
echo "walt" > /sys/devices/system/cpu/cpu5/cpufreq/scaling_governor
echo "walt" > /sys/devices/system/cpu/cpu7/cpufreq/scaling_governor

# 小核(cpu0、cpu3) target_loads 82：轻负载回落，游戏场景及时抬频
echo "82" > /sys/devices/system/cpu/cpu0/cpufreq/walt/target_loads
echo "82" > /sys/devices/system/cpu/cpu3/cpufreq/walt/target_loads
# 大核(cpu5、cpu7) target_loads 76：降低目标负载，团战/爆炸更容易拉高频率，保障120帧稳定
echo "76" > /sys/devices/system/cpu/cpu5/cpufreq/walt/target_loads
echo "76" > /sys/devices/system/cpu/cpu7/cpufreq/walt/target_loads

echo "1" > /sys/devices/system/cpu/cpu1/online
echo "1" > /sys/devices/system/cpu/cpu2/online
echo "1" > /sys/devices/system/cpu/cpu3/online
echo "1" > /sys/devices/system/cpu/cpu4/online
echo "1" > /sys/devices/system/cpu/cpu5/online
echo "1" > /sys/devices/system/cpu/cpu6/online
echo "1" > /sys/devices/system/cpu/cpu7/online

echo "1" > /proc/hmbird_sched/walt_enable 2>/dev/null
echo "1" > /proc/game_opt/early_detect/ed_enable 2>/dev/null
