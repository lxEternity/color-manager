#!/system/bin/sh
# 省电调速器：conservative 动态升降频（字面 per-cpu 行，WebUI 调速器页可解析/保存）
# 小核 cpu0-3：98% 才升频 / 低于 75% 即降频 / 1% 步进 / 20ms 采样
# 大核+超大核 cpu4-7：98% 才升频 / 低于 55% 即降频 / 1% 步进 / 25ms 采样
# ignore_nice_load=1：后台 nice 任务不计入负载（避免后台活动维持高频，超大核无法休闲）

chmod 777 /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor
echo "conservative" > /sys/devices/system/cpu/cpu0/cpufreq/scaling_governor
echo "98" > /sys/devices/system/cpu/cpu0/cpufreq/conservative/up_threshold
echo "75" > /sys/devices/system/cpu/cpu0/cpufreq/conservative/down_threshold
echo "1" > /sys/devices/system/cpu/cpu0/cpufreq/conservative/freq_step
echo "20000" > /sys/devices/system/cpu/cpu0/cpufreq/conservative/sampling_rate
echo "1" > /sys/devices/system/cpu/cpu0/cpufreq/conservative/ignore_nice_load

chmod 777 /sys/devices/system/cpu/cpu1/cpufreq/scaling_governor
echo "conservative" > /sys/devices/system/cpu/cpu1/cpufreq/scaling_governor
echo "98" > /sys/devices/system/cpu/cpu1/cpufreq/conservative/up_threshold
echo "75" > /sys/devices/system/cpu/cpu1/cpufreq/conservative/down_threshold
echo "1" > /sys/devices/system/cpu/cpu1/cpufreq/conservative/freq_step
echo "20000" > /sys/devices/system/cpu/cpu1/cpufreq/conservative/sampling_rate
echo "1" > /sys/devices/system/cpu/cpu1/cpufreq/conservative/ignore_nice_load

chmod 777 /sys/devices/system/cpu/cpu2/cpufreq/scaling_governor
echo "conservative" > /sys/devices/system/cpu/cpu2/cpufreq/scaling_governor
echo "98" > /sys/devices/system/cpu/cpu2/cpufreq/conservative/up_threshold
echo "75" > /sys/devices/system/cpu/cpu2/cpufreq/conservative/down_threshold
echo "1" > /sys/devices/system/cpu/cpu2/cpufreq/conservative/freq_step
echo "20000" > /sys/devices/system/cpu/cpu2/cpufreq/conservative/sampling_rate
echo "1" > /sys/devices/system/cpu/cpu2/cpufreq/conservative/ignore_nice_load

chmod 777 /sys/devices/system/cpu/cpu3/cpufreq/scaling_governor
echo "conservative" > /sys/devices/system/cpu/cpu3/cpufreq/scaling_governor
echo "98" > /sys/devices/system/cpu/cpu3/cpufreq/conservative/up_threshold
echo "75" > /sys/devices/system/cpu/cpu3/cpufreq/conservative/down_threshold
echo "1" > /sys/devices/system/cpu/cpu3/cpufreq/conservative/freq_step
echo "20000" > /sys/devices/system/cpu/cpu3/cpufreq/conservative/sampling_rate
echo "1" > /sys/devices/system/cpu/cpu3/cpufreq/conservative/ignore_nice_load

chmod 777 /sys/devices/system/cpu/cpu4/cpufreq/scaling_governor
echo "conservative" > /sys/devices/system/cpu/cpu4/cpufreq/scaling_governor
echo "98" > /sys/devices/system/cpu/cpu4/cpufreq/conservative/up_threshold
echo "55" > /sys/devices/system/cpu/cpu4/cpufreq/conservative/down_threshold
echo "1" > /sys/devices/system/cpu/cpu4/cpufreq/conservative/freq_step
echo "25000" > /sys/devices/system/cpu/cpu4/cpufreq/conservative/sampling_rate
echo "1" > /sys/devices/system/cpu/cpu4/cpufreq/conservative/ignore_nice_load

chmod 777 /sys/devices/system/cpu/cpu5/cpufreq/scaling_governor
echo "conservative" > /sys/devices/system/cpu/cpu5/cpufreq/scaling_governor
echo "98" > /sys/devices/system/cpu/cpu5/cpufreq/conservative/up_threshold
echo "55" > /sys/devices/system/cpu/cpu5/cpufreq/conservative/down_threshold
echo "1" > /sys/devices/system/cpu/cpu5/cpufreq/conservative/freq_step
echo "25000" > /sys/devices/system/cpu/cpu5/cpufreq/conservative/sampling_rate
echo "1" > /sys/devices/system/cpu/cpu5/cpufreq/conservative/ignore_nice_load

chmod 777 /sys/devices/system/cpu/cpu6/cpufreq/scaling_governor
echo "conservative" > /sys/devices/system/cpu/cpu6/cpufreq/scaling_governor
echo "98" > /sys/devices/system/cpu/cpu6/cpufreq/conservative/up_threshold
echo "55" > /sys/devices/system/cpu/cpu6/cpufreq/conservative/down_threshold
echo "1" > /sys/devices/system/cpu/cpu6/cpufreq/conservative/freq_step
echo "25000" > /sys/devices/system/cpu/cpu6/cpufreq/conservative/sampling_rate
echo "1" > /sys/devices/system/cpu/cpu6/cpufreq/conservative/ignore_nice_load

chmod 777 /sys/devices/system/cpu/cpu7/cpufreq/scaling_governor
echo "conservative" > /sys/devices/system/cpu/cpu7/cpufreq/scaling_governor
echo "98" > /sys/devices/system/cpu/cpu7/cpufreq/conservative/up_threshold
echo "55" > /sys/devices/system/cpu/cpu7/cpufreq/conservative/down_threshold
echo "1" > /sys/devices/system/cpu/cpu7/cpufreq/conservative/freq_step
echo "25000" > /sys/devices/system/cpu/cpu7/cpufreq/conservative/sampling_rate
echo "1" > /sys/devices/system/cpu/cpu7/cpufreq/conservative/ignore_nice_load


chmod 777 /proc/game_opt/early_detect/ed_enable

echo "0" > /proc/game_opt/early_detect/ed_enable
