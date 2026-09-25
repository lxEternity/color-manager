#!/system/bin/sh

cpus="0 3 5 7"


for cpu in $cpus;do
    chmod 777 /sys/devices/system/cpu/cpu$cpu/cpufreq/scaling_governor
    echo "conservative" > /sys/devices/system/cpu/cpu$cpu/cpufreq/scaling_governor

    
    echo "98" > /sys/devices/system/cpu/cpu$cpu/cpufreq/conservative/up_threshold
    echo "90" > /sys/devices/system/cpu/cpu$cpu/cpufreq/conservative/down_threshold
    echo "1" > /sys/devices/system/cpu/cpu$cpu/cpufreq/conservative/freq_step
    echo "20000" > /sys/devices/system/cpu/cpu$cpu/cpufreq/conservative/sampling_rate
    echo "1" > /sys/devices/system/cpu/cpu$cpu/cpufreq/conservative/ignore_nice_load
done


chmod 777 /proc/game_opt/early_detect/ed_enable
              
echo "0" > /proc/game_opt/early_detect/ed_enable
