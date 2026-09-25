

chmod 777 /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor
chmod 777 /sys/devices/system/cpu/cpu3/cpufreq/scaling_governor
chmod 777 /sys/devices/system/cpu/cpu5/cpufreq/scaling_governor
chmod 777 /sys/devices/system/cpu/cpu7/cpufreq/scaling_governor

echo "conservative" > /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor
echo "conservative" > /sys/devices/system/cpu/cpu3/cpufreq/scaling_governor
echo "conservative" > /sys/devices/system/cpu/cpu5/cpufreq/scaling_governor
echo "conservative" > /sys/devices/system/cpu/cpu7/cpufreq/scaling_governor

echo "93" > /sys/devices/system/cpu/cpu0/cpufreq/conservative/up_threshold
echo "93" > /sys/devices/system/cpu/cpu3/cpufreq/conservative/up_threshold
echo "93" > /sys/devices/system/cpu/cpu5/cpufreq/conservative/up_threshold
echo "93" > /sys/devices/system/cpu/cpu7/cpufreq/conservative/up_threshold

echo "86" > /sys/devices/system/cpu/cpu0/cpufreq/conservative/down_threshold
echo "86" > /sys/devices/system/cpu/cpu3/cpufreq/conservative/down_threshold
echo "86" > /sys/devices/system/cpu/cpu5/cpufreq/conservative/down_threshold
echo "86" > /sys/devices/system/cpu/cpu7/cpufreq/conservative/down_threshold


echo "2" > /sys/devices/system/cpu/cpu0/cpufreq/conservative/freq_step
echo "2" > /sys/devices/system/cpu/cpu3/cpufreq/conservative/freq_step
echo "2" > /sys/devices/system/cpu/cpu5/cpufreq/conservative/freq_step
echo "2" > /sys/devices/system/cpu/cpu7/cpufreq/conservative/freq_step


echo "12000" > /sys/devices/system/cpu/cpu0/cpufreq/conservative/sampling_rate
echo "12000" > /sys/devices/system/cpu/cpu3/cpufreq/conservative/sampling_rate
echo "12000" > /sys/devices/system/cpu/cpu5/cpufreq/conservative/sampling_rate
echo "12000" > /sys/devices/system/cpu/cpu7/cpufreq/conservative/sampling_rate
