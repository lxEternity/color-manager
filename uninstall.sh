{
mingc="qingtd"
mokml="/sdcard/Android/$mingc"
JSON_PATH="/data/data/com.omarea.vtools/files/manifest.json"

	until [ -d $mokml ] && [ -d /data ]; do
		sleep 1
	done

	[ -f "$JSON_PATH" ] && chattr -ia "$JSON_PATH" 2>/dev/null

	rm -rf $mokml
	rm -f /data/powercfg*
} & # do not block boot
