#!/system/bin/sh
# colorFC thermal control: fake temp injection + custom temp wall
# state dir survives module updates and form swaps
MODDIR=$(dirname "$(readlink -f "$0")")
TDIR=/data/adb/colorFC_thermal
LOG="$TDIR/thermal.log"
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
  [ -f "$TDIR/fake" ] || echo 1 > "$TDIR/fake" 2>/dev/null
  [ -f "$TDIR/wall" ] || echo "$WALL_MAX" > "$TDIR/wall" 2>/dev/null
}

get_real(){
  local t
  read t < /sys/class/power_supply/battery/temp 2>/dev/null
  case "$t" in ''|*[!0-9]*) echo "" ;; *) echo "$t" ;; esac
}

load_state(){
  read fake < "$TDIR/fake" 2>/dev/null
  [ "$fake" = "1" ] || fake=0
  read wall < "$TDIR/wall" 2>/dev/null
  case "$wall" in ''|*[!0-9]*) wall="" ;; esac
  if [ -n "$wall" ]; then
    [ "$wall" -gt "$WALL_MAX" ] && wall=$WALL_MAX
    [ "$wall" -lt "$WALL_MIN" ] && wall=$WALL_MIN
  fi
}

read_sys_wall(){
  local z t v mx m zone_wall global_wall
  zone_wall=""
  global_wall=""
  for z in /sys/class/thermal/thermal_zone*; do
    [ -d "$z" ] || continue
    mx=""
    for m in "$z"/trip_point*_temp; do
      [ -f "$m" ] || continue
      read v < "$m" 2>/dev/null
      case "$v" in ''|*[!0-9]*) continue ;; esac
      [ "$v" -ge 36000 ] && [ "$v" -le 95000 ] || continue
      if [ -z "$mx" ] || [ "$v" -gt "$mx" ]; then mx=$v; fi
    done
    [ -n "$mx" ] || continue
    read t < "$z/type" 2>/dev/null
    case "$t" in
      *batt*|*BATT*|*skin*|*Skin*|*shell*|*quiet*)
        if [ -z "$zone_wall" ] || [ "$mx" -gt "$zone_wall" ]; then zone_wall=$mx; fi
        ;;
    esac
    if [ -z "$global_wall" ] || [ "$mx" -gt "$global_wall" ]; then global_wall=$mx; fi
  done
  if [ -n "$zone_wall" ]; then echo $((zone_wall/1000)); return; fi
  if [ -n "$global_wall" ]; then echo $((global_wall/1000)); return; fi
  echo ""
}

apply_all(){
  local i=0
  while [ "$i" -le 7 ]; do
    echo "$i $1" > /proc/shell-temp 2>/dev/null
    i=$((i+1))
  done
}

tick_once(){
  local real mode
  load_state
  real=$(get_real)
  if [ "$fake" = "1" ]; then
    if [ -n "$wall" ] && [ -n "$real" ] && [ "$real" -ge $((wall*10)) ]; then
      mode="release"
      apply_all $((real*100))
    else
      mode="fake"
      apply_all "$FAKE_MS"
    fi
  else
    mode="off"
    [ -n "$real" ] && apply_all $((real*100))
  fi
  if [ "$mode" != "$LAST_MODE" ]; then
    echo "$mode" > "$TDIR/mode" 2>/dev/null
    LAST_MODE="$mode"
  fi
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
  log "温控守护启动 pid=$$ fake=$fake wall=${wall:-none}"
  log_trim
  LAST_MODE=""
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
    real=$(get_real)
    sys=$(read_sys_wall)
    if pgrep -f "thermal/thermal.sh daemon" >/dev/null 2>&1; then run=1; else run=0; fi
    printf '{"fake":%s,"wall":%s,"real":%s,"sys":%s,"run":%s}\n' \
      "$fake" "${wall:-$WALL_MAX}" "${real:-null}" "${sys:-null}" "$run"
    ;;
  set_fake)
    case "$2" in
      0|1) ;;
      *) echo "ERR: 用法 thermal.sh set_fake 0|1" >&2; exit 1 ;;
    esac
    init_defaults
    echo "$2" > "$TDIR/fake" 2>/dev/null
    LAST_MODE=""
    tick_once
    log "伪装温度切换为 $2"
    echo "OK"
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
    echo "$2" > "$TDIR/wall" 2>/dev/null
    LAST_MODE=""
    tick_once
    log "温度墙设置为 $2 ℃"
    echo "OK"
    ;;
  *)
    echo "用法: thermal.sh [daemon|status|set_fake 0|1|set_wall $WALL_MIN-$WALL_MAX]" >&2
    exit 1
    ;;
esac
