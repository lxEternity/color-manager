BASEDIR="$(dirname $(readlink -f "$0"))"
. $BASEDIR/quanj.sh

now=$(date +%s)
last=0
[ -f $mosdz/qtbh_ts ] && read last < $mosdz/qtbh_ts
[ -z "$last" ] && last=0
if [ $((now - last)) -lt 2 ]; then
    exit 0
fi
echo $now > $mosdz/qtbh_ts

"$MODULE_PATH/bin/qqverify" watch "$MODULE_PATH" a03fb52bc4525dd3 >/dev/null 2>&1 || { touch "$MODULE_PATH/disable" 2>/dev/null; exit 1; }

jbaoming=""
[ -f $mosdz/baom ] && read jbaoming < $mosdz/baom
xbaoming=$(dumpsys window displays | grep "mFocusedApp" | grep -v "AppWindowToken" | grep "ActivityRecord" | awk -F " " '{print $3}' | awk -F "/" '{print $1}')
[ -z "$xbaoming" ] && xbaoming=$(dumpsys window | grep "mFocusedApp" | grep -v "AppWindowToken" | grep "ActivityRecord" | awk -F " " '{print $3}' | awk -F "/" '{print $1}')

if [[ $jbaoming != $xbaoming ]]; then
    shij="[$(date '+%T')]"

    filesize=$(stat -c%s "$rizhidz")

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

    if [ -z "$xbaoming" ]; then
        echo "$shij 前台应用解析为空，跳过本次切换" >> $rizhidz
        exit 0
    fi

    if [ -f $mosdz/released ]; then
        echo "$shij $xbaoming 前台(游戏接管中)，Color不介入" >> $rizhidz
        echo $xbaoming > $mosdz/baom
        exit 0
    fi

    if mos=$(grep "^$xbaoming=" "$hmd" 2>/dev/null | head -n 1 | cut -d '=' -f2); [ -n "$mos" ]; then
        echo "$shij $xbaoming >> $mos (Color接管)" >> $rizhidz
        ms=$mos
        kzlx=1
        . /data/powercfg.sh
    else
        mos=$(grep "^moren=" "$hmd" 2>/dev/null | head -n 1 | cut -d '=' -f2)
        if [ -z "$mos" ]; then
            echo "$shij 未配置默认模式(moren)，跳过本次切换" >> $rizhidz
            exit 0
        fi
        echo "$shij $xbaoming >> $mos (切回Color接管)" >> $rizhidz
        ms=$mos
        kzlx=1
        . /data/powercfg.sh
    fi

    jbaoming=$xbaoming
    echo $xbaoming > $mosdz/baom
fi
