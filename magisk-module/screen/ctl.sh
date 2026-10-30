#!/system/bin/sh
MODDIR=$(cd "${0%/*}" && pwd)
. "$MODDIR/common.sh"

case "$1" in
    scan)
        scan_rates
        ;;
    apps)
        pm list packages -3 2>/dev/null | tr -d '\r' | sed -n 's/^package:\(.*\)$/\1 0/p'
        pm list packages -s 2>/dev/null | tr -d '\r' | sed -n 's/^package:\(.*\)$/\1 1/p'
        ;;
    ltpo)
        case "$2" in
            compat|disable|keep) ;;
            *) echo "用法：ctl.sh ltpo [compat|disable|keep]" >&2; exit 1 ;;
        esac
        mkdir -p "$SCR_DATA"
        echo "$2" > "$LTPO_FILE"
        apply_ltpo "$2"
        bump_rev
        log "LTPO 模式已设为 $2"
        echo OK
        ;;
    reset)
        rm -f "$SETTINGS" "$RATES" "$APPS" "$STATE" "$SCR_DATA/config.json"
        date +%s > "$REV"
        log "已清除全部配置"
        ;;
    *)
        echo "用法：ctl.sh scan|apps|ltpo|reset" >&2
        exit 1
        ;;
esac
