MODULE_PATH="$(dirname $(readlink -f "$0"))"
MODULE_PATH="${MODULE_PATH%\/script}"
mingc="qingtd"
mokml="/sdcard/Android/$mingc"
rizhidz="$mokml/logs.txt"
hmd="$mokml/动态模式切换.conf"
mosdz=$MODULE_PATH/files

function cpuset(){
local pid=$(ps -ef | grep $1 | grep -v grep | awk '{print $2}')

echo $pid > /dev/cpuset/$2/tasks
echo $pid
}


wj_zr() {
    local value="$1"
    local file="$2"
if [ -f $2 ]; then
	chmod 777 $file
	echo $value > $file
	chmod 444 $file
fi
}
