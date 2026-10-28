#!/system/bin/sh
FC_DIR="$(cd "$(dirname "$0")" && pwd)"
LOG_FILE="$FC_DIR/inject.log"
MARK="$FC_DIR/.injected"

log_msg() {
  ts="$(date '+%Y-%m-%d %H:%M:%S')"
  echo "[$ts] $*" >> "$LOG_FILE"
}

wait_db_ready() {
  i=0
  while [ "$i" -lt 120 ]; do
    [ -f /data/data/com.oplus.cosa/databases/db_game_database ] && return 0
    [ -f /data_ce/data/com.oplus.cosa/databases/db_game_database ] && return 0
    [ -f /data/user_de/0/com.oplus.cosa/databases/db_game_database ] && return 0
    i=$((i + 1))
    sleep 2
  done
  return 1
}

COSA_VER="$(dumpsys package com.oplus.cosa 2>/dev/null | grep versionName | head -1 | sed 's/.*versionName=//' | tr -d ' ')"
[ -z "$COSA_VER" ] && COSA_VER="$(pm dump com.oplus.cosa 2>/dev/null | grep versionName | head -1 | sed 's/.*versionName=//' | tr -d ' ')"

[ "$(cat "$MARK" 2>/dev/null)" = "$COSA_VER" ] && exit 0

if ! wait_db_ready; then
  log_msg "cosA 数据库未就绪，放弃本次注入"
  exit 1
fi

am force-stop com.oplus.cosa >/dev/null 2>&1
sleep 1

sh "$FC_DIR/inject_cosa.sh" >> "$LOG_FILE" 2>&1
RC=$?

if [ "$RC" -eq 0 ]; then
  echo "$COSA_VER" > "$MARK"
  log_msg "注入成功，标记版本: $COSA_VER"
else
  log_msg "注入失败 rc=$RC（下台游戏进入时重试）"
fi
exit "$RC"
