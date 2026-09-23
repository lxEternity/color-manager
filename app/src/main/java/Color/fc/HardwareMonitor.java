package Color.fc;

/**
 * 硬件实时状态采样与解析：
 * 一次 root 扫描同时取 GPU 频率 / CPU 最大频率 / 全部温区，多字段复用
 */
public final class HardwareMonitor {

    /** 一次 root 扫描：CPU 聚合 stat / GPU 频率 / CPU 最大频率 / 全部温区 */
    public static final String SCAN =
            "echo \"C:$(head -n1 /proc/stat)\";"
                    + "g=$(cat /sys/class/kgsl/kgsl-3d0/gpuclk 2>/dev/null);"
                    + "[ -n \"$g\" ] || g=$(cat /sys/class/kgsl/kgsl-3d0/devfreq/cur_freq 2>/dev/null);"
                    + "[ -n \"$g\" ] || g=$(cat /sys/class/devfreq/*qcom,gpu*/cur_freq 2>/dev/null);"
                    + "[ -n \"$g\" ] || g=$(cat /sys/class/devfreq/*gpu*/cur_freq 2>/dev/null);"
                    + "echo \"G:$g\";"
                    + "f=$(for p in /sys/devices/system/cpu/cpufreq/policy*; do "
                    + "cat $p/scaling_cur_freq 2>/dev/null; done | sort -n | tail -1);"
                    + "echo \"F:$f\";"
                    + "for z in /sys/class/thermal/thermal_zone*; do "
                    + "[ -f \"$z/temp\" ] || continue; "
                    + "echo \"T:$(cat \"$z/type\" 2>/dev/null):$(cat \"$z/temp\" 2>/dev/null)\"; done";

    private HardwareMonitor() {
    }

    /** /proc/stat 聚合行（root 读取，应用进程被 SELinux 拦截）→ {idle, total} */
    public static long[] procStat(String out) {
        try {
            for (String line : out.split("\\n")) {
                if (!line.startsWith("C:")) continue;
                String[] p = line.substring(2).trim().split("\\s+");
                if (p.length < 6 || !"cpu".equals(p[0])) continue;
                long idle = Long.parseLong(p[4]) + Long.parseLong(p[5]);   // idle + iowait
                long total = 0;
                for (int i = 1; i < p.length; i++) total += Long.parseLong(p[i]);
                return new long[]{idle, total};
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** GPU 频率（Hz）：kgsl gpuclk 直接为 MHz，devfreq 为 Hz */
    public static double gpuHz(String out) {
        try {
            for (String line : out.split("\\n")) {
                if (line.startsWith("G:")) {
                    double v = Double.parseDouble(line.substring(2).trim());
                    if (v <= 0) return 0;
                    return v < 3000 ? v * 1e6 : v;   // MHz → Hz
                }
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    /** CPU 最大频率（kHz） */
    public static double cpuMaxKHz(String out) {
        try {
            for (String line : out.split("\\n")) {
                if (line.startsWith("F:")) {
                    return Double.parseDouble(line.substring(2).trim());
                }
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    /**
     * 分类温区最大值（℃）：
     * - cpu: 含 cpu/apc/big 的温区，回退电池温度
     * - soc: 含 soc 的温区，回退 CPU 温度
     * - gpu: 含 gpu 的温区，回退 SOC 温度
     */
    public static double zoneTemp(String out, String kind, double fallback) {
        double max = 0;
        try {
            for (String line : out.split("\\n")) {
                if (!line.startsWith("T:")) continue;
                String body = line.substring(2);
                int idx = body.lastIndexOf(':');
                if (idx <= 0) continue;
                String ty = body.substring(0, idx).toLowerCase();
                double v = Double.parseDouble(body.substring(idx + 1).trim());
                if (v > 1000) v = v / 1000.0;              // 毫摄氏度 → ℃
                if (v <= 0 || v > 120) continue;
                boolean hit;
                if ("cpu".equals(kind)) {
                    hit = ty.contains("cpu") || ty.contains("apc") || ty.contains("big");
                } else if ("soc".equals(kind)) {
                    hit = ty.contains("soc");
                } else {
                    hit = ty.contains("gpu");
                }
                if (hit) max = Math.max(max, v);
            }
        } catch (Exception ignored) {
        }
        return Math.max(max, fallback);
    }
}
