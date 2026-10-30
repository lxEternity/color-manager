#!/system/bin/sh
# colorFC thermal control
# - always inject fake 36C into /proc/shell-temp: lifts userspace thermal limits,
#   never hands back (system thermal would force-disable apps & kill background)
# - custom battery temp wall: written directly into kernel battery thermal zone
#   trip points; at the wall the kernel throttles by itself (freq control, not
#   the userspace app-freeze/kill behaviour)
# state dir survives module updates and form swaps
MODDIR=$(dirname "$(readlink -f "$0")")
TDIR=/data/adb/colorFC_thermal
LOG="$TDIR/thermal.log"
ORIG="$TDIR/trips_orig"
FAKE_MS=36000
WALL_MIN=36
WALL_MAX=55

log(){
  echo "[$(date '+%m-%d %H:%M:%S')] $*" >> "$LOG" 2>/dev/null
}

log_trim(){
  local sz
  sz=$(stat -c '%s' "$LOG" 2>/dev/null)
  case "$sz" in ''|*[!0-9]*) sz=0 ;; esac
  [ "$sz" -gt 32768 ] && : > "$LOG" 2>/dev/null
}

init_defaults(){
  mkdir -p "$TDIR" 2>/dev/null
  [ -f "$TDIR/wall" ] || echo "$WALL_MAX" > "$TDIR/wall" 2>/dev/null
}

get_real(){
  local t
  read t < /sys/class/power_supply/battery/temp 2>/dev/null
  case "$t" in ''|*[!0-9]*) echo "" ;; *) echo "$t" ;; esac
}

load_state(){
  read wall < "$TDIR/wall" 2>/dev/null
  case "$wall" in ''|*[!0-9]*) wall=$WALL_MAX ;; esac
  [ "$wall" -gt "$WALL_MAX" ] && wall=$WALL_MAX
  [ "$wall" -lt "$WALL_MIN" ] && wall=$WALL_MIN
}

is_batt_zone(){
  case "$1" in
    *batt*|*BATT*|*Battery*|*battery*) return 0 ;;
  esac
  return 1
}

# snapshot stock battery-zone trips once, before the first write (restore list)
snapshot_orig(){
  [ -f "$ORIG" ] && return
  local z t m v out=""
  for z in /sys/class/thermal/thermal_zone*; do
    [ -d "$z" ] || continue
    read t < "$z/type" 2>/dev/null
    is_batt_zone "$t" || continue
    for m in "$z"/trip_point*_temp; do
      [ -f "$m" ] || continue
      read v < "$m" 2>/dev/null
      case "$v" in ''|*[!0-9]*) continue ;; esac
      [ "$v" -ge 30000 ] && [ "$v" -le 90000 ] || continue
      out="$out$m=$v\n"
    done
  done
  [ -n "$out" ] && printf "$out" > "$ORIG" 2>/dev/null
}

apply_all(){
  local i=0
  while [ "$i" -le 7 ]; do
    echo "$i $1" > /proc/shell-temp 2>/dev/null
    i=$((i+1))
  done
}

# write the user wall into every non-critical battery-zone trip point,
# clamped below the zone's critical trip; re-applied every tick
apply_wall(){
  local z t tp ty v crit tgt
  for z in /sys/class/thermal/thermal_zone*; do
    [ -d "$z" ] || continue
    read t < "$z/type" 2>/dev/null
    is_batt_zone "$t" || continue
    crit=""
    for ty in "$z"/trip_point*_type; do
      [ -f "$ty" ] || continue
      read v < "$ty" 2>/dev/null
      [ "$v" = "critical" ] || continue
      tp="${ty%_type}_temp"
      [ -f "$tp" ] || continue
      read v < "$tp" 2>/dev/null
      case "$v" in ''|*[!0-9]*) continue ;; esac
      if [ -z "$crit" ] || [ "$v" -lt "$crit" ]; then crit=$v; fi
    done
    for tp in "$z"/trip_point*_temp; do
      [ -f "$tp" ] || continue
      ty="${tp%_temp}_type"
      v=""
      [ -f "$ty" ] && read v < "$ty" 2>/dev/null
      [ "$v" = "critical" ] && continue
      read v < "$tp" 2>/dev/null
      case "$v" in ''|*[!0-9]*) continue ;; esac
      [ "$v" -ge 30000 ] && [ "$v" -le 90000 ] || continue
      tgt=$((wall*1000))
      [ -n "$crit" ] && [ "$tgt" -ge "$crit" ] && tgt=$((crit-1000))
      [ "$v" = "$tgt" ] && continue
      chmod 644 "$tp" 2>/dev/null
      echo "$tgt" > "$tp" 2>/dev/null
    done
  done
}

tick_once(){
  load_state
  apply_all "$FAKE_MS"
  apply_wall
}

daemon_main(){
  local p
  for p in $(pgrep -f "thermal/thermal.sh daemon" 2>/dev/null); do
    [ "$p" = "$$" ] && continue
    log "守护已在运行 pid=$p，跳过"
    exit 0
  done
  init_defaults
  load_state
  snapshot_orig
  log "温控守护启动 pid=$$ wall=$wall（36℃注入不交还 + 内核电池温度墙）"
  log_trim
  while :; do
    tick_once
    log_trim
    sleep 2
  done
}

case "$1" in
  daemon)
    daemon_main
    ;;
  status)
    init_defaults
    load_state
    snapshot_orig
    real=$(get_real)
    if pgrep -f "thermal/thermal.sh daemon" >/dev/null 2>&1; then run=1; else run=0; fi
    printf '{"wall":%s,"real":%s,"run":%s}\n' \
      "$wall" "${real:-null}" "$run"
    ;;
  set_wall)
    case "$2" in
      ''|*[!0-9]*) echo "ERR: 用法 thermal.sh set_wall $WALL_MIN-$WALL_MAX" >&2; exit 1 ;;
    esac
    if [ "$2" -lt "$WALL_MIN" ] || [ "$2" -gt "$WALL_MAX" ]; then
      echo "ERR: 温度墙范围 ${WALL_MIN}-${WALL_MAX}℃" >&2
      exit 1
    fi
    init_defaults
    snapshot_orig
    echo "$2" > "$TDIR/wall" 2>/dev/null
    tick_once
    log "电池温度墙设置为 $2 ℃"
    echo "OK"
    ;;
  *)
    echo "用法: thermal.sh [daemon|status|set_wall $WALL_MIN-$WALL_MAX]" >&2
    exit 1
    ;;
esac
