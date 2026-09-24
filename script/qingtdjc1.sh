BASEDIR="$(dirname $(readlink -f "$0"))"
. $BASEDIR/quanj.sh

WATCHED_FILE="/dev/cpuset/top-app/"
EXEC_SCRIPT="$BASEDIR/qtbh.sh"
cpuset "qingtdjc1.sh" "background"

while true; do
  /bin/inotifyd "$EXEC_SCRIPT" "$WATCHED_FILE":Mc
done