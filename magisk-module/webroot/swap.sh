#!/system/bin/sh
#==================================================
SWAPDIR=$(dirname "$(readlink -f "$0")")
MODROOT=${SWAPDIR%/*}
STORE=/data/adb/colorFC_store
DLOG=$STORE/swap.log
mode=$1

# 调度形态的专属文件
DISPATCH_ITEMS="script A B C config qingtd files install.sh post-fs-data.sh uninstall.sh service.sh module.prop META-INF README.md Updatelog.md json_cpu_max_min.c Color调度管理器_1.3.8.apk"

ADAPT_ITEMS="service.sh post-fs-data.sh action.sh powerd.sh powerd.conf proc_monitor.sh games.txt system updatelog.txt module.prop state pause status.json powerd.log"

log() { echo "[$(date '+%m-%d %H:%M:%S')] $*" >> "$DLOG" 2>/dev/null; }

mkdir -p "$STORE" "$STORE/dispatch" "$STORE/adapt"
log "==== swap 开始: $mode ===="

# ---------- 守护进程控制 ----------
stop_dispatch() {
  
    for p in "qingtdjc1.sh" "script/qingtd.sh" "qingtdjc" "qtbh.sh"; do
        pkill -f "$p" 2>/dev/null
    done
    log "调度守护已停止"
}

stop_adapt() {
    # powerd restore
    [ -f "$MODROOT/powerd.sh" ] && sh "$MODROOT/powerd.sh" restore >/dev/null 2>&1
    sleep 1
    for p in "powerd.sh start" "proc_monitor.sh" "colorFC/service.sh"; do
        pkill -f "$p" 2>/dev/null
    done
    log "自适应守护已停止（频率已恢复）"
}

# ---------- 文件搬迁 ----------
move_out_dispatch() {
    for f in $DISPATCH_ITEMS; do
        if [ -e "$MODROOT/$f" ]; then
            rm -rf "${STORE:?}/dispatch/$f" 2>/dev/null
            mv -f "$MODROOT/$f" "$STORE/dispatch/" 2>/dev/null
        fi
    done
    # 接口
    for f in /data/powercfg.sh /data/powercfg.json; do
        [ -e "$f" ] && mv -f "$f" "$STORE/dispatch/" 2>/dev/null
    done
    log "调度文件已完全卸载并备份: $STORE/dispatch"
}

move_in_dispatch() {
    if [ ! -f "$STORE/dispatch/module.prop" ]; then
        log "错误：未找到调度配置文件，中止"
        echo "ERR_NO_BACKUP"
        return 1
    fi
    for f in $DISPATCH_ITEMS; do
        if [ -e "$STORE/dispatch/$f" ]; then
            rm -rf "${MODROOT:?}/$f" 2>/dev/null
            cp -af "$STORE/dispatch/$f" "$MODROOT/" 2>/dev/null
        fi
    done
    chmod 0755 "$MODROOT/service.sh" "$MODROOT/post-fs-data.sh" "$MODROOT/uninstall.sh" "$MODROOT/install.sh" 2>/dev/null
    [ -d "$MODROOT/script" ] && chmod -R 0755 "$MODROOT/script" 2>/dev/null
    [ -d "$MODROOT/A" ] && chmod 0755 "$MODROOT/A"/* 2>/dev/null
    [ -d "$MODROOT/B" ] && chmod 0755 "$MODROOT/B"/* 2>/dev/null
    
    [ -f "$STORE/dispatch/powercfg.sh" ] && cp -af "$STORE/dispatch/powercfg.sh" /data/ && chmod 0777 /data/powercfg.sh
    [ -f "$STORE/dispatch/powercfg.json" ] && cp -af "$STORE/dispatch/powercfg.json" /data/ && chmod 0777 /data/powercfg.json
    log "调度文件已重新释放到模块目录"
    return 0
}

move_out_adapt() {
    for f in $ADAPT_ITEMS; do
        if [ -e "$MODROOT/$f" ]; then
            rm -rf "${STORE:?}/adapt/$f" 2>/dev/null
            mv -f "$MODROOT/$f" "$STORE/adapt/" 2>/dev/null
        fi
    done
    log "自适应文件（含用户改动）已移入备份: $STORE/adapt"
}

move_in_adapt() {
    
    src="$MODROOT/adapt"
    [ -f "$STORE/adapt/powerd.sh" ] && src="$STORE/adapt"
    for f in service.sh post-fs-data.sh action.sh powerd.sh powerd.conf proc_monitor.sh games.txt system updatelog.txt; do
        if [ -e "$src/$f" ]; then
            rm -rf "${MODROOT:?}/$f" 2>/dev/null
            cp -af "$src/$f" "$MODROOT/" 2>/dev/null
        fi
    done
    cp -af "$MODROOT/adapt/module.prop" "$MODROOT/module.prop" 2>/dev/null
    chmod 0755 "$MODROOT/powerd.sh" "$MODROOT/service.sh" "$MODROOT/proc_monitor.sh" "$MODROOT/post-fs-data.sh" "$MODROOT/action.sh" 2>/dev/null
    chmod 0755 "$MODROOT/system/bin/chkfreq.sh" "$MODROOT/system/bin/cpu_gpu_probe.sh" 2>/dev/null
    log "自适应文件已全新释放到模块目录 (源: $src)"
}

# ---------- 形态启动 ----------
start_adapt() {
    nohup /system/bin/sh "$MODROOT/service.sh" >/dev/null 2>&1 &
    log "自适应限频服务已启动（约10秒后开始限频）"
}

start_dispatch() {
    nohup /system/bin/sh "$MODROOT/script/qingtd.sh" >/dev/null 2>&1 &
    # 立即按上次模式重新应用一次
    sleep 1
    cur=$(cat /sdcard/Android/qingtd/cur_powermode.txt 2>/dev/null)
    [ -z "$cur" ] && cur="balance"
    peiz=$(cat "$MODROOT/files/peiz" 2>/dev/null)
    [ -z "$peiz" ] && peiz="all"
    /system/bin/sh "$MODROOT/script/main.sh" "$cur" "$MODROOT/files" "$peiz" >/dev/null 2>&1
    log "调度守护已启动，已应用模式: $cur"
}

# ---------- 刷新管理器模块列表 ----------
reload_managers() {
    sleep 2
    for m in me.weishu.kernelsu com.rifsxd.ksunext me.bmax.apatch; do
        if pm list packages 2>/dev/null | grep -q "package:$m$"; then
            am force-stop "$m" 2>/dev/null
            log "已重启管理器: $m（重开即重载模块列表）"
        fi
    done
}

# ---------- 主流程 ----------
case "$mode" in
adapt)
    if [ -f "$MODROOT/powerd.sh" ] && [ ! -d "$MODROOT/script" ]; then
        log "已是自适应形态，跳过"; echo "ALREADY_ADAPT"; exit 0
    fi
    stop_dispatch
    move_out_dispatch
    move_in_adapt
    if [ ! -f "$MODROOT/powerd.sh" ] || [ ! -f "$MODROOT/module.prop" ]; then
        log "错误：自适应文件释放失败"
        echo "ERR_ADAPT_DEPLOY"
        exit 1
    fi
    echo "adapt" > "$STORE/state"
    start_adapt
    log "==== 切换到自适应限频完成 ===="
    echo "OK_ADAPT"
    reload_managers
    ;;
dispatch)
    if [ -d "$MODROOT/script" ] && [ ! -f "$MODROOT/powerd.sh" ]; then
        log "已是调度形态，跳过"; echo "ALREADY_DISPATCH"; exit 0
    fi
    stop_adapt
    move_out_adapt
    move_in_dispatch || exit 1
    if [ ! -f "$MODROOT/script/qingtd.sh" ]; then
        log "错误：调度文件释放失败"
        echo "ERR_DISPATCH_DEPLOY"
        exit 1
    fi
    echo "dispatch" > "$STORE/state"
    start_dispatch
    log "==== 切换回Color调度完成 ===="
    echo "OK_DISPATCH"
    reload_managers
    ;;
*)
    echo "USAGE: sh swap.sh [adapt|dispatch]"
    exit 1
    ;;
esac
