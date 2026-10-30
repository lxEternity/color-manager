BASEDIR="$(dirname $(readlink -f "$0"))"
. $BASEDIR/quanj.sh

now=$(date +%s)
last=0
[ -f $mosdz/qtbh_ts ] && read last < $mosdz/qtbh_ts
[ -z "$last" ] && last=0

jiance=1
[ -f $mosdz/takeover_iv ] && read jiance < $mosdz/takeover_iv
case "$jiance" in ''|*[!0-9]*) jiance=1 ;; esac
[ "$jiance" -lt 1 ] && jiance=1
[ "$jiance" -gt 10 ] && jiance=10

if [ $((now - last)) -lt $jiance ]; then
    exit 0
fi
echo $now > $mosdz/qtbh_ts

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

    tkm=""
    [ -f $mosdz/game_takeover ] && read tkm < $mosdz/game_takeover
    [ -z "$tkm" ] && tkm=color

    zxyy=0
    if [ "$tkm" != "color" ] && [ -s "$mosdz/takeover_apps" ]; then
        if grep -qx "$xbaoming" "$mosdz/takeover_apps" 2>/dev/null; then
            zxyy=1
        fi
    fi

    if mos=$(grep "^$xbaoming=" "$hmd" 2>/dev/null | head -n 1 | cut -d '=' -f2); [ -n "$mos" ] || [ "$zxyy" = "1" ]; then
        if [ "$tkm" = "color" ]; then
            echo "$shij $xbaoming >> $mos (Color接管)" >> $rizhidz
            ms=$mos
            kzlx=1
            . /data/powercfg.sh
        else
            ly="游戏名单"
            [ -z "$mos" ] && ly="自选应用"
            if [ ! -f "$mosdz/released" ]; then
                sh "$BASEDIR/release.sh" >/dev/null 2>&1
                echo 1 > "$mosdz/released"
            fi
            if [ "$tkm" = "fengchi" ] && [ ! -f "$MODULE_PATH/fengchi/.injected" ]; then
                echo "$shij $xbaoming 前台($ly·风驰增强接管)，策略未注入，后台注入中" >> $rizhidz
                nohup sh "$MODULE_PATH/fengchi/run_inject.sh" >/dev/null 2>&1 &
            else
                if [ "$tkm" = "fengchi" ]; then
                    echo "$shij $xbaoming 前台($ly·风驰增强接管)，Color不介入" >> $rizhidz
                else
                    echo "$shij $xbaoming 前台($ly·官方风驰接管)，Color不介入" >> $rizhidz
                fi
            fi
            jbaoming=$xbaoming
            echo $xbaoming > $mosdz/baom
            exit 0
        fi
    else
        mos=$(grep "^moren=" "$hmd" 2>/dev/null | head -n 1 | cut -d '=' -f2)
        if [ -z "$mos" ]; then
            echo "$shij 未配置默认模式(moren)，跳过本次切换" >> $rizhidz
            exit 0
        fi
        echo "$shij $xbaoming >> $mos (切回Color接管)" >> $rizhidz
        rm -f "$mosdz/released" 2>/dev/null
        ms=$mos
        kzlx=1
        . /data/powercfg.sh
    fi

    jbaoming=$xbaoming
    echo $xbaoming > $mosdz/baom
fi
