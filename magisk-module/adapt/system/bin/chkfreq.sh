#!/system/bin/sh
G='\033[1;32m'; B='\033[1;34m'; C='\033[1;36m'
R='\033[1;31m'; Y='\033[1;33m'; M='\033[1;35m'; X='\033[0m'
CPUFREQ=/sys/devices/system/cpu/cpufreq
MODDIR=/data/adb/modules/colorFC
for _d in /data/adb/modules/colorFC /data/adb/ap/modules/colorFC /data/adb/ksu/modules/colorFC; do
  [ -d "$_d" ] && { MODDIR="$_d"; break; }
done

find_gpu_node() {
  for d in /sys/class/kgsl/kgsl-3d0 \
            /sys/class/devfreq/3d00000.qcom,kgsl-3d0 \
            /sys/class/devfreq/5000000.qcom,kgsl-3d0 \
            /sys/class/devfreq/2c00000.qcom,kgsl-3d0 \
            /sys/class/kgsl/kgsl-3d0/devfreq \
            /sys/class/devfreq/48000000.mali \
            /sys/class/devfreq/13000000.mali \
            /sys/class/devfreq/mtk-mali \
            /sys/class/devfreq/gpufreq \
            /sys/class/devfreq/mali0 \
            /sys/class/misc/mali0/device/devfreq \
            /sys/devices/platform/soc/13000000.mali/devfreq \
            /sys/devices/platform/13000000.mali/devfreq \
            /sys/kernel/gpu; do
    [ -d "$d" ] || continue
    for n in max_gpuclk max_freq gpu_max_clock max_clock_mhz; do
      [ -f "$d/$n" ] && echo "$d" && return
    done
  done
}
GPU_DIR=$(find_gpu_node)
GPU_LABEL="kgsl"
case "$GPU_DIR" in
  *mali*|*gpufreq*) GPU_LABEL="mali" ;;
esac

rd() {
  local _v=""
  read -r _v < "$2" 2>/dev/null
  eval "$1=\$_v"
}
fmt_ghz() {
  local v=${1:-0}
  case "$v" in ''|*[!0-9]*) v=0 ;; esac
  printf '%d.%02dGHz' $((v / 1000000)) $(( (v % 1000000) / 10000 ))
}
fmt_mhz() {
  local v=${1:-0}
  case "$v" in ''|*[!0-9]*) v=0 ;; esac
  if [ "$v" -ge 10000000 ]; then
    v=$((v / 1000000))
  elif [ "$v" -ge 10000 ]; then
    v=$((v / 1000))
  fi
  printf '%dMHz' "$v"
}
TSF=0
case "$(printf '%(%H:%M:%S)T' -1 2>/dev/null)" in
  *%*|'') TSF=0 ;;
  *)      TSF=1 ;;
esac
clock() { if [ "$TSF" = "1" ]; then printf '%(%T)T' -1; else date +'%T'; fi; }

cores=0
for c in /sys/devices/system/cpu/cpu[0-9]*; do [ -d "$c" ] && cores=$((cores+1)); done
[ "$cores" -eq 0 ] && echo "无法读取 CPU" >&2 && exit 1

govs=$(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_available_governors 2>/dev/null)

printf '\033[?25l'
trap 'printf "\033[?25h"; clear; printf "${G}已关闭${X}\n"' EXIT
trap 'exit 130' INT TERM

clear
printf "${R} ____             _  __ _\n"
printf "${R}| __ ) _   _    | |/ /| |___ __   _____\n"
printf "${R}|  _ \\\\| | | |   | ' / | __\\\\ \\\\ /\\\\ / / _ \\\\\\n"
printf "${R}| |_) | |_| |   | . \\\\ | |_  \\\\ V  V / (_) |\n"
printf "${R}|____/ \\\\__, |   |_|\\\\_\\\\ \\\\__|  \\\\_/\\\\_/ \\\\___/${X}\n"
printf "${R}       |___/${X}   ${M}-- by Ktwo --${X}\n"
printf "${B}------------------------------------------------${X}\n"
printf "${G}支持的调速器:${X} "
for g in $govs; do
  case $g in scx|hmbird) printf "${R}${g}(风驰)${X} " ;; *) printf "${C}${g}${X} " ;; esac
done
printf "\n"
printf "${B}%-48s${X}\n" "────────────────────────────────────────────────"
printf "%-8s  %-14s  %10s  %10s\n" "核心" "调速器" "上限" "当前"
printf "${B}%-48s${X}\n" "────────────────────────────────────────────────"

header_lines=11

while :; do
  row=$((header_lines+1))
  i=0
  while [ "$i" -lt "$cores" ]; do
    p="/sys/devices/system/cpu/cpu${i}/cpufreq"
    if [ -f "$p/scaling_governor" ]; then
      g=""; mx=""; cur=""
      rd g "$p/scaling_governor"
      case $g in
        ondemand) cl=$C ;;
        performance) cl=$R ;;
        scx*|hmbird*) cl=$R ;;
        powersave) cl=$M ;;
        userspace) cl=$Y ;;
        schedutil) cl=$B ;;
        *) cl=$G ;;
      esac
      rd mx "$p/scaling_max_freq"
      rd cur "$p/scaling_cur_freq"
      mx_g=$(fmt_ghz "$mx")
      cur_g=$(fmt_ghz "$cur")
      printf "\033[${row};0H\033[K"
      printf "%-8s  ${cl}%-14s${X}  ${G}%10s${X}  ${B}%10s${X}" \
        "CPU$(printf '%02d' $i)" "${g}" "$mx_g" "$cur_g"
    else
      printf "\033[${row};0H\033[K"
      printf "%-8s  ${R}%-14s${X}  ${R}%10s${X}  ${R}%10s${X}" \
        "CPU$(printf '%02d' $i)" "离线" "N/A" "N/A"
    fi
    row=$((row+1))
    i=$((i+1))
  done

  if [ -n "$GPU_DIR" ]; then
    gmx=""; gcur=""
    for n in max_gpuclk max_freq gpu_max_clock max_clock_mhz; do
      [ -f "$GPU_DIR/$n" ] && { rd gmx "$GPU_DIR/$n"; break; }
    done
    for n in gpuclk cur_freq gpu_cur_freq gpu_clock; do
      [ -f "$GPU_DIR/$n" ] && { rd gcur "$GPU_DIR/$n"; break; }
    done
    gmx_g=$(fmt_mhz "$gmx")
    gcur_g=$(fmt_mhz "$gcur")
    printf "\033[${row};0H\033[K"
    printf "%-8s  ${M}%-14s${X}  ${G}%10s${X}  ${B}%10s${X}" \
      "GPU" "$GPU_LABEL" "$gmx_g" "$gcur_g"
    row=$((row+1))
  fi

  st=""
  rd st "$MODDIR/state"
  [ -n "$st" ] || st=unknown
  gp=""
  if [ -f "$MODDIR/run/status" ]; then
    while IFS='=' read -r _k _v; do
      [ "$_k" = game_pkg ] && { gp=$_v; break; }
    done < "$MODDIR/run/status"
  fi
  printf "\033[${row};0H\033[K${B}模块状态:${X} ${G}${st}${X}  时间: ${C}$(clock)${X}  [q退出]"
  [ -n "$gp" ] && printf "  游戏: ${M}%s${X}" "$gp"

  read -t 1 in 2>/dev/null
  [ "$in" = "q" ] && break
done
