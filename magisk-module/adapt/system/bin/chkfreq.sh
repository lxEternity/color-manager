#!/system/bin/sh
G='\033[1;32m'; B='\033[1;34m'; C='\033[1;36m'
R='\033[1;31m'; Y='\033[1;33m'; M='\033[1;35m'; X='\033[0m'
CPUFREQ=/sys/devices/system/cpu/cpufreq
MODDIR=/data/adb/modules/powerd_freqcap

find_gpu_node() {
  for d in /sys/class/kgsl/kgsl-3d0 \
            /sys/class/devfreq/3d00000.qcom,kgsl-3d0 \
            /sys/class/devfreq/5000000.qcom,kgsl-3d0 \
            /sys/class/devfreq/mtk-mali \
            /sys/class/devfreq/mali0; do
    [ -d "$d" ] || continue
    for n in max_gpuclk max_freq; do
      [ -f "$d/$n" ] && echo "$d" && return
    done
  done
}
GPU_DIR=$(find_gpu_node)

cores=0
for c in /sys/devices/system/cpu/cpu[0-9]*; do [ -d "$c" ] && cores=$((cores+1)); done
[ "$cores" -eq 0 ] && echo "无法读取 CPU" >&2 && exit 1

govs=$(cat /sys/devices/system/cpu/cpu0/cpufreq/scaling_available_governors 2>/dev/null)

echo -ne "\033[?25l"
trap 'echo -ne "\033[?25h"; clear; printf "${G}已关闭${X}\n"' EXIT INT TERM

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
echo

printf "${B}%-48s${X}\n" "────────────────────────────────────────────────"
printf "%-8s  %-14s  %10s  %10s\n" "核心" "调速器" "上限" "当前"
printf "${B}%-48s${X}\n" "────────────────────────────────────────────────"

header_lines=12
i=0; while [ "$i" -lt "$cores" ]; do i=$((i+1)); done
total_lines=$((header_lines + cores + 2))
if [ -n "$GPU_DIR" ]; then total_lines=$((total_lines+1)); fi

while :; do
  row=$((header_lines+1))
  i=0
  while [ "$i" -lt "$cores" ]; do
    p="/sys/devices/system/cpu/cpu${i}/cpufreq"
    if [ -f "$p/scaling_governor" ]; then
      g=$(cat "$p/scaling_governor" 2>/dev/null)
      case $g in
        ondemand) cl=$C ;;
        performance) cl=$R ;;
        scx*|hmbird*) cl=$R ;;
        powersave) cl=$M ;;
        userspace) cl=$Y ;;
        schedutil) cl=$B ;;
        *) cl=$G ;;
      esac
      mx=$(cat "$p/cpuinfo_max_freq" 2>/dev/null)
      cur=$(cat "$p/scaling_cur_freq" 2>/dev/null)
      mx_g=$(awk -v v="${mx:-0}" 'BEGIN{printf "%.2fGHz",v/1000000}')
      cur_g=$(awk -v v="${cur:-0}" 'BEGIN{printf "%.2fGHz",v/1000000}')
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
    for n in max_gpuclk max_freq; do
      [ -f "$GPU_DIR/$n" ] && { gmx=$(cat "$GPU_DIR/$n" 2>/dev/null); break; }
    done
    for n in gpuclk cur_freq; do
      [ -f "$GPU_DIR/$n" ] && { gcur=$(cat "$GPU_DIR/$n" 2>/dev/null); break; }
    done
    gmx_g=$(awk -v v="${gmx:-0}" 'BEGIN{printf "%.0fMHz",v/1000000}')
    gcur_g=$(awk -v v="${gcur:-0}" 'BEGIN{printf "%.0fMHz",v/1000000}')
    printf "\033[${row};0H\033[K"
    printf "%-8s  ${M}%-14s${X}  ${G}%10s${X}  ${B}%10s${X}" \
      "GPU" "kgsl" "$gmx_g" "$gcur_g"
    row=$((row+1))
  fi

  st=$(cat "$MODDIR/state" 2>/dev/null || echo "unknown")
  printf "\033[${row};0H\033[K${B}模块状态:${X} ${G}${st}${X}  时间: ${C}$(date +'%T')${X}  [q退出]"

  read -t 1 in 2>/dev/null
  [ "$in" = "q" ] && break
done
