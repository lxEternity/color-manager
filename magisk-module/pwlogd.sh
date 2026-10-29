#!/system/bin/sh
STORE=${PW_STORE:-/data/adb/colorFC_store/pwlog}
PIDF=${PW_PIDF:-/data/adb/colorFC_store/pwlogd.pid}
B=${PW_BATTERY:-/sys/class/power_supply/battery}
SNODE=${PW_SNODE:-}
IV_ON=${PW_IV_ON:-2}
IV_OFF=${PW_IV_OFF:-30}
KEEP_DAYS=7

if command -v pgrep >/dev/null 2>&1; then
    for p in $(pgrep -f pwlogd.sh 2>/dev/null); do
        [ "$p" = "$$" ] || kill "$p" 2>/dev/null
    done
else
    if [ -f "$PIDF" ]; then
        op=""
        read op < "$PIDF" 2>/dev/null
        if [ -n "$op" ] && [ -r "/proc/$op/cmdline" ] \
            && grep -q pwlogd "/proc/$op/cmdline" 2>/dev/null; then
            kill "$op" 2>/dev/null
        fi
    fi
fi
sleep 1
mkdir -p "$STORE"
echo $$ > "$PIDF"

if [ -z "$SNODE" ]; then
    for n in /sys/class/leds/lcd-backlight/brightness /sys/class/backlight/*/brightness; do
        [ -r "$n" ] && SNODE="$n" && break
    done
fi

screen_on() {
    [ -z "$SNODE" ] && return 0
    v=""
    [ -r "$SNODE" ] && read v < "$SNODE"
    [ -z "$v" ] && return 0
    [ "$v" != "0" ]
}

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
    cur=""
    [ -r $B/current_now ] && read cur < $B/current_now
    volt=""
    [ -r $B/voltage_now ] && read volt < $B/voltage_now
    if [ -n "$cur" ] && [ -n "$volt" ] && [ "$cur" != 0 ] && [ "$volt" != 0 ]; then
        now=$(date +%s)
        temp=""
        [ -r $B/temp ] && read temp < $B/temp
        cap=""
        [ -r $B/capacity ] && read cap < $B/capacity
        st=""
        [ -r $B/status ] && read st < $B/status
        wv=$(awk -v c="$cur" -v v="$volt" -v t="${temp:--999}" 'BEGIN{
            a = c<0 ? -c : c; A = a>100000 ? c/1e6 : (a>1 ? c/1000 : c);
            b = v<0 ? -v : v; V = b>1000000 ? v/1e6 : (b>2500 ? v/1000 : v);
            w = A*V; if (w<0) w = -w;
            if (t>600) t/=100; else if (t>60) t/=10;
            if (t>=0 && t<=90) tt=sprintf("%.1f", t); else tt="NA";
            printf "%.2f,%.2f,%s", w, V, tt;
        }')
        case "$st" in
            Charging*|Full|Not\ charg*) c=1;;
            *) c=0;;
        esac
        echo "$now,${wv},${cap:-NA},$c" >> "$STORE/pwlog-$(date +%Y%m%d).csv"
        [ $((now % 1800)) -lt $IV_ON ] && cleanup
    fi
    if screen_on; then
        sleep $IV_ON
    else
        sleep $IV_OFF
    fi
done
