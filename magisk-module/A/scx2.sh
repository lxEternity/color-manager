#!/system/bin/sh
cpus="0 3 5 7"

for cpu in $cpus;do
    chmod 777 /sys/devices/system/cpu/cpu$cpu/cpufreq/scaling_governor
    echo "scx" > /sys/devices/system/cpu/cpu$cpu/cpufreq/scaling_governor


    echo "74" > /sys/devices/system/cpu/cpu$cpu/cpufreq/scx/target_loads

done

echo "1" > /proc/hmbird_sched/scx_enable
echo "1" > /proc/game_opt/early_detect/ed_enable
