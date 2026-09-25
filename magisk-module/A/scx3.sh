#!/system/bin/sh
cpus="0 3 5 7"

for cpu in $cpus;do
    chmod 777 /sys/devices/system/cpu/cpu$cpu/cpufreq/scaling_governor
    echo "walt" > /sys/devices/system/cpu/cpu$cpu/cpufreq/scaling_governor

    echo "60" > /sys/devices/system/cpu/cpu$cpu/cpufreq/walt/target_loads
done




echo "1" > /proc/game_opt/early_detect/ed_enable
