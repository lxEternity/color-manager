#!/system/bin/sh

# By Ktwo
# 自适配动态限频守护进程 v1.2.0
# 常态按百分比锁定 CPU/GPU 上限，游戏前台自动放开。

if [ -z "$POWERD_MKSH" ]; then
  SECONDS=0
  sleep 1
  if [ "${SECONDS:-0}" -lt 1 ] 2>/dev/null; then
    export POWERD_MKSH=1
    exec /system/bin/sh "$0" "$@"
  fi
fi
SECONDS=0

MODDIR=${0%/*}
# 无路径前缀调用（如 cd 模块目录后 sh powerd.sh stop）时 ${0%/*} 等于 $0，回退当前目录
case "$MODDIR" in "$0") MODDIR=. ;; esac
[ -f "$MODDIR/powerd.conf" ] && . "$MODDIR/powerd.conf"
LOG_FILE="${LOG_FILE:-$MODDIR/powerd.log}"
LOG_MAX_BYTES="${LOG_MAX_BYTES:-262144}"
exec 2>>"$LOG_FILE"
STATE_FILE="$MODDIR/state"
LOCK_DIR="$MODDIR/powerd.lock"
GAME_LIST="$MODDIR/games.txt"
RUN_DIR="$MODDIR/run"            # 状态快照与 GPU 恢复记录
GPU_JOURNAL="$RUN_DIR/gpu.saved"
GAME_PERM_JOURNAL="$RUN_DIR/game_perms.saved"  # 游戏放权时的原始权限记录
STATUS_FILE="$RUN_DIR/status"
ORIG_GOVS_FILE="$RUN_DIR/orig_govs"  # 调速器原始值持久化（防 kill -9 丢失）
PAUSE_FILE="$MODDIR/pause"
GPU_RESULT=unknown
GPU_OK=0                     # detect_gpu 成功（有可写节点或 pwrlevel）才置 1
CPUFREQ="$SYSFS_PREFIX/sys/devices/system/cpu/cpufreq"

GPU=""
GPU_MIN=""
GPU_NORMAL=""
GPU_MAX_HZ=""
GPU_PCT="${GPU_PCT:-60}"

INTERVAL="${INTERVAL:-5}"
# ---- 游戏态变量（移植自 g750-boost）----
APPLIED_GAME_PKG=""
GAME_REENTER=0
GAME_PKG=""            # 当前前台游戏包名
GAME_PKGS=""           # games.txt 包名集合，带 mtime 缓存
GAME_PKGS_MTIME=""
GAME_PKGS_LOADED=""
GAME_MISSING_SINCE=""
GAME_LAST_SEEN=0       # 最后一次判定为游戏态的 $SECONDS
NOW_SEC=0              # 本轮 $SECONDS（单调秒）
# 游戏退出迟滞（秒）：前台检测暂失后保持解锁的时长，超时才复位
GAME_EXIT_GRACE_SEC="${GAME_EXIT_GRACE_SEC:-30}"
# 游戏进入后等待风驰调速器接管的检测时间（秒）；超时仍非风驰则写入默认游戏调速器
FENGCHI_DETECT_SEC="${FENGCHI_DETECT_SEC:-1}"

CPU_CAPS=""
WL_EFF=""
ORIG_CPU_MAXS=""
ORIG_GPU_MAX=""
ORIG_GPU_MIN=""
ORIG_GPU_PWRLEVEL=""
LOCK_OWNED=0

ORIG_GOVS=""
ORIG_GOVS_READY=0

# ---- 时间戳加速探测 ----
# mksh 的 printf '%(…)T' -1 可直接取当前时间，无需 fork date。
# 若当前 shell 不支持（理论上不会发生，兼容层已保证 mksh），回退到 date。
TS_FAST=0
_ts_probe=$(printf '%(%H:%M:%S)T' -1 2>/dev/null)
case "$_ts_probe" in
  *%*) TS_FAST=0 ;;
  ?*)  TS_FAST=1 ;;
esac
unset _ts_probe

ts_now() {
  if [ "$TS_FAST" = "1" ]; then
    printf '%(%Y-%m-%d %H:%M:%S)T' -1
  else
    date '+%Y-%m-%d %H:%M:%S'
  fi
}

LOG_COUNT=0
trim_log() {
  # 每 25 次 log 调用才检查一次文件大小，避免每次 fork wc/tail/awk
  LOG_COUNT=$((LOG_COUNT + 1))
  [ $((LOG_COUNT % 25)) -ne 1 ] && return 0
  local size
  [ -f "$LOG_FILE" ] || return 0
  size=$(wc -c < "$LOG_FILE" 2>/dev/null)
  case "$size" in ''|*[!0-9]*) return 0 ;; esac
  [ "$size" -le "$LOG_MAX_BYTES" ] && return 0
  # Drop the potentially partial first line; stderr must follow the new inode.
  if tail -c "$((LOG_MAX_BYTES / 2))" "$LOG_FILE" 2>/dev/null | \
     LC_ALL=C awk 'NR > 1' > "$LOG_FILE.tmp.$$" && mv "$LOG_FILE.tmp.$$" "$LOG_FILE"; then
    exec 2>>"$LOG_FILE"
  fi
}

log() {
  trim_log
  if [ "$TS_FAST" = "1" ]; then
    printf '[%(%H:%M:%S)T] %s\n' -1 "$*" >> "$LOG_FILE"
  else
    printf '[%s] %s\n' "$(date '+%H:%M:%S')" "$*" >> "$LOG_FILE"
  fi
}

validate_pct() {
  local name="$1" value="$2" fallback="$3"
  case "$value" in
    ''|*[!0-9]*) log "WARN: $name=$value 非法，使用默认值 $fallback"; eval "$name=$fallback"; return ;;
  esac
  if [ "$value" -lt 1 ] || [ "$value" -gt 100 ]; then
    log "WARN: $name=$value 超出 1..100，使用默认值 $fallback"
    eval "$name=$fallback"
  fi
}

validate_config() {
  validate_pct CPU_PCT_BIG "${CPU_PCT_BIG:-}" 58
  validate_pct CPU_PCT_MID2 "${CPU_PCT_MID2:-}" 60
  validate_pct CPU_PCT_MID "${CPU_PCT_MID:-}" 62
  validate_pct CPU_PCT_LITTLE "${CPU_PCT_LITTLE:-}" 65
  validate_pct GPU_PCT "${GPU_PCT:-}" 60
  case "$INTERVAL" in ''|*[!0-9]*) INTERVAL=5 ;; esac
  [ "$INTERVAL" -lt 2 ] && INTERVAL=2
  [ "$INTERVAL" -gt 60 ] && INTERVAL=60
  case "$GAME_EXIT_GRACE_SEC" in ''|*[!0-9]*) GAME_EXIT_GRACE_SEC=30 ;; esac
  [ "$GAME_EXIT_GRACE_SEC" -lt "$INTERVAL" ] && GAME_EXIT_GRACE_SEC=$INTERVAL
  [ "$GAME_EXIT_GRACE_SEC" -gt 300 ] && GAME_EXIT_GRACE_SEC=300
  case "$FENGCHI_DETECT_SEC" in ''|*[!0-9]*) FENGCHI_DETECT_SEC=1 ;; esac
  [ "$FENGCHI_DETECT_SEC" -gt 10 ] && FENGCHI_DETECT_SEC=10
}

acquire_lock() {
  if mkdir "$LOCK_DIR" 2>/dev/null; then
    printf '%s\n' "$$" > "$LOCK_DIR/pid"
    LOCK_OWNED=1
    return 0
  fi
  local owner
  owner=$(cat "$LOCK_DIR/pid" 2>/dev/null)
  if [ -n "$owner" ] && [ -d "/proc/$owner" ]; then
    echo "powerd already running pid=$owner" >&2
    return 1
  fi
  rm -rf "$LOCK_DIR" 2>/dev/null
  mkdir "$LOCK_DIR" 2>/dev/null || return 1
  printf '%s\n' "$$" > "$LOCK_DIR/pid"
  LOCK_OWNED=1
}

release_lock() {
  [ "$LOCK_OWNED" = "1" ] || return 0
  if [ "$(cat "$LOCK_DIR/pid" 2>/dev/null)" = "$$" ]; then
    rm -f "$LOCK_DIR/pid"
    rmdir "$LOCK_DIR" 2>/dev/null || true
  fi
  LOCK_OWNED=0
}

validate_hw_gpu_max() {
  local table_max="$1"
  case "$HW_GPU_MAX" in ''|*[!0-9]*) HW_GPU_MAX=""; return ;; esac
  if [ "$HW_GPU_MAX" -gt $(awk -v t="$table_max" 'BEGIN{printf "%.0f", t*120/100}') ] || \
     [ "$HW_GPU_MAX" -lt $(awk -v t="$table_max" 'BEGIN{printf "%.0f", t*80/100}') ]; then
    log "WARN: 配置 GPU max=$HW_GPU_MAX 与实机=$table_max 不符，改用实机值"
    HW_GPU_MAX=""
  fi
}

filter_hw_caps() {
  local item pol freq actual filtered=""
  for item in $HW_CAPS; do
    pol=${item%:*}; freq=${item##*:}
    [ -e "$CPUFREQ/$pol/cpuinfo_max_freq" ] || {
      log "WARN: 配置 policy $pol 不存在，跳过"
      continue
    }
    case "$freq" in ''|0|*[!0-9]*) continue ;; esac
    actual=$(cat "$CPUFREQ/$pol/cpuinfo_max_freq" 2>/dev/null)
    case "$actual" in ''|0|*[!0-9]*)
      log "WARN: $pol 硬件最高频不可用，跳过配置覆盖"
      continue ;;
    esac
    if [ "$freq" -ne "$actual" ]; then
      log "WARN: $pol 配置最高频=$freq 与实机=$actual 不符，改用实机值"
      continue
    fi
    filtered="$filtered $pol:$freq"
  done
  HW_CAPS="${filtered# }"
}

snap_freq() {
  # 将期望封顶值吸附到频点表上。只向下取最接近且不超过 want 的档位：
  # 封顶语义要求实际值永远 <= 预期，向上吸附会让限频名存实亡。
  local pol="$1" want="$2" tbl f best=0
  case "$want" in ''|0|*[!0-9]*) echo "$want"; return ;; esac
  tbl="$CPUFREQ/$pol/scaling_available_frequencies"
  if [ -r "$tbl" ]; then
    for f in $(cat "$tbl" 2>/dev/null); do
      case "$f" in ''|*[!0-9]*) continue ;; esac
      [ "$f" -le "$want" ] && [ "$f" -gt "$best" ] && best=$f
    done
  fi
  [ "$best" -gt 0 ] && echo "$best" || echo "$want"
}

# ------------------- GPU 探测（高通 + MTK 双支持） -------------------
detect_gpu() {
  local avail f gpumax gpumax_is_mhz=0
  GPU=""; GPU_MIN=""; GPU_NORMAL=""; GPU_IS_MHZ=0; GPU_MIN_IS_MHZ=0
  GPU_FREQS=""; GPU_PWRLEVEL=""; GPU_PWRLEVEL_NORMAL=""
  GPU_MAX_HZ=""
  GPU_MIN_HZ=""
  GPU_PLATFORM=""

  # 频率表探测：高通优先（Hz 或 freq_table_mhz），之后 MTK Mali devfreq
  for avail in \
    "/sys/class/kgsl/kgsl-3d0/gpu_available_frequencies" \
    "/sys/class/devfreq/3d00000.qcom,kgsl-3d0/available_frequencies" \
    "/sys/class/devfreq/5000000.qcom,kgsl-3d0/available_frequencies" \
    "/sys/class/kgsl/kgsl-3d0/devfreq/available_frequencies" \
    "/sys/class/devfreq/48000000.mali/available_frequencies" \
    "/sys/class/devfreq/13000000.mali/available_frequencies" \
    "/sys/class/devfreq/mtk-mali/available_frequencies" \
    "/sys/class/devfreq/gpufreq/available_frequencies" \
    "/sys/class/devfreq/mali0/available_frequencies" \
    "/sys/class/misc/mali0/device/devfreq/available_frequencies" \
    "/sys/devices/platform/soc/13000000.mali/devfreq/available_frequencies" \
    "/sys/devices/platform/13000000.mali/devfreq/available_frequencies" \
    "/sys/class/devfreq/3d00000.qcom,kgsl-3d0/freq_table_mhz" \
    "/sys/class/devfreq/5000000.qcom,kgsl-3d0/freq_table_mhz" \
    "/sys/class/kgsl/kgsl-3d0/freq_table_mhz"; do
    GPU_FREQS=$(cat "$avail" 2>/dev/null | tr ' ' '\n' | grep -E '^[0-9]+$')
    [ -n "$GPU_FREQS" ] || continue
    case "$avail" in *freq_table_mhz) gpumax_is_mhz=1 ;; *) gpumax_is_mhz=0 ;; esac
    break
  done
  [ -n "$GPU_FREQS" ] || { log "WARN: 未找到 GPU 可用频率表，跳过 GPU 限频"; return 1; }

  if [ "$gpumax_is_mhz" = "1" ]; then
    GPU_FREQS=$(printf '%s\n' $GPU_FREQS | awk '{printf "%.0f\n", $1 * 1000000}')
  else
    # 自动判定 Hz / MHz：最大频 < 10000 视为 MHz
    local _mx
    _mx=$(printf '%s\n' $GPU_FREQS | sort -rn | head -1)
    if [ -n "$_mx" ] && [ "$_mx" -lt 10000 ]; then
      GPU_FREQS=$(printf '%s\n' $GPU_FREQS | awk '{printf "%.0f\n", $1 * 1000000}')
    fi
  fi
  GPU_FREQS=$(printf '%s\n' "$GPU_FREQS" | grep -E '^[0-9]+$')
  gpumax=$(printf '%s\n' $GPU_FREQS | sort -rn | head -1)
  GPU_MAX_HZ=$gpumax
  GPU_MIN_HZ=$(printf '%s\n' "$GPU_FREQS" | sort -n | head -1)
  validate_hw_gpu_max "$gpumax"

  GPU_NORMAL=$(awk -v m="$gpumax" -v p="${GPU_PCT:-60}" 'BEGIN{printf "%.0f", m*p/100}')
  [ -n "$HW_GPU_MAX" ] && GPU_NORMAL=$(awk -v m="$HW_GPU_MAX" -v p="${GPU_PCT:-60}" 'BEGIN{printf "%.0f", m*p/100}')

  local gpu_best=0
  for f in $GPU_FREQS; do
    [ "$f" -le "$GPU_NORMAL" ] && [ "$f" -gt "$gpu_best" ] && gpu_best=$f
  done
  [ "$gpu_best" -gt 0 ] && GPU_NORMAL=$gpu_best

  # pwrlevel 仅高通 KGSL 有
  local idx=0 pwrlvl=0
  if [ -e "/sys/class/kgsl/kgsl-3d0/max_pwrlevel" ]; then
    for f in $(printf '%s\n' $GPU_FREQS | sort -rn); do
      [ "$f" -le "$GPU_NORMAL" ] && { pwrlvl=$idx; break; }
      idx=$(( idx + 1 ))
    done
    GPU_PWRLEVEL_NORMAL=$pwrlvl
    GPU_PWRLEVEL="/sys/class/kgsl/kgsl-3d0/max_pwrlevel"
  fi

  # 只写明确的频率上限节点；GED boost/索引接口语义不统一，保持只读。
  for avail in $(gpu_max_nodes); do
    [ -e "$avail" ] || continue
    GPU="$avail"
    case "$avail" in
      */gpu_max_clock|*/max_clock_mhz|*/custom_boost_gpu_freq|*/custom_upbound_gpu_freq) GPU_IS_MHZ=1 ;;
      *) GPU_IS_MHZ=0 ;;
    esac
    break
  done

  case "$GPU" in
    *kgsl*) GPU_PLATFORM="QC" ;;
    *mali*|*ged*|*gpufreq*) GPU_PLATFORM="MTK" ;;
    *) GPU_PLATFORM="?" ;;
  esac

  # GPU_NORMAL_WRITE 已移除：write_gpu 会按节点单位自行换算，避免两处换算逻辑漂移

  if [ -e "/sys/kernel/gpu/gpu_min_clock" ]; then
    GPU_MIN="/sys/kernel/gpu/gpu_min_clock"
    GPU_MIN_IS_MHZ=1
  else
    case "$GPU" in
      */max_gpuclk)    GPU_MIN="${GPU%max_gpuclk}min_gpuclk" ;;
      */max_freq)      GPU_MIN="${GPU%max_freq}min_freq" ;;
      */gpu_max_clock) GPU_MIN="${GPU%gpu_max_clock}gpu_min_clock" ;;
      */max_clock_mhz) GPU_MIN="${GPU%max_clock_mhz}min_clock_mhz" ;;
      *)               GPU_MIN="" ;;
    esac
    case "$GPU_MIN" in
      */gpu_min_clock|*/min_clock_mhz) GPU_MIN_IS_MHZ=1 ;;
      *)                              GPU_MIN_IS_MHZ=0 ;;
    esac
    [ -e "$GPU_MIN" ] || GPU_MIN=""
  fi

  if [ -n "$GPU" ]; then
    log "GPU 探测[$GPU_PLATFORM]: $GPU max=${HW_GPU_MAX:-$gpumax} 封顶=${GPU_NORMAL}Hz min=${GPU_MIN:-?} 最低档=${GPU_MIN_HZ:-?}$([ -n "$GPU_PWRLEVEL" ] && echo " pwrlevel=${GPU_PWRLEVEL_NORMAL}(${GPU_PCT:-60}%)")$([ -n "$HW_GPU_MAX" ] && echo " [conf]")"
  elif [ -n "$GPU_PWRLEVEL" ]; then
    log "GPU 探测: 仅 pwrlevel 可用 max=${HW_GPU_MAX:-$gpumax} 封顶level=${GPU_PWRLEVEL_NORMAL}"
  else
    log "WARN: 未找到可写 GPU 节点，跳过 GPU 限频"
    return 1
  fi
  GPU_OK=1
  return 0
}

detect_cpu_caps() {
  local p maxf caps="" freqs="" n tier pct cap pol eff item
  local hw_map=""
  filter_hw_caps
  for item in $HW_CAPS; do hw_map="$hw_map $item"; done

  for p in "$CPUFREQ"/policy*/cpuinfo_max_freq; do
    [ -f "$p" ] || continue
    pol=${p%/cpuinfo_max_freq}; pol=${pol##*/}
    maxf=$(cat "$p" 2>/dev/null)
    case "$maxf" in ''|*[!0-9]*) continue ;; esac
    local hw_override=""
    for item in $hw_map; do
      [ "${item%:*}" = "$pol" ] && hw_override="${item##*:}" && break
    done
    [ -n "$hw_override" ] && maxf=$hw_override
    freqs="$freqs $maxf"
  done

  [ -z "$freqs" ] && return 1
  freqs=$(printf '%s\n' $freqs | sort -nu | tr '\n' ' ')
  n=0
  for f in $freqs; do n=$((n + 1)); done

  for p in "$CPUFREQ"/policy*/cpuinfo_max_freq; do
    [ -f "$p" ] || continue
    pol=${p%/cpuinfo_max_freq}; pol=${pol##*/}
    maxf=$(cat "$p" 2>/dev/null)
    case "$maxf" in ''|*[!0-9]*) continue ;; esac
    for item in $hw_map; do
      [ "${item%:*}" = "$pol" ] && maxf="${item##*:}" && break
    done
    tier=0
    for f in $freqs; do [ "$f" = "$maxf" ] && break; tier=$((tier+1)); done
    if [ "$tier" -eq $((n-1)) ]; then
      pct="${CPU_PCT_BIG:-58}"
    elif [ "$tier" -eq 0 ] && [ "$n" -gt 1 ]; then
      pct="${CPU_PCT_LITTLE:-65}"
    elif [ "$n" -ge 4 ] && [ "$tier" -eq $((n-2)) ]; then
      pct="${CPU_PCT_MID2:-60}"
    else
      pct="${CPU_PCT_MID:-62}"
    fi
    cap=$(( maxf * pct / 100 ))
    case "$cap" in ''|0|*[!0-9]*) log "WARN: $pol cap=$cap 异常，跳过"; continue ;; esac
    cap=$(snap_freq "$pol" "$cap")
    eff=$(awk -v c="$cap" -v m="$maxf" 'BEGIN{printf "%.1f", c*100/m}')
    log "  $pol tier=$tier/${n} hw=$maxf 意图=${pct}% 吸附=$cap 实际=${eff}%$([ -n "$HW_CAPS" ] && echo " [conf]")"
    caps="$caps $pol:$cap"
  done
  CPU_CAPS="${caps# }"
  log "CPU 探测: $CPU_CAPS"
}

write_lock() {
  local node="$1" val="$2" lock="${3:-1}"
  WL_EFF=""
  [ -e "$node" ] || return 1
  chmod 644 "$node" 2>/dev/null
  printf '%s\n' "$val" > "$node" 2>/dev/null
  local got
  got=$(cat "$node" 2>/dev/null)
  [ -n "$got" ] || { log "WARN write_lock: 回读为空 node=$node"; return 1; }
  WL_EFF="$got"
  [ "$got" = "$val" ] && { log "  write_lock OK $node=$val"; return 0; }
  case "$got$val" in
    *[!0-9]*) log "WARN write_lock: 非数字 want=$val got=$got node=$node"; return 1 ;;
  esac
  [ "$got" -lt "$val" ] && { log "  write_lock OK(clamped) $node want=$val got=$got"; return 0; }
  log "WARN write_lock: 被拒 want=$val got=$got node=$node"
  return 1
}

gpu_max_nodes() {
  printf '%s\n' \
    /sys/class/devfreq/3d00000.qcom,kgsl-3d0/max_freq \
    /sys/class/devfreq/5000000.qcom,kgsl-3d0/max_freq \
    /sys/class/devfreq/2c00000.qcom,kgsl-3d0/max_freq \
    /sys/class/kgsl/kgsl-3d0/devfreq/max_freq \
    /sys/class/kgsl/kgsl-3d0/max_gpuclk \
    /sys/class/kgsl/kgsl-3d0/max_clock_mhz \
    /sys/class/devfreq/13000000.mali/max_freq \
    /sys/class/devfreq/mtk-mali/max_freq \
    /sys/class/devfreq/mali0/max_freq \
    /sys/class/devfreq/gpufreq/max_freq \
    /sys/class/misc/mali0/device/devfreq/max_freq \
    /sys/devices/platform/soc/13000000.mali/devfreq/max_freq \
    /sys/devices/platform/13000000.mali/devfreq/max_freq \
    /sys/class/devfreq/48000000.mali/max_freq \
    /sys/kernel/gpu/gpu_max_clock
}

node_write() {
  chmod 644 "$1" 2>/dev/null || return 1
  printf '%s\n' "$2" > "$1" 2>/dev/null
}

# Journal before any write attempt, retaining raw values and original permissions.
# A failed write may have side effects; keep its snapshot too. No GPU_MIN snapshot
# is restored unless a write was actually attempted during this or an interrupted run.
gpu_write_node() {
  local node="$1" value="$2" path raw mode found=0
  [ -e "$node" ] || return 1
  if [ -f "$GPU_JOURNAL" ]; then
    while read -r path raw mode; do
      [ "$path" != "$node" ] || { found=1; break; }
    done < "$GPU_JOURNAL"
  fi
  if [ "$found" = 0 ]; then
    raw=$(cat "$node" 2>/dev/null)
    mode=$(stat -c '%a' "$node" 2>/dev/null)
    case "$raw" in ''|*[!0-9]*) return 1 ;; esac
    case "$mode" in ''|*[!0-7]*) mode=644 ;; esac
    printf '%s %s %s\n' "$node" "$raw" "$mode" >> "$GPU_JOURNAL" || return 1
  fi
  node_write "$node" "$value"
}

restore_gpu_nodes() {
  local node raw mode got failed=0
  [ -f "$GPU_JOURNAL" ] || return 0
  while read -r node raw mode; do
    if node_write "$node" "$raw"; then
      got=$(cat "$node" 2>/dev/null)
      [ "$got" = "$raw" ] || failed=1
    else
      failed=1
    fi
    chmod "$mode" "$node" 2>/dev/null || failed=1
  done < "$GPU_JOURNAL"
  if [ "$failed" = 0 ]; then
    rm -f "$GPU_JOURNAL"
  else
    log 'WARN: GPU 恢复未完成，保留逐节点快照，下次启动先重试恢复'
    return 1
  fi
}

# ---- 游戏放权：权限交换 ----
# 进入游戏时，把 CPU/GPU/调速器相关节点的写权限交给系统（666），
# 让厂商框架（perfd / fpsgo / ged 等）能真正接管调频；
# 退出游戏或停止守护时，按 journal 恢复原始权限。
loosen_node_perm() {
  local node="$1" mode="${2:-666}" path old found=0
  [ -e "$node" ] || return 1
  if [ -f "$GAME_PERM_JOURNAL" ]; then
    while read -r path old; do
      [ "$path" = "$node" ] && { found=1; break; }
    done < "$GAME_PERM_JOURNAL"
  fi
  if [ "$found" = "0" ]; then
    old=$(stat -c '%a' "$node" 2>/dev/null)
    case "$old" in ''|*[!0-7]*) old=644 ;; esac
    printf '%s %s\n' "$node" "$old" >> "$GAME_PERM_JOURNAL" 2>/dev/null || return 1
  fi
  chmod "$mode" "$node" 2>/dev/null
}

restore_game_perms() {
  local node old restored=0
  [ -f "$GAME_PERM_JOURNAL" ] || return 0
  while read -r node old; do
    [ -e "$node" ] || continue
    chmod "$old" "$node" 2>/dev/null && restored=$((restored + 1))
  done < "$GAME_PERM_JOURNAL"
  rm -f "$GAME_PERM_JOURNAL"
  [ "$restored" -gt 0 ] && log "  游戏放权已收回（恢复 $restored 个节点权限）"
  return 0
}

loosen_game_perms() {
  local node
  # CPU：上限 / 下限 / 调速器
  for node in "$CPUFREQ"/policy*/scaling_max_freq \
              "$CPUFREQ"/policy*/scaling_min_freq \
              "$CPUFREQ"/policy*/scaling_governor; do
    [ -e "$node" ] && loosen_node_perm "$node" 666
  done
  # GPU：已探测节点 + 所有候选上限节点 + pwrlevel
  for node in "$GPU" "$GPU_MIN" "$GPU_PWRLEVEL"; do
    [ -n "$node" ] && [ -e "$node" ] && loosen_node_perm "$node" 666
  done
  for node in $(gpu_max_nodes); do
    [ -e "$node" ] && loosen_node_perm "$node" 666
  done
  log "  游戏放权：CPU/GPU/调速器节点权限已交由系统接管"
}

write_gpu() {
  local want_hz="$1" intent="${2:-cap}" node val got
  GPU_RESULT=failed
  case "$want_hz" in ''|0|*[!0-9]*) return 1 ;; esac
  for node in $(gpu_max_nodes); do
    [ -e "$node" ] || continue
    case "$node" in
      */gpu_max_clock|*/max_clock_mhz) val=$((want_hz / 1000000)) ;;
      *) val=$want_hz ;;
    esac
    gpu_write_node "$node" "$val" || continue
    got=$(cat "$node" 2>/dev/null)
    case "$got" in ''|0|*[!0-9]*) continue ;; esac
    GPU="$node"
    case "$node" in
      */gpu_max_clock|*/max_clock_mhz) GPU_IS_MHZ=1; got=$((got * 1000000)) ;;
      *) GPU_IS_MHZ=0 ;;
    esac
    if [ "$intent" = unlock ]; then
      if [ "$got" = "$want_hz" ]; then
        GPU_RESULT=unlocked
        return 0
      fi
      # A successful but clamped write may be a thermal/vendor restriction.
      # Do not hop to another path or pwrlevel to try bypassing that restriction.
      GPU_RESULT=restricted
      log "WARN: GPU 解锁受限 node=$node want=$want_hz got=$got"
      return 1
    fi
    if [ "$got" -le "$want_hz" ]; then
      GPU_RESULT=capped
      return 0
    fi
  done
  # pwrlevel is an index, not a frequency. Verify it independently.
  if [ -n "$GPU_PWRLEVEL" ] && [ -n "$GPU_PWRLEVEL_NORMAL" ]; then
    val=$GPU_PWRLEVEL_NORMAL
    [ "$intent" != unlock ] || val=0
    if gpu_write_node "$GPU_PWRLEVEL" "$val"; then
      got=$(cat "$GPU_PWRLEVEL" 2>/dev/null)
      if [ "$got" = "$val" ]; then
        GPU_RESULT=pwrlevel_applied
        return 0
      fi
    fi
  fi
  log "WARN: GPU 写入失败 want=$want_hz intent=$intent"
  return 1
}

snapshot_governors() {
  local pol gov node
  ORIG_GOVS=""
  for node in "$CPUFREQ"/policy*/scaling_governor; do
    [ -f "$node" ] || continue
    pol=${node%/scaling_governor}; pol=${pol##*/}
    gov=$(cat "$node" 2>/dev/null)
    [ -n "$gov" ] || continue
    ORIG_GOVS="$ORIG_GOVS $pol:$gov"
    log "  snapshot $pol 原始调速器=$gov"
  done
  ORIG_GOVS="${ORIG_GOVS# }"
  ORIG_GOVS_READY=1
  # 持久化一份：进程被 kill -9 时内存快照丢失，下次启动可据此先恢复
  mkdir -p "$RUN_DIR" 2>/dev/null
  printf '%s\n' "$ORIG_GOVS" > "$ORIG_GOVS_FILE" 2>/dev/null
}

# 上次异常退出（kill -9 / 崩溃）后，凭持久化快照尽力恢复调速器；
# 随后 snapshot_governors 会基于恢复后的状态重新快照。
restore_governors_from_file() {
  local item pol orig got
  [ -f "$ORIG_GOVS_FILE" ] || return 0
  while read -r item; do
    pol=${item%:*}; orig=${item##*:}
    [ -n "$pol" ] && [ -n "$orig" ] && [ "$pol" != "$item" ] || continue
    [ -f "$CPUFREQ/$pol/scaling_governor" ] || continue
    chmod 644 "$CPUFREQ/$pol/scaling_governor" 2>/dev/null
    printf '%s\n' "$orig" > "$CPUFREQ/$pol/scaling_governor" 2>/dev/null
    got=$(cat "$CPUFREQ/$pol/scaling_governor" 2>/dev/null)
    [ "$got" = "$orig" ] \
      && log "  $pol 调速器恢复(异常退出) -> $orig" \
      || log "  WARN: $pol 调速器恢复失败 want=$orig got=$got"
  done < "$ORIG_GOVS_FILE"
}

apply_governor() {
  local pol="$1" avail cur_gov got
  [ -n "$CPU_GOVERNOR" ] || return 0
  [ "$ORIG_GOVS_READY" = "1" ] || return 0
  [ -f "$CPUFREQ/$pol/scaling_governor" ] || return 0
  cur_gov=$(cat "$CPUFREQ/$pol/scaling_governor" 2>/dev/null)
  [ "$cur_gov" = "$CPU_GOVERNOR" ] && return 0
  case "$cur_gov" in
    hmbird*|scx*|vivo*|mtk*) log "  $pol 厂商调速器 $cur_gov，跳过"; return 0 ;;
  esac
  avail=$(cat "$CPUFREQ/$pol/scaling_available_governors" 2>/dev/null)
  case " $avail " in
    *" $CPU_GOVERNOR "*)
      chmod 644 "$CPUFREQ/$pol/scaling_governor" 2>/dev/null
      printf '%s\n' "$CPU_GOVERNOR" > "$CPUFREQ/$pol/scaling_governor" 2>/dev/null
      got=$(cat "$CPUFREQ/$pol/scaling_governor" 2>/dev/null)
      [ "$got" = "$CPU_GOVERNOR" ] \
        && log "  $pol 调速器 -> $CPU_GOVERNOR" \
        || log "  WARN: $pol 调速器写入失败 want=$CPU_GOVERNOR got=$got"
      ;;
    *) log "  WARN: $pol 不支持调速器 $CPU_GOVERNOR (可用: $avail)" ;;
  esac
}

restore_governor() {
  local item pol orig got
  [ -n "$ORIG_GOVS" ] || { log "  WARN: restore_governor ORIG_GOVS 为空，跳过"; return 0; }
  for item in $ORIG_GOVS; do
    pol=${item%:*}; orig=${item##*:}
    [ -n "$orig" ] || { log "  WARN: $pol 原始调速器为空，跳过"; continue; }
    [ -f "$CPUFREQ/$pol/scaling_governor" ] || continue
    chmod 644 "$CPUFREQ/$pol/scaling_governor" 2>/dev/null
    printf '%s\n' "$orig" > "$CPUFREQ/$pol/scaling_governor" 2>/dev/null
    got=$(cat "$CPUFREQ/$pol/scaling_governor" 2>/dev/null)
    [ "$got" = "$orig" ] \
      && log "  $pol 调速器恢复 -> $orig" \
      || log "  WARN: $pol 调速器恢复失败 want=$orig got=$got"
  done
}

apply_cpu_caps() {
  local item pol cap
  for item in $CPU_CAPS; do
    pol=${item%:*}; cap=${item##*:}
    write_lock "$CPUFREQ/$pol/scaling_max_freq" "$cap"
    [ -n "$WL_EFF" ] && [ "$WL_EFF" != "$cap" ] && \
      log "  $pol 请求=$cap 实际=$WL_EFF (厂商压得更低, 本档不起作用)"
    [ "$ORIG_GOVS_READY" = "1" ] && apply_governor "$pol"
  done
}

snapshot_limits() {
  local item pol cap node val hw
  ORIG_CPU_MAXS=""
  for item in $CPU_CAPS; do
    pol=${item%:*}; cap=${item##*:}
    node="$CPUFREQ/$pol/scaling_max_freq"
    val=$(cat "$node" 2>/dev/null)
    case "$val" in ''|*[!0-9]*) continue ;; esac
    # 崩溃重启保护：当前值恰等于本模块封顶值时，是上次被中断运行残留的限制
    #（GPU 侧已有等价的 restore_gpu_nodes 先行恢复）；改用硬件最高频作为原始值，
    # 否则 stop/restore 永远无法恢复真实频率
    if [ "$val" = "$cap" ]; then
      hw=$(cat "$CPUFREQ/$pol/cpuinfo_max_freq" 2>/dev/null)
      case "$hw" in ''|*[!0-9]*) ;; *) val=$(snap_freq "$pol" "$hw") ;; esac
    fi
    ORIG_CPU_MAXS="$ORIG_CPU_MAXS $pol:$val"
  done
  ORIG_CPU_MAXS="${ORIG_CPU_MAXS# }"

  [ -n "$GPU" ] && ORIG_GPU_MAX=$(cat "$GPU" 2>/dev/null)
  [ -n "$GPU_MIN" ] && ORIG_GPU_MIN=$(cat "$GPU_MIN" 2>/dev/null)
  [ -n "$GPU_PWRLEVEL" ] && ORIG_GPU_PWRLEVEL=$(cat "$GPU_PWRLEVEL" 2>/dev/null)
  local gpu_min_unit="Hz" gpu_min_norm="?"
  if [ "$GPU_MIN_IS_MHZ" = "1" ]; then
    gpu_min_unit="MHz"
    case "$ORIG_GPU_MIN" in ''|*[!0-9]*) ;; *) gpu_min_norm=$(( ORIG_GPU_MIN * 1000000 )) ;; esac
  else
    case "$ORIG_GPU_MIN" in ''|*[!0-9]*) ;; *) gpu_min_norm=$ORIG_GPU_MIN ;; esac
  fi
  log "原始限制快照: CPU=$ORIG_CPU_MAXS GPU_MAX=${ORIG_GPU_MAX:-?} GPU_MIN_RAW=${ORIG_GPU_MIN:-?} GPU_MIN_UNIT=$gpu_min_unit GPU_MIN_HZ=$gpu_min_norm PWRLEVEL=${ORIG_GPU_PWRLEVEL:-?}"
}

restore_cpu_caps() {
  local item pol orig node
  for item in $ORIG_CPU_MAXS; do
    pol=${item%:*}; orig=${item##*:}
    node="$CPUFREQ/$pol/scaling_max_freq"
    [ -e "$node" ] || continue
    chmod 644 "$node" 2>/dev/null
    printf '%s\n' "$orig" > "$node" 2>/dev/null
    chmod 644 "$CPUFREQ/$pol/scaling_governor" 2>/dev/null
  done
}

unlock_cpu_game() {
  local node pol hw got
  for node in "$CPUFREQ"/policy*/cpuinfo_max_freq; do
    [ -r "$node" ] || continue
    pol=${node%/cpuinfo_max_freq}; pol=${pol##*/}
    hw=$(cat "$node" 2>/dev/null)
    case "$hw" in ''|*[!0-9]*) continue ;; esac
    node="$CPUFREQ/$pol/scaling_max_freq"
    [ -e "$node" ] || continue
    hw=$(snap_freq "$pol" "$hw")
    chmod 644 "$node" 2>/dev/null
    printf '%s\n' "$hw" > "$node" 2>/dev/null
    got=$(cat "$node" 2>/dev/null)
    if [ "$got" = "$hw" ] || { [ -n "$got" ] && [ "$got" -gt "$hw" ]; }; then
      log "  $pol 游戏 CPU 解锁 -> hw=$hw got=$got"
    else
      log "  WARN: $pol 游戏 CPU 解锁失败 want=$hw got=${got:-?}"
    fi
  done
}

# 风驰调速器检测：只认 hmbird / scx（真正的风驰调度器）。
# 非风驰 → 由 apply_game 快速切换为默认游戏调速器。
is_fengchi_governor() {
  local pol gov
  for pol in $(printf '%s\n' $CPU_CAPS | tr ' ' '\n' | sed 's/:.*//'); do
    [ -f "$CPUFREQ/$pol/scaling_governor" ] || continue
    gov=$(cat "$CPUFREQ/$pol/scaling_governor" 2>/dev/null)
    case "$gov" in
      hmbird*|scx*) return 0 ;;
    esac
  done
  return 1
}

apply_game_fallback_governor() {
  local pol avail cur got target
  case "$SOC" in
    mt*) set -- sugov_ext walt schedutil performance ;;
    sm*|*) set -- walt sugov_ext schedutil performance ;;
  esac

  for pol in $(printf '%s\n' $CPU_CAPS | tr ' ' '\n' | sed 's/:.*//'); do
    [ -f "$CPUFREQ/$pol/scaling_governor" ] || continue
    cur=$(cat "$CPUFREQ/$pol/scaling_governor" 2>/dev/null)
    case "$cur" in
      hmbird*|scx*) log "  $pol 风驰调速器 $cur，跳过"; continue ;;
    esac

    target=""
    avail=$(cat "$CPUFREQ/$pol/scaling_available_governors" 2>/dev/null)
    for g in "$@"; do
      case " $avail " in *" $g "*) target=$g; break ;; esac
    done
    [ -n "$target" ] || { log "  WARN: $pol 无可用游戏调速器 (SoC=$SOC 可用: $avail)"; continue; }
    [ "$cur" = "$target" ] && continue
    chmod 644 "$CPUFREQ/$pol/scaling_governor" 2>/dev/null
    printf '%s\n' "$target" > "$CPUFREQ/$pol/scaling_governor" 2>/dev/null
    got=$(cat "$CPUFREQ/$pol/scaling_governor" 2>/dev/null)
    [ "$got" = "$target" ] \
      && log "  $pol 游戏调速器 -> $target" \
      || log "  WARN: $pol 游戏调速器写入失败 want=$target got=$got"
  done
}

# 将 GPU_MIN 写回最低档（按节点单位自动换算 raw 值）；调用方自行判断 GPU_BOOST_CONTROL
gpu_min_to_floor() {
  local want
  [ -n "$GPU_MIN" ] && [ -e "$GPU_MIN" ] && [ -n "$GPU_MIN_HZ" ] || return 1
  if [ "$GPU_MIN_IS_MHZ" = "1" ]; then
    want=$(( GPU_MIN_HZ / 1000000 ))
  else
    want=$GPU_MIN_HZ
  fi
  gpu_write_node "$GPU_MIN" "$want"
}

patrol_gpu_min() {
  [ "${GPU_BOOST_CONTROL:-0}" = "1" ] || return 0
  [ -n "$GPU_MIN" ] && [ -e "$GPU_MIN" ] && [ -n "$GPU_MIN_HZ" ] || return 0
  local cur norm raw_after after
  cur=$(cat "$GPU_MIN" 2>/dev/null)
  case "$cur" in ''|*[!0-9]*) return 0 ;; esac
  if [ "$GPU_MIN_IS_MHZ" = "1" ]; then norm=$(( cur * 1000000 )); else norm=$cur; fi
  [ "$norm" -gt "$GPU_MIN_HZ" ] || return 0
  gpu_min_to_floor || return 1
  raw_after=$(cat "$GPU_MIN" 2>/dev/null)
  case "$raw_after" in ''|*[!0-9]*) return 1 ;; esac
  if [ "$GPU_MIN_IS_MHZ" = "1" ]; then after=$(( raw_after * 1000000 )); else after=$raw_after; fi
  [ "$after" -lt "$norm" ] && \
    log "RESTORE GPU_MIN Boost: raw=$raw_after normalized=${norm}Hz -> target=${GPU_MIN_HZ}Hz"
}

patrol_cpu_caps() {
  local item pol cap cur state
  state=$(cat "$STATE_FILE" 2>/dev/null)
  for item in $CPU_CAPS; do
    pol=${item%:*}; cap=${item##*:}
    cur=$(cat "$CPUFREQ/$pol/scaling_max_freq" 2>/dev/null)
    case "$cur" in ''|*[!0-9]*) continue ;; esac
    if [ "$state" != "game" ]; then
      if [ "$cur" -gt "$cap" ]; then
        log "RESTORE $pol: $cur -> $cap"
        write_lock "$CPUFREQ/$pol/scaling_max_freq" "$cap"
      fi
      apply_governor "$pol"
    fi
  done
}

apply_normal() {
  local prev
  prev=$(cat "$STATE_FILE" 2>/dev/null)
  if [ "$prev" != "normal" ]; then
    log "→ 常态 GPU=${GPU_NORMAL} CPU: $CPU_CAPS"
    # 收权：先恢复游戏期间交给系统的节点权限，再施加常态限制
    restore_game_perms
    if [ "${GPU_BOOST_CONTROL:-0}" = "1" ] && [ "$prev" = "game" ]; then
      gpu_min_to_floor 2>/dev/null
    fi
    apply_cpu_caps
    # 无可写 GPU 节点时不再空写，避免每次切换都刷一条 WARN
    [ "$GPU_OK" = "1" ] && write_gpu "$GPU_NORMAL"
    printf 'normal\n' > "$STATE_FILE"
  else
    patrol_cpu_caps
    if [ -n "$GPU" ]; then
      local cur_gpu
      cur_gpu=$(cat "$GPU" 2>/dev/null)
      case "$cur_gpu" in ''|*[!0-9]*) ;; *)
        case "$GPU" in
          */gpu_max_clock|*/max_clock_mhz|*/custom_boost_gpu_freq|*/custom_upbound_gpu_freq) cur_gpu=$(( cur_gpu * 1000000 )) ;;
        esac
        [ "$cur_gpu" -gt "$GPU_NORMAL" ] && write_gpu "$GPU_NORMAL"
      ;; esac
    fi
    patrol_gpu_min
  fi
}

apply_game() {
  if [ "$(cat "$STATE_FILE" 2>/dev/null)" = "game" ] &&
     [ "$APPLIED_GAME_PKG" = "$GAME_PKG" ] && [ "$GAME_REENTER" = "0" ]; then
    return 0
  fi
  APPLIED_GAME_PKG=$GAME_PKG
  GAME_REENTER=0
  log "  游戏前台进入: $GAME_PKG"
  log "→ 游戏解锁到硬件最高频率 GPU_MAX=${GPU_MAX_HZ:-?}Hz GPU_MIN=${GPU_MIN_HZ:-?}Hz，交还系统接管"

  unlock_cpu_game
  if [ "$GPU_OK" = "1" ] && [ -n "$GPU_MAX_HZ" ]; then
    write_gpu "$GPU_MAX_HZ" unlock
  fi
  if [ -n "$GPU_PWRLEVEL" ]; then
    gpu_write_node "$GPU_PWRLEVEL" 0 || log 'WARN: 游戏 GPU pwrlevel 写入失败'
  fi
  if [ "${GPU_BOOST_CONTROL:-0}" = "1" ] && [ -n "$GPU_MIN" ] && [ -n "$GPU_MIN_HZ" ]; then
    local game_min_write
    if [ "$GPU_MIN_IS_MHZ" = "1" ]; then
      game_min_write=$(( GPU_MIN_HZ / 1000000 ))
    else
      game_min_write=$GPU_MIN_HZ
    fi
    gpu_write_node "$GPU_MIN" "$game_min_write"
    log "  游戏 GPU_MIN -> raw=$game_min_write normalized=${GPU_MIN_HZ}Hz"
  fi
  # 权限交换：先解锁频率值，再把节点写权限交给系统，让厂商框架真正接管
  loosen_game_perms

  printf 'game\n' > "$STATE_FILE"

  # 风驰接管检测等待（之前 3 秒，现默认 1 秒，可用 FENGCHI_DETECT_SEC 调整）：
  # 给系统调度器一个短暂的接管窗口；超时仍非风驰则快速写入默认游戏调速器
  sleep "$FENGCHI_DETECT_SEC"
  is_game || { log "  游戏检测暂失，交由主循环统一宽限"; return; }
  if is_fengchi_governor; then
    log "  风驰调速器已接管，不干预"
  else
    log "  非风驰调速器，快速切换为默认游戏调速器"
    apply_game_fallback_governor
  fi
}

# ============ 游戏判定：前台窗口 + 退出宽限 ============
# 每次判定执行一次 timeout 2 dumpsys window，由 awk 解析焦点。
# 游戏进程留在后台不作为命中依据；窗口暂失由退出宽限处理。

# games.txt -> GAME_PKGS（去注释 / 空行 / 重复 / CR）
# 带 mtime 缓存：文件未变化时直接复用，避免每 5 秒重解析一次；
# 修改后 mtime 变化即重新加载（保持原有“修改后立即生效”语义）。
CR=$(printf '\r')
load_game_pkgs() {
  local mtime _p
  mtime=$(stat -c '%Y' "$GAME_LIST" 2>/dev/null)
  if [ -n "$GAME_PKGS_LOADED" ] && [ -n "$mtime" ] && \
     [ "$mtime" = "$GAME_PKGS_MTIME" ]; then
    return 0
  fi
  GAME_PKGS=""
  if [ -f "$GAME_LIST" ]; then
    while IFS= read -r _p || [ -n "$_p" ]; do
      _p=${_p%$CR}
      case "$_p" in
        \#*|"") continue ;;
      esac
      case " $GAME_PKGS " in
        *" $_p "*) continue ;;
      esac
      GAME_PKGS="$GAME_PKGS $_p"
    done < "$GAME_LIST"
  fi
  GAME_PKGS="${GAME_PKGS# }"
  GAME_PKGS_MTIME=$mtime
  GAME_PKGS_LOADED=1
}

game_exit_due() {
  if [ -z "$GAME_MISSING_SINCE" ]; then
    GAME_MISSING_SINCE=$NOW_SEC
    return 1
  fi
  [ $((NOW_SEC - GAME_MISSING_SINCE)) -ge "$GAME_EXIT_GRACE_SEC" ]
}

publish_status() {
  local tmp="$STATUS_FILE.tmp.$$"
  {
    printf 'state=%s\n' "$(cat "$STATE_FILE" 2>/dev/null || echo unknown)"
    # Preserve legacy status keys without reporting stale process evidence.
    printf 'game_proc=none\n'
    printf 'game_pkg=%s\n' "${GAME_PKG:-}"
    printf 'game_pid=\n'
    printf 'last_seen=%s\n' "${GAME_LAST_SEEN:-}"
    printf 'gpu_result=%s\n' "${GPU_RESULT:-unknown}"
  } > "$tmp" 2>/dev/null && mv -f "$tmp" "$STATUS_FILE" 2>/dev/null
}

collect_foreground_apps() {
  # One streaming parser; preserve first-focus and overlay precedence.
  FOREGROUND_APPS=$({ timeout 2 dumpsys window 2>/dev/null || :; } | awk '
    function package_of(line) {
      if (!match(line, / u[0-9]+ [a-zA-Z0-9._]+\//)) return ""
      line = substr(line, RSTART, RLENGTH)
      sub(/^ u[0-9]+ /, "", line)
      sub(/\/$/, "", line)
      return line
    }
    /mCurrentFocus=/ && !have_focus { focus = package_of($0); have_focus = 1 }
    /mFocusedApp=/ && !have_app { app = package_of($0); have_app = 1 }
    /mTopFullscreenOpaqueWindowState=/ && !have_top { top = package_of($0); have_top = 1 }
    END {
      result = have_focus ? focus : app
      if (result ~ /^(com\.omarea\.vtools|bin\.mt\.plus|bin\.mt\.plus\.canary)$/ && top != "") result = top
      print result
    }
  ')
}

is_game() {
  local app previous="$GAME_PKG"
  GAME_PKG=""
  load_game_pkgs
  collect_foreground_apps
  for app in $FOREGROUND_APPS; do
    case " $GAME_PKGS " in
      *" $app "*) GAME_PKG=$app ;;
    esac
    [ -n "$GAME_PKG" ] || continue
    GAME_MISSING_SINCE=""
    return 0
  done
  # Record the observation here, including the delayed governor recheck.
  NOW_SEC=$SECONDS
  [ -n "$GAME_MISSING_SINCE" ] || GAME_MISSING_SINCE=$NOW_SEC
  [ -z "$previous" ] || log "  游戏离开前台: $previous，开始退出宽限"
  GAME_REENTER=1
  return 1
}

cleanup() {
  [ "$LOCK_OWNED" = "1" ] || return 0
  log "停止，恢复模块启动前状态"
  restore_governor
  restore_cpu_caps
  restore_game_perms
  restore_gpu_nodes
  rm -f "$STATE_FILE" "$STATUS_FILE" "$ORIG_GOVS_FILE"
  release_lock
}

main() {
  acquire_lock || return 1
  : > "$LOG_FILE"
  log "===== powerd start pid=$$ $(ts_now) ====="
  # TERM/INT 必须显式 exit：否则 cleanup 跑完会继续主循环
  #（旧版 trap cleanup EXIT INT TERM 会让 stop/kill 形同虚设 → 多实例抢写频率）
  trap 'cleanup' EXIT
  trap 'exit 0' INT TERM
  validate_config
  # 启动依赖检查：缺工具时给出明确 WARN，而不是静默行为异常
  for _tool in awk timeout dumpsys stat; do
    command -v "$_tool" >/dev/null 2>&1 || log "WARN: 缺少工具 $_tool，部分功能可能受限"
  done

  i=0
  while [ "$(getprop sys.boot_completed 2>/dev/null)" != "1" ]; do
    sleep 5; i=$((i+5)); [ "$i" -ge 120 ] && break
  done
  sleep 3

  # 与 powerd.conf 一致：优先 ro.soc.model（如 sm8850/mt6991），回退 ro.board.platform
  SOC=$(getprop ro.soc.model 2>/dev/null | tr 'A-Z' 'a-z')
  [ -n "$SOC" ] || SOC=$(getprop ro.board.platform 2>/dev/null)

  detect_cpu_caps
  detect_gpu
  [ -z "$CPU_CAPS" ] && log "WARN: 未探测到 cpufreq policy"
  # Recover interrupted GPU writes before taking the new baseline snapshot.
  restore_gpu_nodes || log "WARN: 启动时 GPU 快照恢复失败，继续但保留恢复日志"

  # Foreground windows control mode; no process-event monitor is started.
  mkdir -p "$RUN_DIR" 2>/dev/null
  # 上次若被 kill -9，内存中的调速器快照已丢失，先按持久化快照恢复
  restore_governors_from_file
  load_game_pkgs
  log "游戏判定: 前台窗口 白名单=$(printf '%s' "$GAME_PKGS" | wc -w) 个包 迟滞=${GAME_EXIT_GRACE_SEC}s"

  snapshot_governors
  snapshot_limits
  log "GPU=$GPU  检测间隔=日常${INTERVAL}s/游戏$((INTERVAL*2))s"

  rm -f "$STATE_FILE"
  apply_normal

  while :; do
    NOW_SEC=$SECONDS
    if is_game; then
      GAME_MISSING_SINCE=""
      GAME_LAST_SEEN=$NOW_SEC
      apply_game
      [ "$(cat "$STATE_FILE" 2>/dev/null)" = "game" ] && patrol_gpu_min
      publish_status
      # GAME_MISSING_SINCE 在本分支入口已清空，游戏态固定用 2 倍间隔巡检
      sleep $(( INTERVAL * 2 ))
    else
      if [ "$(cat "$STATE_FILE" 2>/dev/null)" = "game" ]; then
        # 精确迟滞:$SECONDS 单调秒,0 fork
        if ! game_exit_due; then
          elapsed=$(( NOW_SEC - GAME_MISSING_SINCE ))
          log "  游戏离开前台，宽限 ${elapsed}/${GAME_EXIT_GRACE_SEC}s，保持解锁"
          sleep "$INTERVAL"
          continue
        fi
        GAME_LAST_SEEN=0
        log "  游戏已离开前台（首次检测丢失后 $((NOW_SEC - GAME_MISSING_SINCE))s），复位"
        apply_normal
      else
        apply_normal
      fi
      publish_status
      sleep "$INTERVAL"
    fi
  done
}

case "${1:-start}" in
  start)
    [ -f "$PAUSE_FILE" ] && { echo "paused (remove $PAUSE_FILE to resume)"; exit 0; }
    main
    ;;
  stop)
    : > "$PAUSE_FILE"
    pid=$(cat "$LOCK_DIR/pid" 2>/dev/null)
    case "$pid" in ''|*[!0-9]*) echo "not running" ;; *)
      [ -d "/proc/$pid" ] && kill "$pid" && echo "stopped" || echo "not running"
    esac
    ;;
  restore)
    : > "$PAUSE_FILE"
    pid=$(cat "$LOCK_DIR/pid" 2>/dev/null)
    case "$pid" in ''|*[!0-9]*) echo "not running" ;; *)
      if [ -d "/proc/$pid" ]; then kill -TERM "$pid" 2>/dev/null; echo "restore requested"; else rm -rf "$LOCK_DIR"; echo "not running"; fi
    esac
    ;;
  status)
    if [ -f "$STATUS_FILE" ]; then
      cat "$STATUS_FILE"
    else
      printf 'state=%s\n' "$(cat "$STATE_FILE" 2>/dev/null || echo unknown)"
      printf 'game_proc=none\n'
      printf 'game_pkg=\n'
      printf 'game_pid=\n'
      printf 'last_seen=\n'
      printf 'gpu_result=unknown\n'
    fi
    ;;
  probe)
    echo "=== cpufreq policies ==="
    for p in "$CPUFREQ"/policy*/cpuinfo_max_freq; do
      [ -f "$p" ] || continue
      d=${p%/cpuinfo_max_freq}
      printf "  %-10s hw_max=%-10s cur_max=%s\n" "${d##*/}" \
        "$(cat "$p" 2>/dev/null)" "$(cat "$d/scaling_max_freq" 2>/dev/null)"
    done
    echo "=== 将要应用的封顶(按频点表吸附后) ==="
    detect_cpu_caps >/dev/null 2>&1
    for item in $CPU_CAPS; do
      printf "  %-10s cap=%s\n" "${item%:*}" "${item##*:}"
    done
    echo "=== GPU ==="
    detect_gpu >/dev/null 2>&1
    echo "  platform=$GPU_PLATFORM"
    echo "  node=$GPU"
    echo "  max=$(cat "$GPU" 2>/dev/null)"
    [ -n "$GPU_MIN" ] && echo "  min=$(cat "$GPU_MIN" 2>/dev/null) (unit=$([ "$GPU_MIN_IS_MHZ" = "1" ] && echo MHz || echo Hz))"
    echo "  freqs(hz)=$GPU_FREQS"
    echo "  max_hz=$GPU_MAX_HZ min_hz=$GPU_MIN_HZ normal=$GPU_NORMAL"
    ;;
  gpu-probe)
    echo "=== MTK / Mali GPU 节点探测 ==="
    echo "=== boost / FPS controls are diagnostic only; units and semantics unverified ==="
    for p in \
      /sys/class/devfreq/13000000.mali/available_frequencies \
      /sys/class/devfreq/13000000.mali/max_freq \
      /sys/class/devfreq/13000000.mali/min_freq \
      /sys/class/devfreq/13000000.mali/cur_freq \
      /sys/class/devfreq/13000000.mali/governor \
      /sys/class/devfreq/mtk-mali/available_frequencies \
      /sys/class/devfreq/gpufreq/available_frequencies \
      /sys/class/misc/mali0/device/devfreq/available_frequencies \
      /sys/devices/platform/soc/13000000.mali/devfreq/available_frequencies \
      /sys/kernel/ged/hal/custom_boost_gpu_freq \
      /sys/kernel/ged/hal/custom_upbound_gpu_freq \
      /sys/kernel/ged/hal/gpu_freq \
      /sys/kernel/ged/hal/gpu_utilization \
      /proc/gpufreqv2/gpu_working_opp_table \
      /proc/gpufreqv2/fix_target_opp_index \
      /proc/gpufreqv2/stack_opp_table \
      /proc/gpufreqv2/fix_fps_cap \
      /proc/gpufreqv2/gpu_boost \
      /proc/gpufreq/gpufreq_boost \
      /proc/gpufreq/gpufreq_opp_freq \
      /proc/gpufreq/gpufreq_opp_dump \
      /sys/class/kgsl/kgsl-3d0/freq_table_mhz \
      /sys/class/kgsl/kgsl-3d0/max_clock_mhz \
      /sys/class/kgsl/kgsl-3d0/max_gpuclk; do
      if [ -e "$p" ]; then
        v=$(cat "$p" 2>/dev/null | tr -s '[:space:]' ' ' | head -c 300)
        printf "  %s  %s = %s\n" "$([ -w "$p" ] && echo RW || echo RO)" "$p" "${v:-<empty>}"
      else
        printf "  MISS  %s\n" "$p"
      fi
    done
    ;;
  *)  echo "用法: powerd.sh [start|stop|restore|status|probe|gpu-probe]" ;;
esac
#By Ktwo
