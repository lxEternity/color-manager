#!/system/bin/sh
MODDIR="${0%/*}"
[ "$MODDIR" = "." ] && MODDIR="$(pwd)"

ENC_STRATEGY_DIR="$MODDIR/encrypted_strategy"
DB_PATH="/data/data/com.oplus.cosa/databases/db_game_database"
[ ! -f "$DB_PATH" ] && DB_PATH="/data/user_de/0/com.oplus.cosa/databases/db_game_database"
LOG_FILE="$MODDIR/inject.log"
LOCK_DIR="$MODDIR/.inject_lock"
DEVICE_CONFIG="$MODDIR/device_config.json"

log_msg() {
  ts="$(date '+%Y-%m-%d %H:%M:%S')"
  echo "[$ts] $*" >> "$LOG_FILE"
  echo "[$ts] $*"
  log -t cosa_injector "$*" 2>/dev/null || true
}

cleanup() {
  rmdir "$LOCK_DIR" >/dev/null 2>&1
}

# 在候选路径列表中找到第一个可执行文件
find_bin() {
  for candidate in "$@"; do
    [ -z "$candidate" ] && continue
    [ -f "$candidate" ] || continue
    [ -x "$candidate" ] || chmod 0755 "$candidate" >/dev/null 2>&1
    [ -x "$candidate" ] && { printf "%s" "$candidate"; return 0; }
  done
  return 1
}

calc_local_bind_id() {
  printf "a4dbd57451750c196914468bab7b89c59e424767b804a6ff7e07c7df7afa2efe"
}

disable_feedback() {
  log_msg "正在关闭用户体验计划"
  settings put system oplus_customize_cta_user_experience 0
  if [ "$(settings get system oplus_customize_cta_user_experience)" -eq 0 ]; then
    log_msg "成功：已关闭用户体验计划"
  else
    log_msg "错误：未关闭用户体验计划"
  fi
}

# ── lock ──────────────────────────────────────────────────────────────────
if ! mkdir "$LOCK_DIR" >/dev/null 2>&1; then
  log_msg "提示：已有注入任务在执行，跳过本次运行"
  exit 0
fi
trap cleanup EXIT

# ── preflight checks ──────────────────────────────────────────────────────
if [ ! -f "$DB_PATH" ]; then
  log_msg "错误：数据库不存在（已尝试 /data 和 /data/user_de）"
  exit 1
fi

LOCAL_BIND_ID="$(calc_local_bind_id)"
if [ -z "$LOCAL_BIND_ID" ] || [ "${#LOCAL_BIND_ID}" -ne 64 ]; then
  log_msg "错误：无法计算本机设备绑定 ID"
  exit 1
fi

# ── detect chip subdir ────────────────────────────────────────────────────
# 注：customize.sh 安装时已将正确策略合并到 ENC_STRATEGY_DIR 根目录
# 此处检测逻辑：优先使用根目录（已合并），回退到子目录扫描
CHIP_SUBDIR=""

# 检查根目录是否已有 .enc 文件（customize.sh 合并后的结果）
if ls "$ENC_STRATEGY_DIR"/*.enc >/dev/null 2>&1; then
  ACTIVE_ENC_DIR="$ENC_STRATEGY_DIR"
  log_msg "使用已合并策略目录: $ACTIVE_ENC_DIR"
else
  # 回退：尝试 SoC 子目录匹配（兼容未经 customize.sh 安装的情况）
  SOC="$(getprop ro.soc.model 2>/dev/null)"
  [ -z "$SOC" ] && SOC="$(getprop ro.board.platform 2>/dev/null)"
  [ -z "$SOC" ] && SOC="$(getprop ro.hardware 2>/dev/null)"
  SOC_UPPER="$(echo "$SOC" | tr -cd '[:alnum:]' | tr '[:lower:]' '[:upper:]')"

  JQ_BIN=""
  for candidate in "$MODDIR/jq" "$MODDIR/jq-linux-arm64"; do
    [ -f "$candidate" ] || continue
    [ -x "$candidate" ] || chmod 0755 "$candidate"
    [ -x "$candidate" ] && { JQ_BIN="$candidate"; break; }
  done

  # 白名单检查
  DEVICE_MODEL="$(getprop ro.product.vendor.model 2>/dev/null)"
  PRJ_NAME_INJ="$(getprop ro.boot.prjname 2>/dev/null)"
  for lookup_key in "$DEVICE_MODEL" "$PRJ_NAME_INJ"; do
    [ -z "$lookup_key" ] && continue
    if [ -n "$JQ_BIN" ] && [ -f "$DEVICE_CONFIG" ]; then
      wl_path="$($JQ_BIN -r --arg k "$lookup_key" '
        .devices | to_entries[] |
        select((.value.models | if type=="array" then index($k) != null else . == $k end)) |
        .value.strategy_path
      ' "$DEVICE_CONFIG" 2>/dev/null | head -1)"
    elif [ -f "$DEVICE_CONFIG" ]; then
      wl_name="$(awk -v k="\"$lookup_key\"" '
        /^[[:space:]]*"[^"]+"[[:space:]]*:/ { devname = $0; gsub(/^[[:space:]]*"|".*/, "", devname) }
        $0 ~ k { print devname; exit }
      ' "$DEVICE_CONFIG")"
      [ -n "$wl_name" ] && wl_path="$(sed -n "/\"$wl_name\"/,/}/s/.*\"strategy_path\"[[:space:]]*:[[:space:]]*\"\([^\"]*\)\".*/\1/p" "$DEVICE_CONFIG" | head -1)"
    fi
    if [ -n "$wl_path" ] && [ -d "$ENC_STRATEGY_DIR/$wl_path" ]; then
      CHIP_SUBDIR="$wl_path"
      log_msg "白名单命中: $lookup_key -> $CHIP_SUBDIR"
      break
    fi
  done

  # 平台映射检查
  if [ -z "$CHIP_SUBDIR" ]; then
    if [ -n "$SOC_UPPER" ] && [ -d "$ENC_STRATEGY_DIR/$SOC_UPPER" ]; then
      CHIP_SUBDIR="$SOC_UPPER"
    elif [ -n "$JQ_BIN" ] && [ -f "$DEVICE_CONFIG" ]; then
      MAPPED="$($JQ_BIN -r ".platforms.\"$SOC_UPPER\" // empty" "$DEVICE_CONFIG" 2>/dev/null)"
      [ -n "$MAPPED" ] && [ -d "$ENC_STRATEGY_DIR/$MAPPED" ] && CHIP_SUBDIR="$MAPPED"
    elif [ -f "$DEVICE_CONFIG" ]; then
      MAPPED="$(sed -n "/\"platforms\"/,/^  }/s/.*\"$SOC_UPPER\"[[:space:]]*:[[:space:]]*\"\([^\"]*\)\".*/\1/p" "$DEVICE_CONFIG" | head -1)"
      [ -n "$MAPPED" ] && [ -d "$ENC_STRATEGY_DIR/$MAPPED" ] && CHIP_SUBDIR="$MAPPED"
    fi
  fi

  # 最终回退：扫描第一个含 .enc 的子目录
  if [ -z "$CHIP_SUBDIR" ]; then
    for d in "$ENC_STRATEGY_DIR"/*/; do
      [ -d "$d" ] || continue
      ls "$d"*.enc >/dev/null 2>&1 && { CHIP_SUBDIR="${d%/}"; CHIP_SUBDIR="${CHIP_SUBDIR##*/}"; break; }
    done
  fi

  if [ -n "$CHIP_SUBDIR" ]; then
    ACTIVE_ENC_DIR="$ENC_STRATEGY_DIR/$CHIP_SUBDIR"
  else
    ACTIVE_ENC_DIR="$ENC_STRATEGY_DIR"
  fi
fi

log_msg "使用加密策略目录: $ACTIVE_ENC_DIR"

# ── inject_core binary ────────────────────────────────────────────────────
INJECT_BIN="$(find_bin \
    "$MODDIR/inject_core" \
    "/data/adb/modules/cosa_injector/inject_core")"

if [ -z "$INJECT_BIN" ]; then
  log_msg "错误：未找到 inject_core 二进制文件，请确保已部署到模块目录"
  exit 1
fi

log_msg "使用二进制注入器: $INJECT_BIN"

MAP_ARG=""
[ -f "$DEVICE_CONFIG" ] && MAP_ARG="$DEVICE_CONFIG"

THREADS="$(nproc 2>/dev/null)"
[ -z "$THREADS" ] || [ "$THREADS" -lt 1 ] && THREADS=4
[ "$THREADS" -gt 16 ] && THREADS=16

# ── 获取运行时参数 ────────────────────────────────────────────────────────
COSA_VER=""
COSA_VER="$(dumpsys package com.oplus.cosa 2>/dev/null | grep versionName | head -1 | sed 's/.*versionName=//' | tr -d ' ')"
[ -z "$COSA_VER" ] && COSA_VER="$(pm dump com.oplus.cosa 2>/dev/null | grep versionName | head -1 | sed 's/.*versionName=//' | tr -d ' ')"

PRJ_NAME="$(getprop ro.boot.prjname 2>/dev/null)"
OTHER_VAL=""
[ -n "$PRJ_NAME" ] && OTHER_VAL="${PRJ_NAME}_false&"
[ -n "$PRJ_NAME" ] && log_msg "机型代号: $PRJ_NAME"

# 生成当前时间戳（设备本地时区）
MONTHS="Jan Feb Mar Apr May Jun Jul Aug Sep Oct Nov Dec"
_mon="$(date '+%m' | sed 's/^0//')"
_mon_name="$(echo "$MONTHS" | awk -v m="$_mon" '{print $m}')"
_day="$(date '+%d' | sed 's/^0//')"
_year="$(date '+%Y')"
_time="$(date '+%H:%M:%S')"
UPDATE_TIME="${_mon_name} ${_day}, ${_year} ${_time}"
log_msg "更新时间: $UPDATE_TIME"

# 使用临时文件传递退出码（mksh 不支持 PIPESTATUS）
BIN_RC_FILE="$MODDIR/.inject_rc"
( "$INJECT_BIN" "$LOCAL_BIND_ID" "$ACTIVE_ENC_DIR" "$DB_PATH" "$THREADS" "$MAP_ARG" "$COSA_VER" "$OTHER_VAL" "$UPDATE_TIME" 2>&1; echo $? > "$BIN_RC_FILE" ) | tee -a "$LOG_FILE"
BIN_RC="$(cat "$BIN_RC_FILE" 2>/dev/null)"
rm -f "$BIN_RC_FILE"
[ -z "$BIN_RC" ] && BIN_RC=1

if [ "$BIN_RC" -eq 0 ] || [ "$BIN_RC" -eq 2 ]; then
  log_msg "注入完成 (rc=$BIN_RC)"
else
  log_msg "错误：注入失败 (rc=$BIN_RC)"
  exit 1
fi

# ── post-inject tasks ─────────────────────────────────────────────────────
disable_feedback
cleanup
exit 0
