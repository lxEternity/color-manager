#!/system/bin/sh
# ============================================================
#  防格机·全分区守护 v1.0.0 —— 核心加锁逻辑
#  原理：对受保护分区的块设备下发内核级只读锁（BLKROSET），
#  任何进程（含 root）对设备打开写入/格式化/擦除一律 EROFS，
#  防止误格机/恶意格机导致的变砖与密钥丢失。
#  仅限制运行期写入，不影响开机读取——本模块不触碰引导链，
#  加锁失败也只写日志，绝不阻塞开机。
# ============================================================
MODDIR=$(dirname "$0")
RUN=/data/adb/antifmt
LIST="$RUN/protected.list"
LOG="$RUN/antifmt.log"
PROP="$MODDIR/module.prop"

mkdir -p "$RUN"

# 默认配置（首次运行生成，用户可随时编辑）
if [ ! -f "$RUN/config" ]; then
    cat > "$RUN/config" <<'CFGEOF'
# ===== 防格机守护配置 =====
# 总开关：0 = 关闭保护（重启后清除全部只读锁）
ENABLED=1
# 看门狗：周期性自动重锁（防 blockdev --setrw 后偷写）
WATCHDOG=1
# 看门狗重锁间隔（秒）
INTERVAL=5
# 追加保护分区（基础名，_a/_b 自动匹配），例：
# EXTRA_PROTECT="oplusreserve oplusstanvbk oplusdlcmn"
EXTRA_PROTECT=""
# 追加放行分区（优先级最高，例：EXTRA_SKIP="logo"）
EXTRA_SKIP=""
CFGEOF
fi
. "$RUN/config"

if [ "${ENABLED:-1}" != "1" ]; then
    sed -i "s|^description=.*|description=保护已关闭（ENABLED=0）。改回 1 并重启即可恢复全分区保护|" "$PROP"
    echo "[antifmt] 保护已被配置关闭"
    exit 0
fi

# OTA 快照合并期间不加锁，避免更新失败（合并完成后下次启动自动恢复）
if pgrep -f snapuserd >/dev/null 2>&1; then
    sed -i "s|^description=.*|description=系统 OTA 合并进行中，本次启动暂缓加锁；合并完成后重启自动恢复保护|" "$PROP"
    echo "[antifmt] OTA 快照合并中，本次跳过"
    exit 0
fi

. "$MODDIR/lists.sh"

# 只读锁命令（toybox blockdev，缺失时回退 Magisk busybox）
if command -v blockdev >/dev/null 2>&1; then
    RO="blockdev --setro"
elif [ -x /data/adb/busybox ]; then
    RO="/data/adb/busybox blockdev --setro"
else
    sed -i "s|^description=.*|description=环境缺少 blockdev 工具，保护未生效|" "$PROP"
    echo "[antifmt] 缺少 blockdev 工具"
    exit 1
fi

# 当前以读写方式挂载的设备（保护它们会立刻破坏系统，必须跳过）
RW_REAL=""
for d in $(awk '$4 ~ /(^|,)rw(,|$)/ {print $1}' /proc/mounts 2>/dev/null | sort -u); do
    r=$(realpath "$d" 2>/dev/null || readlink -f "$d" 2>/dev/null)
    [ -n "$r" ] && RW_REAL="$RW_REAL $r "
done

# 分区保护判定，优先级：EXTRA_SKIP > EXTRA_PROTECT > SKIP_DEFAULT > PROTECT_DEFAULT
want() { # $1=完整名 $2=基础名（去掉 _a/_b 后缀）
    case " ${EXTRA_SKIP:-} " in *" $1 "*|*" $2 "*) return 1 ;; esac
    case " ${EXTRA_PROTECT:-} " in *" $1 "*|*" $2 "*) return 0 ;; esac
    case " $SKIP_DEFAULT " in *" $1 "*|*" $2 "*) return 1 ;; esac
    case " $PROTECT_DEFAULT " in *" $1 "*|*" $2 "*) return 0 ;; esac
    return 1
}

COUNT=0
: > "$LIST"
: > "$LOG"

# by-name：物理分区；mapper：动态分区（system/vendor 等的 dm 设备）
for link in /dev/block/by-name/* /dev/block/mapper/*; do
    [ -e "$link" ] || continue
    name=${link##*/}
    base=${name%_a}; base=${base%_b}
    want "$name" "$base" || continue
    real=$(realpath "$link" 2>/dev/null || readlink -f "$link" 2>/dev/null)
    [ -n "$real" ] || continue
    case " $RW_REAL " in *" $real "*)
        echo "SKIP(读写挂载中): $name -> $real" >> "$LOG"; continue ;; esac
    if $RO "$real" >/dev/null 2>&1; then
        echo "$name $real" >> "$LIST"
    else
        echo "FAIL(加锁失败): $name -> $real" >> "$LOG"
        continue
    fi
    COUNT=$((COUNT+1))
done

# 看门狗（先清理旧实例再拉起，模块更新/重扫时自动接管）
pkill -f "antifmt/daemon.sh" 2>/dev/null
if [ "${WATCHDOG:-1}" = "1" ] && [ -s "$LIST" ]; then
    nohup sh "$MODDIR/daemon.sh" >/dev/null 2>&1 &
fi

# 把实际保护数量写进模块介绍（Magisk 模块页可见）
if [ "$COUNT" -gt 0 ]; then
    sed -i "s|^description=.*|description=全分区防格机保护运行中：已保护 $COUNT 个分区（引导/系统/密钥/校准），看门狗每 ${INTERVAL:-5} 秒自动重锁。OTA/刷机前请先关闭：config 设 ENABLED=0 并重启|" "$PROP"
else
    sed -i "s|^description=.*|description=未检测到可保护分区，请查看 $RUN/antifmt.log 反馈机型|" "$PROP"
fi
echo "[antifmt] 已保护 $COUNT 个分区（明细见 $LOG）"
