{
mingc="qingtd"
mokml="/sdcard/Android/$mingc"

	until [ -d $mokml ] && [ -d /data ]; do
		sleep 1
	done

	rm -rf $mokml
	rm -f /data/powercfg*
} &
