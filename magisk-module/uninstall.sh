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
