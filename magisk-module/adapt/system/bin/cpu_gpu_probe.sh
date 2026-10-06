#!/system/bin/sh
# ============================================================
#  cpu_gpu_probe.sh  (v5)
#  用法：
#    su -c 'sh /sdcard/3/cpu_gpu_probe.sh'
#    su -c 'sh /sdcard/3/cpu_gpu_probe.sh /sdcard/3/cpu_gpu_probe.log'
#  安全：只读节点，不写频率、不改权限。
# ============================================================

# 屏蔽 mksh 交互模式下的 fc 噪音
exec 2>/dev/null

OUT="${1:-/sdcard/3/cpu_gpu_probe.log}"
TMP="${OUT}.tmp.$$"

cleanup() { rm -f "$TMP" 2>/dev/null; }
trap cleanup EXIT INT TERM
mkdir -p "${OUT%/*}" 2>/dev/null

log() { printf '%s\n' "$*" >> "$TMP"; }

r() {
  p="$1"
  if [ -e "$p" ]; then
    v=$(cat "$p" 2>/dev/null | tr -s '[:space:]' ' ' | sed 's/^ *//;s/ *$//')
    if [ -w "$p" ]; then log "  RW    $p = ${v:-<empty>}"; else log "  RO    $p = ${v:-<empty>}"; fi
  else
    log "  MISS  $p"
  fi
}

rd() {
  d="$1"
  if [ -d "$d" ]; then
    log "  DIR   $d"
    for f in "$d"/*; do [ -f "$f" ] && r "$f"; done
  else
    log "  MISS  $d/"
  fi
}

log "============================================================"
log " CPU/GPU PROBE START $(date '+%Y-%m-%d %H:%M:%S')"
log "============================================================"

log ""
log "[SYS] 基础信息"
log "  shell      = $(readlink /proc/$$/exe 2>/dev/null)"
log "  uid        = $(id 2>/dev/null)"
log "  kernel     = $(uname -a 2>/dev/null)"
log "  soc.model  = $(getprop ro.soc.model 2>/dev/null)"
log "  soc.maker  = $(getprop ro.soc.manufacturer 2>/dev/null)"
log "  board      = $(getprop ro.board.platform 2>/dev/null)"
log "  hardware   = $(getprop ro.hardware 2>/dev/null)"
log "  product    = $(getprop ro.product.model 2>/dev/null)"
log "  brand      = $(getprop ro.product.brand 2>/dev/null)"
log "  android    = $(getprop ro.build.version.release 2>/dev/null)  sdk=$(getprop ro.build.version.sdk 2>/dev/null)"

log ""
log "[CPU] 拓扑"
for p in /sys/devices/system/cpu/possible /sys/devices/system/cpu/present /sys/devices/system/cpu/online /sys/devices/system/cpu/offline /sys/devices/system/cpu/kernel_max /sys/devices/system/cpu/isolated; do r "$p"; done

log ""
log "[CPU] cpufreq policy 目录"
for d in /sys/devices/system/cpu/cpufreq/policy*; do
  [ -d "$d" ] || continue
  log "==> $d"
  for f in related_cpus affected_cpus scaling_driver scaling_governor scaling_available_governors scaling_available_frequencies scaling_min_freq scaling_max_freq cpuinfo_min_freq cpuinfo_max_freq scaling_cur_freq cpuinfo_transition_latency scaling_boost_frequencies scaling_setspeed; do
    [ -e "$d/$f" ] && r "$d/$f"
  done
  if [ -d "$d/stats" ]; then for g in "$d/stats"/*; do [ -f "$g" ] && r "$g"; done; fi
done

log ""
log "[CPU] 每核心 cpufreq"
for d in /sys/devices/system/cpu/cpu[0-9]*; do
  [ -d "$d" ] || continue
  p="$d/cpufreq"
  if [ -d "$p" ]; then
    for f in scaling_driver scaling_governor scaling_cur_freq scaling_min_freq scaling_max_freq cpuinfo_min_freq cpuinfo_max_freq scaling_available_frequencies scaling_available_governors; do
      [ -e "$p/$f" ] && r "$p/$f"
    done
    [ -f "$p/stats/time_in_state" ] && r "$p/stats/time_in_state"
  fi
  [ -e "$d/online" ] && r "$d/online"
done

log ""
log "[CPU] boost / 触控加速"
for d in /sys/devices/system/cpu/cpufreq/boost /sys/module/cpu_boost/parameters /sys/module/cpu_input_boost/parameters /sys/module/msm_performance/parameters /sys/kernel/msm_performance /sys/module/input_boost/parameters /sys/devices/system/cpu/cpu_boost; do
  [ -d "$d" ] && rd "$d"
  [ -f "$d" ] && r "$d"
done

log ""
log "[CPU] core_ctl"
for pat in /sys/devices/system/cpu/cpu[0-9]*/core_ctl /sys/module/core_ctl/parameters /sys/kernel/core_ctl; do
  for x in $pat; do [ -d "$x" ] && rd "$x"; done
done

log ""
log "[CPU] 调度器 / WALT"
for pat in /sys/kernel/sched /sys/module/sched_walt/parameters /proc/sys/kernel/sched_*; do
  for x in $pat; do
    [ -d "$x" ] && rd "$x"
    [ -f "$x" ] && r "$x"
  done
done

log ""
log "[DEVFREQ] 所有 devfreq 设备"
for d in /sys/class/devfreq/*; do
  [ -d "$d" ] || continue
  log "==> $d"
  for f in "$d"/*; do [ -f "$f" ] && r "$f"; done
done

log ""
log "[GPU] 高通 KGSL"
for d in /sys/class/kgsl/kgsl-3d0 /sys/class/kgsl/kgsl-3d0/devfreq /sys/class/kgsl/kgsl-3d0/htw /sys/class/kgsl/kgsl-3d0/hwcg /sys/class/kgsl/kgsl-2d0 /sys/class/kgsl/kgsl-2d0/devfreq /sys/class/kgsl/kgsl-2d1 /sys/class/kgsl/kgsl-2d1/devfreq; do
  [ -e "$d" ] && rd "$d"
done

log ""
log "[GPU] 高通 devfreq 专用"
for d in /sys/class/devfreq/3d00000.qcom,kgsl-3d0 /sys/class/devfreq/5000000.qcom,kgsl-3d0 /sys/class/devfreq/*.qcom,kgsl-3d0 /sys/devices/platform/soc/*.qcom,kgsl-3d0/devfreq /sys/devices/platform/*.qcom,kgsl-3d0/devfreq; do
  for x in $d; do [ -d "$x" ] && rd "$x"; done
done

log ""
log "[GPU] MTK GED / Mali"
for d in /sys/kernel/ged /sys/kernel/ged/hal /sys/kernel/ged/hal/* /sys/module/ged/parameters /proc/ged /sys/class/devfreq/mtk-mali /sys/class/devfreq/mali0 /sys/class/devfreq/13000000.mali /sys/class/devfreq/gpufreq /proc/gpufreq /proc/gpufreqv2 /sys/kernel/gpu; do
  for x in $d; do
    [ -d "$x" ] && rd "$x"
    [ -f "$x" ] && r "$x"
  done
done

log ""
log "[GPU] 其他平台 Mali / Exynos / 麒麟"
for pat in /sys/class/misc/mali0 /sys/class/misc/mali0/device/devfreq /sys/devices/platform/*mali*/devfreq /sys/devices/platform/*.mali/devfreq /sys/devices/platform/soc/*mali*/devfreq /sys/devices/platform/soc/*.mali/devfreq /sys/kernel/gpu_boost /sys/module/gpu_boost/parameters /sys/module/mali/parameters; do
  for x in $pat; do
    [ -d "$x" ] && rd "$x"
    [ -f "$x" ] && r "$x"
  done
done

log ""
log "[GPU] 常用单点路径"
for p in /sys/class/kgsl/kgsl-3d0/gpu_available_frequencies /sys/class/kgsl/kgsl-3d0/gpu_available_governors /sys/class/kgsl/kgsl-3d0/gpu_cur_freq /sys/class/kgsl/kgsl-3d0/gpu_busy_percentage /sys/class/kgsl/kgsl-3d0/gpu_model /sys/class/kgsl/kgsl-3d0/gpu_clock /sys/class/kgsl/kgsl-3d0/max_gpuclk /sys/class/kgsl/kgsl-3d0/min_gpuclk /sys/class/kgsl/kgsl-3d0/max_pwrlevel /sys/class/kgsl/kgsl-3d0/min_pwrlevel /sys/class/kgsl/kgsl-3d0/default_pwrlevel /sys/class/kgsl/kgsl-3d0/num_pwrlevels /sys/class/kgsl/kgsl-3d0/devfreq/available_frequencies /sys/class/kgsl/kgsl-3d0/devfreq/cur_freq /sys/class/kgsl/kgsl-3d0/devfreq/min_freq /sys/class/kgsl/kgsl-3d0/devfreq/max_freq /sys/class/kgsl/kgsl-3d0/devfreq/governor /sys/class/kgsl/kgsl-3d0/devfreq/available_governors /sys/class/devfreq/3d00000.qcom,kgsl-3d0/available_frequencies /sys/class/devfreq/3d00000.qcom,kgsl-3d0/cur_freq /sys/class/devfreq/3d00000.qcom,kgsl-3d0/min_freq /sys/class/devfreq/3d00000.qcom,kgsl-3d0/max_freq /sys/class/devfreq/3d00000.qcom,kgsl-3d0/governor /sys/class/devfreq/3d00000.qcom,kgsl-3d0/available_governors /sys/class/devfreq/3d00000.qcom,kgsl-3d0/load /sys/class/devfreq/3d00000.qcom,kgsl-3d0/freq_table_mhz /sys/class/devfreq/mtk-mali/available_frequencies /sys/class/devfreq/mtk-mali/cur_freq /sys/class/devfreq/mtk-mali/min_freq /sys/class/devfreq/mtk-mali/max_freq /sys/class/devfreq/mtk-mali/governor /sys/class/devfreq/mali0/available_frequencies /sys/class/devfreq/mali0/cur_freq /sys/class/devfreq/mali0/min_freq /sys/class/devfreq/mali0/max_freq /sys/class/devfreq/mali0/governor /sys/kernel/gpu/gpu_max_clock /sys/kernel/gpu/gpu_min_clock /sys/kernel/gpu/gpu_clock /sys/kernel/gpu/gpu_model /sys/kernel/gpu/gpu_busy_percentage /sys/kernel/ged/hal/power_policy_cmd /sys/kernel/ged/hal/custom_boost_gpu_freq /sys/kernel/ged/hal/custom_upbound_gpu_freq /sys/kernel/ged/hal/gpu_boost_level /sys/kernel/ged/hal/gpu_freq /sys/kernel/ged/hal/gpu_utilization /proc/gpufreq/gpufreq_opp_freq /proc/gpufreq/gpufreq_opp_dump; do
  [ -e "$p" ] && r "$p"
done

log ""
log "[MATCH] 相关节点清单 (限定深度, 前 400 条)"
find /sys/class /sys/devices/system/cpu /sys/kernel /sys/module -maxdepth 4 2>/dev/null | grep -Ei '/(cpufreq|cpuidle|devfreq|mali|gpu|kgsl|ged|opp|core_ctl|msm_performance|cpu_boost|input_boost|sched)' | sort -u | head -400 | while read -r p; do log "  PATH  $p"; done

log ""
log "============================================================"
log " CPU/GPU PROBE END $(date '+%Y-%m-%d %H:%M:%S')"
log "============================================================"

mv "$TMP" "$OUT" 2>/dev/null || cp "$TMP" "$OUT"
chmod 0644 "$OUT" 2>/dev/null
printf '探测完成，日志：%s\n' "$OUT"
