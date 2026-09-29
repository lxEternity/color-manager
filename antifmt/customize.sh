#!/system/bin/sh
ui_print "===================================="
ui_print "   防格机·全分区守护  v1.0.0"
ui_print "===================================="
ui_print ""
ui_print "  • 内核级只读锁：引导/系统/密钥/校准分区"
ui_print "  • 防误格机、防恶意格机导致的变砖"
ui_print "  • 看门狗周期自动重锁，封堵 setrw 抢写"
ui_print "  • 不触碰引导链，只读锁不影响开机"
ui_print ""

# 安装时预览本机可保护分区数量
if [ -d /dev/block/by-name ] || [ -d /dev/block/mapper ]; then
    . "$MODPATH/lists.sh"
    N=0
    for link in /dev/block/by-name/* /dev/block/mapper/*; do
        [ -e "$link" ] || continue
        name=${link##*/}
        base=${name%_a}; base=${base%_b}
        case " $SKIP_DEFAULT " in *" $name "*|*" $base "*) continue ;; esac
        case " $PROTECT_DEFAULT " in *" $name "*|*" $base "*) N=$((N+1)); continue ;; esac
    done
    ui_print "  本机检测到 $N 个可保护分区"
    ui_print "  重启后自动加锁，模块介绍中将显示实际保护数量"
fi

ui_print ""
ui_print "  ⚠ OTA/刷机/Magisk 升级前请先关闭保护："
ui_print "    /data/adb/antifmt/config 设 ENABLED=0 并重启"
ui_print "    （fastboot 线刷不受影响，无需担心救砖）"
