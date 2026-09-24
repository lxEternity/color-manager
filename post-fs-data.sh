lock_val() {
	umount $2
	chmod +w $2

	echo "$1" | tee /dev/fas_rs_mask $2
	/bin/find $2 -exec mount /dev/fas_rs_mask {} \;
	rm /dev/fas_rs_mask
}
lock_val "" "/system_ext/bin/horae"
lock_val "" "/proc/game_opt/cpu_max_freq"
lock_val "" "/proc/game_opt/fake_cpu7_cpuinfo_max_freq"
lock_val "" "/proc/game_opt/disable_cpufreq_limit"
lock_val "" "/proc/game_opt/fake_cpu7_cpuinfo_max_freq"
lock_val "" "/proc/game_opt/early_detect/fst_cpu_max_freq"
lock_val "" "/proc/game_opt/early_detect/flt_cpu_max_freq"
lock_val "" "/proc/game_opt/early_detect/edb_cpu_max_freq"
dumpsys horae testmode
for i in $(seq 0 7); do
    echo "$i 36000" > /proc/shell-temp
done
