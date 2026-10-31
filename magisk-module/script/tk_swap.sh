#!/system/bin/sh
# 游戏接管形态切换（风驰增强 ⇄ Color调度），逻辑与自适应限频 swap.sh 一致：
# 切到风驰增强时卸载整个调度目录并备份，游戏助手策略全权接管；
# 切回 Color调度时释放备份、重启前台监视守护。
SWAPDIR=$(dirname "$(readlink -f "$0")")
MODROOT=${SWAPDIR%/script}
STORE=/data/adb/colorFC_store
DLOG=$STORE/tk_swap.log
MODE=$1

TK_ITEMS="A B C config qingtd"
DATA_ITEMS="/data/powercfg.sh /data/powercfg.json"
SCENE_PKGS="com.omarea.scene_mode com.omarea.vtools"

log() { echo "[$(date '+m-%d %H:%M:%S')] $*" >> "$DLOG" 2>/dev/null; }

stop_dispatch() {
    for p in "qingtdjc1.sh" "script/qingtd.sh" "qingtdjc" "qtbh.sh"; do
        pkill -f "$p" 2>/dev/null
    done
    log "调度守护已停止"
}

mkdir -p "$STORE" "$STORE/fengchi" 2>/dev/null
log "==== tk_swap 开始: $MODE ===="

case "$MODE" in
fengchi)
    if [ ! -d "$MODROOT/A" ] && [ -f "$STORE/fengchi/.done" ]; then
        log "已是风驰增强形态，跳过"
        echo "ALREADY"
        exit 0
    fi
    stop_dispatch
    sh "$MODROOT/script/release.sh" >/dev/null 2>&1 && log "已释放Color频率锁"
    for sp in $SCENE_PKGS; do
        if pm path "$sp" >/dev/null 2>&1; then
            am force-stop "$sp" >/dev/null 2>&1 && log "已停止 $sp（防止Scene应用策略覆盖接管）"
        fi
    done
    for f in $TK_ITEMS; do
        if [ -e "$MODROOT/$f" ]; then
            rm -rf "${STORE:?}/fengchi/$f" 2>/dev/null
            mv -f "$MODROOT/$f" "$STORE/fengchi/" 2>/dev/null
        fi
    done
    for f in $DATA_ITEMS; do
        [ -e "$f" ] && mv -f "$f" "$STORE/fengchi/" 2>/dev/null
    done
    rm -f "$MODROOT/files/released" 2>/dev/null
    touch "$STORE/fengchi/.done"
    log "调度目录已卸载并备份至 $STORE/fengchi"
    echo "OK_FENGCHI"
    ;;
color)
    if [ ! -f "$STORE/fengchi/.done" ]; then
        log "已是Color调度形态，跳过"
        echo "ALREADY"
        exit 0
    fi
    for f in $TK_ITEMS; do
        if [ -e "$STORE/fengchi/$f" ]; then
            rm -rf "${MODROOT:?}/$f" 2>/dev/null
            cp -af "$STORE/fengchi/$f" "$MODROOT/" 2>/dev/null
        fi
    done
    [ -d "$MODROOT/A" ] && chmod 0755 "$MODROOT/A"/* 2>/dev/null
    [ -d "$MODROOT/B" ] && chmod 0755 "$MODROOT/B"/* 2>/dev/null
    [ -d "$MODROOT/C" ] && chmod 0755 "$MODROOT/C"/* 2>/dev/null
    [ -f "$STORE/fengchi/powercfg.sh" ] && { cp -af "$STORE/fengchi/powercfg.sh" /data/ && chmod 0777 /data/powercfg.sh; }
    [ -f "$STORE/fengchi/powercfg.json" ] && { cp -af "$STORE/fengchi/powercfg.json" /data/ && chmod 0777 /data/powercfg.json; }
    rm -rf "${STORE:?}/fengchi" 2>/dev/null
    rm -f "$MODROOT/files/released" 2>/dev/null
    mos=$(grep "^moren=" "/sdcard/Android/qingtd/动态模式切换.conf" 2>/dev/null | head -n 1 | cut -d '=' -f2)
    if [ -n "$mos" ]; then
        ms=$mos
        kzlx=1
        . /data/powercfg.sh >/dev/null 2>&1 && log "已恢复Color默认模式 $mos"
    fi
    rm -f "/sdcard/Android/qingtd/stop" 2>/dev/null
    pgrep -f qingtdjc1 >/dev/null 2>&1 || nohup sh "$MODROOT/script/qingtdjc1.sh" >/dev/null 2>&1 &
    log "调度目录已恢复，Color调度接管"
    echo "OK_COLOR"
    ;;
*)
    echo "USAGE: sh tk_swap.sh [fengchi|color]"
    exit 1
    ;;
esac
