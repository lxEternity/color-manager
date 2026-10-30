#!/system/bin/sh

MODDIR=${0%/*}
MODDIR=${MODDIR%/webroot}
CONF="$MODDIR/powerd.conf"
GAMES="$MODDIR/games.txt"
RUN_DIR="$MODDIR/run"
LOG_FILE=$(sed -n 's/^LOG_FILE=//p' "$CONF" 2>/dev/null | head -1)
[ -z "$LOG_FILE" ] && LOG_FILE="$MODDIR/powerd.log"

err() { echo "ERR: $1" >&2; exit 1; }

API_LOG="$RUN_DIR/api.log"
_log_api() {
  local sz
  [ -d "$RUN_DIR" ] || mkdir -p "$RUN_DIR" 2>/dev/null
  sz=$(stat -c '%s' "$API_LOG" 2>/dev/null)
  case "$sz" in ''|*[!0-9]*) sz=0 ;; esac
  [ "$sz" -gt 65536 ] && : > "$API_LOG" 2>/dev/null
  printf '%s %s\n' "$(date +%H:%M:%S)" "$*" >> "$API_LOG" 2>/dev/null
}

jesc() {
  printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g' | tr '\n\t' '  '
}

valid_pkg() {
  case "$1" in ''|*[!a-zA-Z0-9._]*) return 1 ;; esac
  return 0
}

get_label() {
  local p="$1" line lab
  line=$(dumpsys package "$p" 2>/dev/null | grep -m1 -i 'application-label')
  if [ -z "$line" ]; then
    line=$(pm dump "$p" 2>/dev/null | grep -m1 -i 'application-label')
  fi
  lab=$(printf '%s' "$line" | sed "s/.*application-label['\" :=]*//" | sed "s/^['\"]//; s/['\"]\$//")
  [ -z "$lab" ] && lab="$p"
  printf '%s' "$lab" | tr -d '\t\n\r' | cut -c1-60
}

cpufreq_table_json() {
  local base="$1" want="$2" p x pol tbl out=""
  for p in "$base"/policy*/cpuinfo_max_freq; do
    [ -f "$p" ] || continue
    x=$(cat "$p" 2>/dev/null)
    [ "$x" = "$want" ] || continue
    pol=${p%/cpuinfo_max_freq}
    tbl="$pol/scaling_available_frequencies"
    if [ -r "$tbl" ]; then
      out=$(tr ' ' '\n' < "$tbl" 2>/dev/null | grep -E '^[0-9]+$' | sort -n | tr '\n' ',')
      out=${out%,}
    fi
    break
  done
  printf '[%s]' "$out"
}

cmd_clusters() {
  local CPUFREQ=/sys/devices/system/cpu/cpufreq
  local p maxf freqs="" f n=0
  for p in "$CPUFREQ"/policy*/cpuinfo_max_freq; do
    [ -f "$p" ] || continue
    maxf=$(cat "$p" 2>/dev/null)
    case "$maxf" in ''|*[!0-9]*) continue ;; esac
    freqs="$freqs $maxf"
  done
  freqs=$(printf '%s\n' $freqs | sort -nu | tr '\n' ' ')
  for f in $freqs; do n=$((n+1)); done

  local cpu_json=""
  if [ "$n" -ge 1 ]; then
    set -- $freqs  # $1..$n 升序
    local f_big f_little f_mid2 f_mid
    eval "f_big=\${$n}"
    f_little=$1
    [ "$n" -ge 4 ] && eval "f_mid2=\${$((n-1))}"
    if [ "$n" -ge 4 ]; then
      eval "f_mid=\${$((n-2))}"
    elif [ "$n" -eq 3 ]; then
      f_mid=$2
    fi
    cpu_json="{\"key\":\"big\",\"max\":$f_big,\"freqs\":$(cpufreq_table_json "$CPUFREQ" "$f_big")}"
    [ "$n" -ge 4 ] && cpu_json="$cpu_json,{\"key\":\"mid2\",\"max\":$f_mid2,\"freqs\":$(cpufreq_table_json "$CPUFREQ" "$f_mid2")}"
    [ "$n" -ge 3 ] && cpu_json="$cpu_json,{\"key\":\"mid\",\"max\":$f_mid,\"freqs\":$(cpufreq_table_json "$CPUFREQ" "$f_mid")}"
    [ "$n" -ge 2 ] && cpu_json="$cpu_json,{\"key\":\"little\",\"max\":$f_little,\"freqs\":$(cpufreq_table_json "$CPUFREQ" "$f_little")}"
  fi

  local gpu_json="null" node v mhz
  for node in \
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
    /sys/kernel/gpu/gpu_max_clock; do
    [ -f "$node" ] || continue
    v=$(cat "$node" 2>/dev/null)
    case "$v" in ''|*[!0-9]*) continue ;; esac
    if [ "$v" -ge 100000000 ]; then mhz=$((v/1000000))
    elif [ "$v" -ge 100000 ]; then mhz=$((v/1000))
    else mhz=$v
    fi
    break
  done
  if [ -n "$mhz" ]; then
    local gnode gvals gfreqs_json="[]" is_mhz=0 _mx
    for gnode in \
      /sys/class/kgsl/kgsl-3d0/gpu_available_frequencies \
      /sys/class/devfreq/3d00000.qcom,kgsl-3d0/available_frequencies \
      /sys/class/devfreq/5000000.qcom,kgsl-3d0/available_frequencies \
      /sys/class/devfreq/2c00000.qcom,kgsl-3d0/available_frequencies \
      /sys/class/kgsl/kgsl-3d0/devfreq/available_frequencies \
      /sys/class/devfreq/48000000.mali/available_frequencies \
      /sys/class/devfreq/13000000.mali/available_frequencies \
      /sys/class/devfreq/mtk-mali/available_frequencies \
      /sys/class/devfreq/gpufreq/available_frequencies \
      /sys/class/devfreq/mali0/available_frequencies \
      /sys/class/misc/mali0/device/devfreq/available_frequencies \
      /sys/devices/platform/soc/13000000.mali/devfreq/available_frequencies \
      /sys/devices/platform/13000000.mali/devfreq/available_frequencies \
      /sys/class/devfreq/3d00000.qcom,kgsl-3d0/freq_table_mhz \
      /sys/class/devfreq/5000000.qcom,kgsl-3d0/freq_table_mhz \
      /sys/class/kgsl/kgsl-3d0/freq_table_mhz; do
      [ -f "$gnode" ] || continue
      gvals=$(tr ' ' '\n' < "$gnode" 2>/dev/null | grep -E '^[0-9]+$')
      [ -n "$gvals" ] || continue
      case "$gnode" in *freq_table_mhz) is_mhz=1 ;; *) is_mhz=0 ;; esac
      if [ "$is_mhz" = "1" ]; then
        gvals=$(printf '%s\n' $gvals | awk '{printf "%.0f\n", $1 * 1000000}')
      else
        _mx=$(printf '%s\n' $gvals | sort -rn | head -1)
        if [ -n "$_mx" ] && [ "$_mx" -lt 10000 ]; then
          gvals=$(printf '%s\n' $gvals | awk '{printf "%.0f\n", $1 * 1000000}')
        elif [ -n "$_mx" ] && [ "$_mx" -lt 10000000 ]; then
          gvals=$(printf '%s\n' $gvals | awk '{printf "%.0f\n", $1 * 1000}')
        fi
      fi
      gvals=$(printf '%s\n' $gvals | grep -E '^[0-9]+$' | sort -n | tr '\n' ',')
      gfreqs_json="[${gvals%,}]"
      break
    done
    local max_hz table_max=""
    if [ "$gfreqs_json" != "[]" ]; then
      max_hz=$(printf '%s' "$gfreqs_json" | tr ',[]' '\n\n\n' | grep -E '^[0-9]+$' | sort -rn | head -1)
      table_max=$max_hz
    else
      max_hz=$(awk -v m="$mhz" 'BEGIN{printf "%.0f", m * 1000000}')
    fi
    local max_src="hw" conf_gpu_max=""
    if [ -n "$table_max" ] && [ -f "$CONF" ]; then
      conf_gpu_max=$( ( . "$CONF" 2>/dev/null; printf '%s' "$HW_GPU_MAX" ) 2>/dev/null )
      case "$conf_gpu_max" in ''|*[!0-9]*) conf_gpu_max="" ;; esac
      if [ -n "$conf_gpu_max" ]; then
        local hi lo
        hi=$(awk -v t="$table_max" 'BEGIN{printf "%.0f", t*120/100}')
        lo=$(awk -v t="$table_max" 'BEGIN{printf "%.0f", t*80/100}')
        if [ "$conf_gpu_max" -le "$hi" ] && [ "$conf_gpu_max" -ge "$lo" ]; then
          max_hz=$conf_gpu_max
          max_src="conf"
        fi
      fi
    fi
    gpu_json="{\"max_hz\":$max_hz,\"freqs_hz\":$gfreqs_json,\"max_src\":\"$max_src\"}"
  fi
  printf '{"cpu":[%s],"gpu":%s}\n' "$cpu_json" "$gpu_json"
}

cmd_ping() { echo OK; }

cmd_status() {
  local state="" pid="" alive=0 paused=0 game_pkg="" gpu="" last="" screen="" soc k v
  read -r state < "$MODDIR/state" 2>/dev/null
  [ -z "$state" ] && state=unknown
  read -r pid < "$MODDIR/powerd.lock/pid" 2>/dev/null
  case "$pid" in ''|*[!0-9]*) pid="" ;; *) [ -d "/proc/$pid" ] && alive=1 ;; esac
  [ -f "$MODDIR/pause" ] && paused=1
  if [ -f "$RUN_DIR/status" ]; then
    while IFS='=' read -r k v; do
      case "$k" in
        game_pkg)   game_pkg="$v" ;;
        gpu_result) gpu="$v" ;;
        last_seen)  last="$v" ;;
        screen)     screen="$v" ;;
      esac
    done < "$RUN_DIR/status"
  fi
  soc=$(getprop ro.soc.model 2>/dev/null)
  [ -z "$soc" ] && soc=$(getprop ro.board.platform 2>/dev/null)
  printf '{"state":"%s","alive":%d,"paused":%d,"pid":"%s","game_pkg":"%s","gpu_result":"%s","last_seen":"%s","screen":"%s","soc":"%s"}\n' \
    "$(jesc "$state")" "$alive" "$paused" "$(jesc "$pid")" "$(jesc "$game_pkg")" \
    "$(jesc "$gpu")" "$(jesc "$last")" "$(jesc "$screen")" "$(jesc "$soc")"
}

cmd_log() {
  local n="$1" lf="$LOG_FILE"
  case "$n" in ''|*[!0-9]*) n=200 ;; esac
  [ "$n" -gt 1000 ] && n=1000
  [ -f "$lf" ] || lf="$MODDIR/powerd.log"
  [ -f "$lf" ] && tail -n "$n" "$lf" 2>/dev/null
  return 0
}

CONF_KEYS="INTERVAL GAME_EXIT_GRACE_SEC FENGCHI_DETECT_SEC CPU_PCT_BIG CPU_PCT_MID2 CPU_PCT_MID CPU_PCT_LITTLE GPU_PCT GPU_BOOST_CONTROL CPU_GOVERNOR LOG_FILE"

ensure_conf_keys() {
  [ -f "$CONF" ] || return 0
  local k v
  for k in $CONF_KEYS; do
    grep -q "^$k=" "$CONF" 2>/dev/null && continue
    case "$k" in
      INTERVAL) v=5 ;;
      GAME_EXIT_GRACE_SEC) v=10 ;;
      FENGCHI_DETECT_SEC) v=1 ;;
      CPU_PCT_BIG) v=55 ;;
      CPU_PCT_MID2) v=50 ;;
      CPU_PCT_MID) v=48 ;;
      CPU_PCT_LITTLE) v=60 ;;
      GPU_PCT) v=38 ;;
      GPU_BOOST_CONTROL) v=1 ;;
      CPU_GOVERNOR) v= ;;
      LOG_FILE) v="$MODDIR/powerd.log" ;;
    esac
    printf '%s=%s\n' "$k" "$v" >> "$CONF"
  done
  chmod 644 "$CONF" 2>/dev/null
}

cmd_conf_get() {
  ensure_conf_keys
  local k v first=1
  printf '{'
  for k in $CONF_KEYS; do
    v=$(sed -n "s/^$k=//p" "$CONF" 2>/dev/null | head -1)
    v=$(printf '%s' "$v" | sed 's/[[:space:]]*#.*$//')  # 去行尾注释
    case "$v" in '"'*'"') v=$(printf '%s' "$v" | sed 's/^"//; s/"$//') ;; esac
    [ "$first" = 1 ] || printf ','
    first=0
    printf '"%s":"%s"' "$k" "$(jesc "$v")"
  done
  printf '}\n'
}

cmd_conf_set() {
  ensure_conf_keys
  local k="$1" v="$2" num=0 min=0 max=0
  case "$k" in
    INTERVAL) num=1; min=2; max=60 ;;
    GAME_EXIT_GRACE_SEC) num=1; min=5; max=300 ;;
    FENGCHI_DETECT_SEC) num=1; min=0; max=10 ;;
    CPU_PCT_BIG|CPU_PCT_MID2|CPU_PCT_MID|CPU_PCT_LITTLE|GPU_PCT) num=1; min=1; max=100 ;;
    GPU_BOOST_CONTROL) case "$v" in 0|1) ;; *) err "值非法（0/1）" ;; esac ;;
    CPU_GOVERNOR) ;;
    LOG_FILE) case "$v" in /*) ;; *) err "路径必须以 / 开头" ;; esac ;;
    *) err "未知参数" ;;
  esac
  if [ "$num" = "1" ]; then
    case "$v" in ''|*[!0-9]*) err "需要数字" ;; esac
    [ "$v" -lt "$min" ] && v=$min
    [ "$v" -gt "$max" ] && v=$max
  fi
  v=$(printf '%s' "$v" | tr -d '|&\\' | tr -d '\n' | tr -d "'" | tr -d '"')
  [ -f "$CONF" ] || err "配置文件不存在"
  if [ "$k" = "CPU_GOVERNOR" ]; then
    sed -i "s|^$k=.*|$k=\"$v\"|" "$CONF"
  else
    sed -i "s|^$k=.*|$k=$v|" "$CONF"
  fi
  grep -q "^$k=" "$CONF" 2>/dev/null || err "写入失败"
  echo OK
}

cmd_games_list() {
  [ -f "$GAMES" ] || return 0
  grep -v -e '^#' -e '^[[:space:]]*$' "$GAMES" 2>/dev/null | tr -d '\r' | sed 's/^[[:space:]]*//; s/[[:space:]]*$//' | grep -v '^$' | sort -u
  return 0
}

cmd_games_add() {
  valid_pkg "$1" || err "包名非法"
  touch "$GAMES" 2>/dev/null || err "无法写入名单"
  grep -q -F -x -- "$1" "$GAMES" 2>/dev/null || printf '%s\n' "$1" >> "$GAMES"
  echo OK
}

cmd_games_del() {
  valid_pkg "$1" || err "包名非法"
  if [ -f "$GAMES" ]; then
    grep -v -F -x -- "$1" "$GAMES" > "$GAMES.tmp" 2>/dev/null && mv "$GAMES.tmp" "$GAMES"
  fi
  echo OK
}

ICON_BIN="$MODDIR/bin/apkicon"
APPS_CACHE="$RUN_DIR/apps.cache"
APPS_LOCK="$RUN_DIR/apps.lock"

apps_scan() {
  local tmp="$APPS_CACHE.tmp.$$"
  dumpsys package 2>/dev/null | awk '
    /Package \[/ {
      if (pkg != "") print pkg "\t" lastlab;
      pkg=$0; sub(/^.*Package \[/, "", pkg); sub(/\].*$/, "", pkg);
      lastlab=pkg; next
    }
    tolower($0) ~ /application-label/ {
      lab=$0; sub(/^.*[Aa]pplication-[Ll]abel['"'"' :=]*/, "", lab);
      gsub(/^['"'"']|['"'"']$/, "", lab);
      gsub(/\t/, " ", lab);
      if (lab != "") lastlab=lab;
    }
    END { if (pkg != "") print pkg "\t" lastlab }' > "$tmp" 2>/dev/null
  if [ -s "$tmp" ]; then
    mv -f "$tmp" "$APPS_CACHE" 2>/dev/null
    rm -rf "$APPS_LOCK" 2>/dev/null
    return 0
  fi
  rm -f "$tmp" 2>/dev/null
  rm -rf "$APPS_LOCK" 2>/dev/null
  return 1
}

apps_scan_bg() {
  if [ -d "$APPS_LOCK" ]; then
    local mt now
    mt=$(stat -c '%Y' "$APPS_LOCK" 2>/dev/null); now=$(date +%s)
    case "$mt" in
      ''|*[!0-9]*) ;;
      *) [ $((now - mt)) -lt 300 ] && return 0 ;;
    esac
    rm -rf "$APPS_LOCK" 2>/dev/null
  fi
  mkdir "$APPS_LOCK" 2>/dev/null || return 0
  if command -v setsid >/dev/null 2>&1; then
    setsid /system/bin/sh "$0" apps_scan_job >/dev/null 2>&1 &
  else
    ( apps_scan >/dev/null 2>&1 ) >/dev/null 2>&1 &
  fi
}

cmd_apps_all() {
  local mt now
  mkdir -p "$RUN_DIR" 2>/dev/null
  if [ "$1" = "force" ]; then
    apps_scan
    [ -s "$APPS_CACHE" ] && cat "$APPS_CACHE"
    return 0
  fi
  if [ -s "$APPS_CACHE" ]; then
    cat "$APPS_CACHE"
    mt=$(stat -c '%Y' "$APPS_CACHE" 2>/dev/null); now=$(date +%s)
    case "$mt" in
      ''|*[!0-9]*) ;;
      *) [ $((now - mt)) -gt 21600 ] && apps_scan_bg ;;
    esac
    return 0
  fi
  pm list packages 2>/dev/null | sed 's/^package://' | while IFS= read -r p; do
    [ -n "$p" ] && printf '%s\t%s\n' "$p" "$p"
  done
  apps_scan_bg
  return 0
}

icon_candidates() {
  local listing="$1" dens
  for dens in xhdpi hdpi xxhdpi xxxhdpi mdpi; do
    printf '%s\n' "$listing" | grep -i "mipmap-${dens}" | grep -iE 'launcher|icon' | grep -vi 'round'
    printf '%s\n' "$listing" | grep -i "mipmap-${dens}" | grep -iE 'launcher|icon'
  done
  printf '%s\n' "$listing" | grep -iE 'mipmap|drawable' | grep -iE 'launcher|icon' | grep -vi 'round'
  printf '%s\n' "$listing" | grep -iE 'mipmap|drawable' | grep -iE 'launcher|icon'
  printf '%s\n' "$listing" | grep -iE 'ic_launcher_background\.(png|webp|jpg|jpeg)$'
  printf '%s\n' "$listing" | grep -iE '(^|/)(ic_launcher|app_icon|appicon)[^/]*\.(png|webp|jpg|jpeg)$'
}

cmd_app_icon() {
  local p="$1" apk entry mime b64 listing dir f miss mt now cands fallback fb_mime cand
  valid_pkg "$p" || return 1
  command -v unzip >/dev/null 2>&1 || return 1
  dir="$RUN_DIR/icons"
  mkdir -p "$dir" 2>/dev/null
  f="$dir/$p.b64"
  miss="$dir/$p.miss"
  now=$(date +%s)
  if [ -s "$f" ]; then
    mt=$(stat -c '%Y' "$f" 2>/dev/null)
    case "$mt" in ''|*[!0-9]*) ;; *) [ $((now - mt)) -lt 604800 ] && { cat "$f"; return 0; } ;; esac
  fi
  if [ -f "$miss" ]; then
    mt=$(stat -c '%Y' "$miss" 2>/dev/null)
    case "$mt" in ''|*[!0-9]*) ;; *) [ $((now - mt)) -lt 300 ] && return 0 ;; esac
    rm -f "$miss" 2>/dev/null
  fi
  for apk in $(pm path "$p" 2>/dev/null | sed 's/^package://'); do
    [ -f "$apk" ] || continue
    cands=""
    if [ -x "$ICON_BIN" ]; then
      entry=$("$ICON_BIN" icon "$apk" 2>/dev/null)
      case "$entry" in
        *.xml)
          for cand in $("$ICON_BIN" refs "$apk" "$entry" 2>/dev/null); do
            entry=$("$ICON_BIN" id "$apk" "$cand" 2>/dev/null)
            case "$entry" in
              *.png|*.webp|*.jpg|*.jpeg) break ;;
              *) entry="" ;;
            esac
          done
          ;;
      esac
      case "$entry" in
        *.png|*.webp|*.jpg|*.jpeg) cands="$entry" ;;
      esac
    fi
    listing=$(unzip -l "$apk" 2>/dev/null | grep -iE '\.(png|webp|jpg|jpeg)$' | sed -E 's/^ *[^ ]+ +[^ ]+ +[^ ]+ +//')
    [ -n "$listing" ] && cands="$cands
$(icon_candidates "$listing")"
    cands=$(printf '%s\n' "$cands" | awk 'NF && !seen[$0]++')
    [ -n "$cands" ] || continue
    printf '%s\n' "$cands" > "$dir/.cand.$$" 2>/dev/null
    b64=""; fallback=""; fb_mime=""
    while IFS= read -r entry; do
      [ -n "$entry" ] || continue
      case "$entry" in
        *.webp|*.WEBP) mime="image/webp" ;;
        *.jpg|*.jpeg|*.JPG|*.JPEG) mime="image/jpeg" ;;
        *) mime="image/png" ;;
      esac
      b64=$(unzip -p "$apk" "$entry" 2>/dev/null | base64 2>/dev/null | tr -d '\n')
      [ -n "$b64" ] || continue
      [ ${#b64} -le 120000 ] && break
      [ -n "$fallback" ] || { fallback="$b64"; fb_mime="$mime"; }
      b64=""
    done < "$dir/.cand.$$"
    rm -f "$dir/.cand.$$" 2>/dev/null
    [ -n "$b64" ] || { b64="$fallback"; mime="$fb_mime"; }
    [ -n "$b64" ] || continue
    printf 'data:%s;base64,%s' "$mime" "$b64" > "$f" 2>/dev/null
    rm -f "$miss" 2>/dev/null
    cat "$f"
    return 0
  done
  : > "$miss" 2>/dev/null
  return 0
}

cmd_conf_restore() {
  local bak="$MODDIR/powerd.conf.bak"
  [ -f "$bak" ] || err "没有 powerd.conf.bak（未升级过或已还原）"
  [ -f "$CONF" ] && cp -f "$CONF" "$MODDIR/powerd.conf.prev" 2>/dev/null
  cp -f "$bak" "$CONF" 2>/dev/null || err "写入失败"
  chmod 644 "$CONF" 2>/dev/null
  ensure_conf_keys
  echo OK
}

cmd_conf_bak() {
  [ -f "$MODDIR/powerd.conf.bak" ] && echo yes || echo no
}

cmd_diag() {
  echo "moddir=$MODDIR"
  echo "games=$([ -f "$GAMES" ] && grep -vcE '^#|^$' "$GAMES" 2>/dev/null || echo none)"
  echo "apps_cache=$([ -s "$APPS_CACHE" ] && wc -l < "$APPS_CACHE" 2>/dev/null || echo none)"
  echo "icons=$(ls "$RUN_DIR/icons" 2>/dev/null | wc -l)"
  echo "conf=$([ -f "$CONF" ] && echo yes || echo no) bak=$([ -f "$MODDIR/powerd.conf.bak" ] && echo yes || echo no)"
  echo "pid=$(cat "$MODDIR/powerd.lock/pid" 2>/dev/null)"
  echo "unzip=$(command -v unzip 2>/dev/null || echo none)"
  echo "state=$(sed -n 's/^state=//p' "$RUN_DIR/status" 2>/dev/null)"
}

cmd_restart() {
  local pid alive=0 w=0
  rm -f "$MODDIR/pause"
  pid=$(cat "$MODDIR/powerd.lock/pid" 2>/dev/null)
  case "$pid" in ''|*[!0-9]*) ;; *) [ -d "/proc/$pid" ] && alive=1 ;; esac
  if [ "$alive" = "1" ]; then
    kill "$pid" 2>/dev/null
    while [ -d "/proc/$pid" ] && [ "$w" -lt 10 ]; do sleep 1; w=$((w+1)); done
    [ "$w" -ge 10 ] && [ -d "/proc/$pid" ] && kill -9 "$pid" 2>/dev/null
    sleep 1
  fi
  POWERD_MKSH=1 nohup /system/bin/sh "$MODDIR/powerd.sh" start >/dev/null 2>&1 &
  echo OK
}

cmd_about() {
  echo "---PROP---"
  cat "$MODDIR/module.prop" 2>/dev/null
  echo "---CHANGELOG---"
  cat "$MODDIR/updatelog.txt" 2>/dev/null
  return 0
}

_log_api "${1:-none}"

cmd_swap() {
  case "$1" in
    adapt|dispatch) ;;
    *) err "用法: api.sh swap [adapt|dispatch]" ;;
  esac
  [ -f "$MODDIR/webroot/swap.sh" ] || err "未找到 swap.sh"
  sh "$MODDIR/webroot/swap.sh" "$1" 2>/dev/null
}

cmd_takeover_get() {
  v=$(cat "$MODDIR/files/game_takeover" 2>/dev/null)
  case "$v" in color|official|fengchi) ;; *) v=color ;; esac
  echo "$v"
}

OFFICIAL_MASKS="/system_ext/bin/horae /proc/game_opt/cpu_max_freq /proc/game_opt/fake_cpu7_cpuinfo_max_freq /proc/game_opt/disable_cpufreq_limit /proc/game_opt/early_detect/fst_cpu_max_freq /proc/game_opt/early_detect/flt_cpu_max_freq /proc/game_opt/early_detect/edb_cpu_max_freq"

official_mask_on() {
  local t
  for t in $OFFICIAL_MASKS; do
    umount "$t" 2>/dev/null
    chmod +w "$t" 2>/dev/null
    echo "" | tee /dev/fas_rs_mask "$t" 2>/dev/null
    /bin/find "$t" -exec mount /dev/fas_rs_mask {} \; 2>/dev/null
    rm -f /dev/fas_rs_mask 2>/dev/null
  done
  dumpsys horae testmode 2>/dev/null
}

official_mask_off() {
  local t n
  for t in $OFFICIAL_MASKS; do
    n=0
    while grep -q " $t " /proc/mounts 2>/dev/null && [ "$n" -lt 5 ]; do
      umount "$t" 2>/dev/null || break
      n=$((n+1))
    done
  done
  stop horae 2>/dev/null
  start horae 2>/dev/null
}

cmd_takeover_set() {
  case "$1" in
    color|official|fengchi) ;;
    *) err "用法: api.sh takeover_set [color|official|fengchi]" ;;
  esac
  mkdir -p "$MODDIR/files"
  echo "$1" > "$MODDIR/files/game_takeover"
  if [ "$1" = "color" ]; then
    official_mask_on
  else
    official_mask_off
  fi
  rm -f "/sdcard/Android/qingtd/stop" 2>/dev/null
  pgrep -f qingtdjc1 >/dev/null 2>&1 || nohup sh "$MODDIR/script/qingtdjc1.sh" >/dev/null 2>&1 &
  local cur="" mos="" moren="" ms kzlx
  cur=$(dumpsys window displays 2>/dev/null | grep "mFocusedApp" | grep -v "AppWindowToken" | grep "ActivityRecord" | awk -F " " '{print $3}' | awk -F "/" '{print $1}')
  [ -z "$cur" ] && cur=$(dumpsys window 2>/dev/null | grep "mFocusedApp" | grep -v "AppWindowToken" | grep "ActivityRecord" | awk -F " " '{print $3}' | awk -F "/" '{print $1}')
  [ -n "$cur" ] && mos=$(grep "^$cur=" "/sdcard/Android/qingtd/动态模式切换.conf" 2>/dev/null | head -n 1 | cut -d '=' -f2)
  if [ "$1" = "color" ]; then
    rm -f "$MODDIR/files/released" 2>/dev/null
    if [ -n "$mos" ]; then
      ms="$mos"
    else
      moren=$(grep "^moren=" "/sdcard/Android/qingtd/动态模式切换.conf" 2>/dev/null | head -n 1 | cut -d '=' -f2)
      ms="$moren"
    fi
    if [ -n "$ms" ]; then
      kzlx=1
      . /data/powercfg.sh >/dev/null 2>&1
    fi
  else
    if [ -n "$mos" ]; then
      sh "$MODDIR/script/release.sh" >/dev/null 2>&1
      echo 1 > "$MODDIR/files/released"
    fi
  fi
  local fc_tail=""
  if [ "$1" = "fengchi" ]; then
    rm -f "$MODDIR/fengchi/.injected" 2>/dev/null
    fc_tail="sh '$MODDIR/fengchi/run_inject.sh' >/dev/null 2>&1"
  fi
  nohup sh -c "
    sleep 1
    am force-stop com.oplus.games >/dev/null 2>&1
    monkey -p com.oplus.games -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
    rm -f /data/data/com.oplus.cosa/databases/db_game_database* 2>/dev/null
    rm -f /data/data/com.oplus.cosa/no_backup/androidx.work.workdb* 2>/dev/null
    am force-stop com.oplus.cosa >/dev/null 2>&1
    monkey -p com.oplus.cosa -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
    $fc_tail
  " >/dev/null 2>&1 &
  echo "OK"
}

cmd_screen_ctl() {
  local sub="$1"
  case "$sub" in
    scan|reset|apps) ;;
    ltpo)
      case "$2" in
        compat|disable|keep) ;;
        *) err "用法: api.sh screen_ctl ltpo [compat|disable|keep]" ;;
      esac
      ;;
    *) err "用法: api.sh screen_ctl [scan|apps|ltpo|reset]" ;;
  esac
  [ -f "$MODDIR/screen/ctl.sh" ] || err "未找到 screen/ctl.sh"
  sh "$MODDIR/screen/ctl.sh" "$@"
}

case "$1" in
  ping) cmd_ping ;;
  swap) cmd_swap "$2" ;;
  takeover_get) cmd_takeover_get ;;
  takeover_set) cmd_takeover_set "$2" ;;
  status) cmd_status ;;
  log) cmd_log "$2" ;;
  conf_get) cmd_conf_get ;;
  conf_set) cmd_conf_set "$2" "$3" ;;
  conf_restore) cmd_conf_restore ;;
  conf_bak) cmd_conf_bak ;;
  diag) cmd_diag ;;
  apps_scan_job) apps_scan ;;
  games_list) cmd_games_list ;;
  games_add) cmd_games_add "$2" ;;
  games_del) cmd_games_del "$2" ;;
  apps_all) cmd_apps_all "$2" ;;
  app_icon) cmd_app_icon "$2" ;;
  clusters) cmd_clusters ;;
  restart) cmd_restart ;;
  screen_ctl) cmd_screen_ctl "$2" "$3" ;;
  about) cmd_about ;;
  *) echo "ERR: 未知命令" >&2; exit 1 ;;
esac
