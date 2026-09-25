#!/system/bin/sh
cpus="0 3 5 7"

for cpu in $cpus;do
    chmod 777 /sys/devices/system/cpu/cpu$cpu/cpufreq/scaling_governor
    echo "walt" > /sys/devices/system/cpu/cpu$cpu/cpufreq/scaling_governor

    echo "85" > /sys/devices/system/cpu/cpu$cpu/cpufreq/conservative/up_threshold
    echo "80" > /sys/devices/system/cpu/cpu$cpu/cpufreq/conservative/down_threshold
    echo "1" > /sys/devices/system/cpu/cpu$cpu/cpufreq/conservative/freq_step
    echo "8000" > /sys/devices/system/cpu/cpu$cpu/cpufreq/conservative/sampling_rate
    echo "1" > /sys/devices/system/cpu/cpu$cpu/cpufreq/conservative/ignore_nice_load
done



       
echo "1" > /proc/game_opt/early_detect/ed_enable