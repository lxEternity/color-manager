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

    # 前台解析失败/为空时直接退出：
    # 空值会让 grep -q 恒真并按 conf 首个含=行取模式，导致模式被莫名切回
    if [ -z "$xbaoming" ]; then
        echo "$shij 前台应用解析为空，跳过本次切换" >> $rizhidz
        exit 0
    fi

    # 应用专属规则：精确前缀匹配（包名=），命中多个只取第一条
    if mos=$(grep "^$xbaoming=" "$hmd" 2>/dev/null | head -n 1 | cut -d '=' -f2); [ -n "$mos" ]; then
        echo "$shij $xbaoming >> $mos" >> $rizhidz
        ms=$mos
        kzlx=1
        . /data/powercfg.sh
    else
        mos=$(grep "^moren=" "$hmd" 2>/dev/null | head -n 1 | cut -d '=' -f2)
        # moren 缺失/为空时不切换（防止切到空模式）
        if [ -z "$mos" ]; then
            echo "$shij 未配置默认模式(moren)，跳过本次切换" >> $rizhidz
            exit 0
        fi
        echo "$shij $xbaoming >> $mos" >> $rizhidz
        ms=$mos
        kzlx=1
        . /data/powercfg.sh
    fi

    jbaoming=$xbaoming
    echo $xbaoming > $mosdz/baom
fi