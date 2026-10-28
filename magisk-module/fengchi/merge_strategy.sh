#!/system/bin/sh
FC_DIR="$(cd "$(dirname "$0")" && pwd)"
TARGET_DIR="$FC_DIR/encrypted_strategy"
DEVICE_CONFIG="$FC_DIR/device_config.json"
JQ_BIN="$FC_DIR/jq"

say() { echo "  $*"; }

get_soc_model() {
  raw="$(getprop ro.soc.model 2>/dev/null)"
  [ -z "$raw" ] && raw="$(getprop ro.board.platform 2>/dev/null)"
  echo "$raw" | tr -cd '[:alnum:]' | tr '[:lower:]' '[:upper:]'
}

CURRENT_SOC="$(get_soc_model)"
DEVICE_MODEL="$(getprop ro.product.vendor.model 2>/dev/null)"
PRJ_NAME="$(getprop ro.boot.prjname 2>/dev/null)"
DEVICE_CODENAME="$(getprop ro.product.device 2>/dev/null)"
VENDOR_CODENAME="$(getprop ro.product.vendor.device 2>/dev/null)"
SDK_VERSION="$(getprop ro.build.version.sdk 2>/dev/null)"

WHITELIST_PATH=""
cosa_device_lookup() {
  key="$1"
  [ -z "$key" ] || [ ! -f "$DEVICE_CONFIG" ] && return
  found_name="$("$JQ_BIN" -r --arg k "$key" '
    .devices | to_entries[] |
    select((.value.models | if type=="array" then index($k) != null else . == $k end)) |
    .key' "$DEVICE_CONFIG" 2>/dev/null | head -1)"
  if [ -n "$found_name" ]; then
    echo "$("$JQ_BIN" -r ".devices.\"$found_name\".strategy_path" "$DEVICE_CONFIG" 2>/dev/null)"
  fi
}

for lookup_key in "$DEVICE_MODEL" "$PRJ_NAME" "$DEVICE_CODENAME" "$VENDOR_CODENAME"; do
  [ -z "$lookup_key" ] && continue
  hit="$(cosa_device_lookup "$lookup_key")"
  if [ -n "$hit" ] && [ -d "$TARGET_DIR/$hit" ]; then
    WHITELIST_PATH="$hit"
    say "风驰增强: 机型白名单命中 $lookup_key -> $hit"
    break
  fi
done

RESOLVED_SOC="$CURRENT_SOC"
if [ -f "$DEVICE_CONFIG" ]; then
  MAPPED_SOC="$("$JQ_BIN" -r ".platforms.\"$CURRENT_SOC\" // empty" "$DEVICE_CONFIG" 2>/dev/null)"
  if [ -n "$MAPPED_SOC" ] && [ -d "$TARGET_DIR/$MAPPED_SOC" ]; then
    RESOLVED_SOC="$MAPPED_SOC"
    say "风驰增强: 平台映射 $CURRENT_SOC -> $RESOLVED_SOC"
  elif [ -d "$TARGET_DIR/$CURRENT_SOC" ]; then
    RESOLVED_SOC="$CURRENT_SOC"
    say "风驰增强: 平台目录 $RESOLVED_SOC"
  fi
fi

STRATEGY_APPLIED=""
apply_strategy_layer() {
  layer="$1"
  [ -n "$layer" ] || return 0
  [ -d "$TARGET_DIR/$layer" ] || return 0
  for layer_file in "$TARGET_DIR/$layer"/*; do
    [ -f "$layer_file" ] || continue
    cp -f "$layer_file" "$TARGET_DIR/"
    STRATEGY_APPLIED=1
  done
}

apply_strategy_layer "$RESOLVED_SOC"
apply_strategy_layer "$WHITELIST_PATH"
apply_strategy_layer "$RESOLVED_SOC/sdk$SDK_VERSION"
apply_strategy_layer "$WHITELIST_PATH/sdk$SDK_VERSION"

[ -n "$STRATEGY_APPLIED" ] && say "风驰增强: 策略合并完成 ($RESOLVED_SOC $WHITELIST_PATH sdk$SDK_VERSION)" || say "风驰增强: 未匹配到本机策略（游戏接管=风驰增强时注入器会回退扫描）"

for dir in "$TARGET_DIR"/*/; do
  [ -d "$dir" ] && rm -rf "$dir"
done
exit 0
