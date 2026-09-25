#!/system/bin/sh
MODDIR=$1
RUN_DIR=$2
LOG=$3

[ -n "$MODDIR" ] && [ -n "$RUN_DIR" ] && [ -n "$LOG" ] || exit 1
mkdir -p "$RUN_DIR" 2>/dev/null
GAME_PROC="$RUN_DIR/game_proc"

# ---- 单实例保护：已有存活实例则直接退出（防止重启累积）----
MPIDF="$RUN_DIR/monitor.pid"
if [ -f "$MPIDF" ]; then
  _old=$(cat "$MPIDF" 2>/dev/null)
  case "$_old" in
    ''|*[!0-9]*) ;;
    *)
      if [ "$_old" != "$$" ] && [ -d "/proc/$_old" ]; then
        exit 0
      fi
      ;;
  esac
fi
echo $$ > "$MPIDF" 2>/dev/null

trap 'exit 0' TERM INT

log() { echo "$(date '+%m-%d %H:%M:%S') [monitor] $1" >> "$LOG" 2>/dev/null; }

is_game_pkg() {
  [ -n "$1" ] || return 1
  [ -f "$MODDIR/games.txt" ] || return 1
  while IFS= read -r p || [ -n "$p" ]; do
    case "$p" in
      \#*|"") continue ;;
    esac
    [ "$1" = "$p" ] && return 0
  done < "$MODDIR/games.txt"
  return 1
}

# 进程刚创建瞬间 cmdline 可能尚未就绪，最多重试 1 秒
wait_target_cmdline() {
  pkg=$1
  pid=$2
  retry=0
  while [ "$retry" -lt 20 ]; do
    [ -d "/proc/$pid" ] || return 1
    cmdline=""
    IFS= read -r -d '' cmdline < "/proc/$pid/cmdline" 2>/dev/null
    if [ "$cmdline" = "$pkg" ]; then
      proc_state=""
      read -r proc_state < "/proc/$pid/stat" 2>/dev/null
      proc_state=${proc_state##*) }
      case "${proc_state%% *}" in
        ""|Z|z) return 1 ;;
        *) return 0 ;;
      esac
    fi
    usleep 50000 2>/dev/null || sleep 0.05
    retry=$((retry + 1))
  done
  return 1
}

handle_start() {
  is_game_pkg "$1" || return 0
  wait_target_cmdline "$1" "$2" || return 0
  printf '%s %s\n' "$1" "$2" > "$GAME_PROC.tmp"
  mv -f "$GAME_PROC.tmp" "$GAME_PROC"
  log "PROC START $1 $2"
}

handle_died() {
  is_game_pkg "$1" || return 0
  # 只有当前缓存的游戏进程退出时才清状态（防重复事件/子进程误清）
  _cur=""
  read -r _cur < "$GAME_PROC" 2>/dev/null
  [ "$_cur" = "$1 $2" ] && rm -f "$GAME_PROC"
  log "PROC DIED $1 $2"
}

logcat -b events -v brief -s am_proc_start:I -s am_proc_died:I 2>/dev/null | while IFS= read -r line; do
  [ -f "$MODDIR/disable" ] && exit 0
  [ -f "$MODDIR/remove" ] && exit 0

  case "$line" in
    *am_proc_start*)
      # 格式: [user,pid,uid,process,hostingType,...]
      e_pid=$(printf '%s\n' "$line" | sed -n 's/.*\[[^,]*,\([0-9][0-9]*\),[^,]*,\([^,]*\),.*/\1/p')
      e_proc=$(printf '%s\n' "$line" | sed -n 's/.*\[[^,]*,[^,]*,[^,]*,\([^,]*\),.*/\1/p')
      [ -n "$e_pid" ] && [ -n "$e_proc" ] || continue
      handle_start "$e_proc" "$e_pid"
      ;;
    *am_proc_died*)
      # 格式: [user,pid,process,oom_score,proc_state]
      d_pid=$(printf '%s\n' "$line" | sed -n 's/.*\[[^,]*,\([0-9][0-9]*\),\([^,]*\),.*/\1/p')
      d_proc=$(printf '%s\n' "$line" | sed -n 's/.*\[[^,]*,\([0-9][0-9]*\),\([^,]*\),.*/\2/p')
      [ -n "$d_pid" ] && [ -n "$d_proc" ] || continue
      handle_died "$d_proc" "$d_pid"
      ;;
  esac
done