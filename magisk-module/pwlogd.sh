STORE=/data/adb/colorFC_store/pwlog
PIDF=/data/adb/colorFC_store/pwlogd.pid
INTERVAL=2
KEEP_DAYS=7

if command -v pgrep >/dev/null 2>&1; then
    for p in $(pgrep -f pwlogd.sh 2>/dev/null); do
        [ "$p" = "$$" ] || kill "$p" 2>/dev/null
    done
else
    if [ -f "$PIDF" ]; then
        op=$(cat "$PIDF" 2>/dev/null)
        if [ -n "$op" ] && [ -r "/proc/$op/cmdline" ] \
            && grep -q pwlogd "/proc/$op/cmdline" 2>/dev/null; then
            kill "$op" 2>/dev/null
        fi
    fi
fi
sleep 1
mkdir -p "$STORE"
echo $$ > "$PIDF"

B=/sys/class/power_supply/battery

cleanup() {
    cutoff=$(date -d "-${KEEP_DAYS} days" +%Y%m%d 2>/dev/null) || return 0
    [ -n "$cutoff" ] || return 0
    for f in "$STORE"/pwlog-*.csv; do
        [ -e "$f" ] || continue
        fd=$(basename "$f" .csv); fd=${fd#pwlog-}
        case "$fd" in *[!0-9]*|"") continue;; esac
        [ "$fd" -lt "$cutoff" ] && rm -f "$f"
    done
}
cleanup

while true; do
    cur=$(cat $B/current_now 2>/dev/null)
    volt=$(cat $B/voltage_now 2>/dev/null)
    [ -n "$cur" ] && [ -n "$volt" ] && [ "$cur" != 0 ] && [ "$volt" != 0 ] && {
        now=$(date +%s)
        temp=$(cat $B/temp 2>/dev/null)
        cap=$(cat $B/capacity 2>/dev/null)
        st=$(cat $B/status 2>/dev/null)
        wv=$(awk -v c="$cur" -v v="$volt" 'BEGIN{
            a = c<0 ? -c : c; A = a>100000 ? c/1e6 : (a>1 ? c/1000 : c);
            b = v<0 ? -v : v; V = b>1000000 ? v/1e6 : (b>2500 ? v/1000 : v);
            w = A*V; if (w<0) w = -w;
            printf "%.2f,%.2f", w, V;
        }')
        tv=NA
        [ -n "$temp" ] && tv=$(awk -v t="$temp" 'BEGIN{
            if (t>600) t/=100; else if (t>60) t/=10;
            if (t>=0 && t<=90) printf "%.1f", t; else printf "NA";
        }')
        case "$st" in
            Charging*|Full|Not\ charg*) c=1;;
            *) c=0;;
        esac
        echo "$now,${wv},${tv},${cap:-NA},$c" >> "$STORE/pwlog-$(date +%Y%m%d).csv"
        [ $((now % 1800)) -lt $INTERVAL ] && cleanup
    }
    sleep $INTERVAL
done
