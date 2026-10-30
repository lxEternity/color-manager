{
mingc="qingtd"
mokml="/sdcard/Android/$mingc"

	until [ -d $mokml ] && [ -d /data ]; do
		sleep 1
	done

	rm -rf $mokml
	rm -f /data/powercfg*
} &

{
MODDIR=${0%/*}
[ -f "$MODDIR/screen/common.sh" ] && . "$MODDIR/screen/common.sh" && {
	apply_ltpo keep
	restore_orig
	rm -f "$STATE" "$REV"
}
} &

{
tdir=/data/adb/colorFC_thermal
t=$(cat /sys/class/power_supply/battery/temp 2>/dev/null)
case "$t" in
	''|*[!0-9]*) ;;
	*)
		i=0
		while [ "$i" -le 7 ]; do
			echo "$i $((t*100))" > /proc/shell-temp 2>/dev/null
			i=$((i+1))
		done
		;;
esac
if [ -f "$tdir/trips_orig" ]; then
	while IFS='=' read -r tp tv; do
		case "$tp" in /sys/class/thermal/*) ;;
			*) continue ;;
		esac
		case "$tv" in ''|*[!0-9]*) continue ;; esac
		echo "$tv" > "$tp" 2>/dev/null
	done < "$tdir/trips_orig"
fi
rm -rf "$tdir"
} &
