BASEDIR="$(dirname $(readlink -f "$0"))"
. $BASEDIR/quanj.sh

$MODULE_PATH/fps

jbaoming=$(cat $mosdz/baom)
#获取前台并且输出
xbaoming=$(dumpsys window displays | grep "mFocusedApp" | grep -v "AppWindowToken" | awk '{print $(NF-1)}' | awk -F "/" '{print $1}')

if [[ $jbaoming != $xbaoming ]]; then
    shij="[$(date '+%T')]"

    # 使用stat命令获取文件大小（以字节为单位）
    filesize=$(stat -c%s "$rizhidz")

    # 判断文件大小是否大于2048字节（2KB）
    if test "$filesize" -gt 10240 ; then
        echo "$shij 日志大于10k进行清空" > $rizhidz
    fi

    if test -f $mokml/stop ; then
        shij="[$(date '+%T')]"
        echo "$shij 检测外部控制关闭动态!!!" >> $rizhidz
        pid=$(pgrep -f "qingtdjc")

        kill $(pgrep -f "qingtdjc")

        kill $(pgrep -f "qtbh")

        exit 1
    fi

    xbaoming=$(dumpsys window displays | grep "mFocusedApp" | grep -v "AppWindowToken" | grep "ActivityRecord" | awk -F " " '{print $3}' | awk -F "/" '{print $1}')

    if cat $hmd | grep -q $xbaoming; then
        mos=$(grep "$xbaoming=" "$hmd" | cut -d '=' -f2)
        echo "$shij $xbaoming >> $mos" >> $rizhidz
        ms=$mos
        kzlx=1
        . /data/powercfg.sh
    else
        mos=$(grep "moren=" "$hmd" | cut -d '=' -f2)
        echo "$shij $xbaoming >> $mos" >> $rizhidz
        ms=$mos
        kzlx=1
        . /data/powercfg.sh
    fi

    jbaoming=$xbaoming
    echo $xbaoming > $mosdz/baom
fi