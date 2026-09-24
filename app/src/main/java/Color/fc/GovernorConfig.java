package Color.fc;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 调速器脚本（conservative.sh / scx1.sh / scx2.sh / scx3.sh）的解析与生成
 */
public class GovernorConfig {

    public static class Gov {
        /** 以下字段未在脚本中解析到时为 null，由调用方按 上次保存镜像/默认值 兜底 */
        public String governor = null;       // 调速器名称
        public String upThreshold = null;   // 升频阈值 %
        public String downThreshold = null; // 降频阈值 %
        public String freqStep = null;      // 调频步进 %
        public String samplingRate = null;  // 采样周期 µs
        public String targetLoads = null;   // scx 目标负载 %
        public boolean ignoreNiceLoad = false; // conservative 忽略 nice 负载（模块 B/conservative.sh 原有）
        public String minFreq = null;       // CPU 最小频率限制 MHz（null/空/"0" = 不限制）
        public String maxFreq = null;       // CPU 最大频率限制 MHz（null/空/"0" = 不限制）
        /** 启用核心（索引0-7，true=online）。脚本无 online 行时保持全启用 */
        public final boolean[] cores = {true, true, true, true, true, true, true, true};
        /** 脚本是否含 online 行（无则 cores 视为未指定） */
        public boolean hasOnline = false;
    }

    /** 路径宽松匹配（\S*）：兼容 cpuN / policyN / 模块出厂脚本的 cpu$cpu 循环变量等写法，
     *  否则 scx1/2/3.sh（for cpu in 0 3 5 7）解析不到调速器名，保存后会被错误覆盖 */
    private static final Pattern P_GOV = Pattern.compile("echo \\\"?([\\w.-]+)\\\"? > \\S*scaling_governor");
    private static final Pattern P_UP = Pattern.compile("echo \\\"?([\\w.-]+)\\\"? > \\S*conservative/up_threshold");
    private static final Pattern P_DOWN = Pattern.compile("echo \\\"?([\\w.-]+)\\\"? > \\S*conservative/down_threshold");
    private static final Pattern P_STEP = Pattern.compile("echo \\\"?([\\w.-]+)\\\"? > \\S*conservative/freq_step");
    private static final Pattern P_RATE = Pattern.compile("echo \\\"?([\\w.-]+)\\\"? > \\S*conservative/sampling_rate");
    private static final Pattern P_NICE = Pattern.compile("echo \\\"?([01])\\\"? > \\S*conservative/ignore_nice_load");
    private static final Pattern P_LOADS = Pattern.compile("echo \\\"?([\\w.-]+)\\\"? > \\S*scx/target_loads");
    /** 引号可选：兼容生成的无引号与外部脚本的有引号两种写法 */
    private static final Pattern P_ONLINE = Pattern.compile("echo \\\"?([01])\\\"? > \\S*cpu(\\d+)/online");
    /** CPU 限频行（scaling_min_freq / scaling_max_freq，值为 kHz） */
    private static final Pattern P_MIN_FREQ = Pattern.compile("echo \\\"?([\\w.-]+)\\\"? > \\S*scaling_min_freq");
    private static final Pattern P_MAX_FREQ = Pattern.compile("echo \\\"?([\\w.-]+)\\\"? > \\S*scaling_max_freq");

    /** 解析调速器脚本，内容为空或完全无可识别行返回 null。未匹配到的字段保持 null（由调用方兜底） */
    public static Gov parse(String content, boolean conservative) {
        if (content == null || content.trim().isEmpty()) return null;
        Gov g = new Gov();
        int found = 0;
        try {
            Matcher mg = P_GOV.matcher(content);
            if (mg.find()) {
                g.governor = mg.group(1);
                found++;
            }
            if (conservative) {
                Matcher m = P_UP.matcher(content);
                if (m.find()) {
                    g.upThreshold = m.group(1);
                    found++;
                }
                m = P_DOWN.matcher(content);
                if (m.find()) {
                    g.downThreshold = m.group(1);
                    found++;
                }
                m = P_STEP.matcher(content);
                if (m.find()) {
                    g.freqStep = m.group(1);
                    found++;
                }
                m = P_RATE.matcher(content);
                if (m.find()) {
                    g.samplingRate = m.group(1);
                    found++;
                }
                m = P_NICE.matcher(content);
                if (m.find() && "1".equals(m.group(1))) {
                    g.ignoreNiceLoad = true;
                    found++;
                }
            } else {
                Matcher m = P_LOADS.matcher(content);
                if (m.find()) {
                    g.targetLoads = m.group(1);
                    found++;
                }
            }
            // 核心启用状态（脚本含 online 行才覆盖默认全开）
            Matcher mo = P_ONLINE.matcher(content);
            while (mo.find()) {
                g.hasOnline = true;
                found++;
                try {
                    int c = Integer.parseInt(mo.group(2));
                    if (c >= 0 && c < 8) g.cores[c] = "1".equals(mo.group(1));
                } catch (Exception ignored) {
                }
            }
            // CPU 限频（kHz → MHz，仅首个匹配）
            Matcher mf = P_MIN_FREQ.matcher(content);
            if (mf.find()) {
                try {
                    long k = Long.parseLong(mf.group(1));
                    if (k > 0) {
                        g.minFreq = String.valueOf(k / 1000);
                        found++;
                    }
                } catch (Exception ignored) {
                }
            }
            mf = P_MAX_FREQ.matcher(content);
            if (mf.find()) {
                try {
                    long k = Long.parseLong(mf.group(1));
                    if (k > 0) {
                        g.maxFreq = String.valueOf(k / 1000);
                        found++;
                    }
                } catch (Exception ignored) {
                }
            }
            if (found == 0) return null;
            return g;
        } catch (Exception e) {
            return null;
        }
    }

    /** 是否有核心被禁用 */
    private static boolean anyOff(boolean[] cores) {
        for (boolean c : cores) if (!c) return true;
        return false;
    }

    /** 脚本开头：先把启用的核拉回 online（必须无条件写 1，否则之前被关掉的核无法重新开启） */
    private static void appendCoreOn(StringBuilder sb, boolean[] cores) {
        for (int i = 0; i < 8; i++) {
            if (cores[i]) sb.append("echo 1 > /sys/devices/system/cpu/cpu").append(i).append("/online 2>/dev/null\n");
        }
        sb.append('\n');
    }

    /** 脚本结尾：关闭不用的核 */
    private static void appendCoreOff(StringBuilder sb, boolean[] cores) {
        if (!anyOff(cores)) return;
        for (int i = 0; i < 8; i++) {
            if (!cores[i]) sb.append("echo 0 > /sys/devices/system/cpu/cpu").append(i).append("/online 2>/dev/null\n");
        }
    }

    /** MHz 字符串 → kHz（无效/0 返回 0 = 不限制） */
    private static long mhzToKhz(String s) {
        try {
            long v = Long.parseLong(s.trim());
            return v > 0 ? v * 1000 : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /** 追加 CPU 限频行（最小/最大，任一 >0 才写；与调速器写入同一 base） */
    private static void appendFreqLimit(StringBuilder sb, String base, Gov g) {
        long mn = mhzToKhz(g.minFreq), mx = mhzToKhz(g.maxFreq);
        if (mn > 0) sb.append("echo ").append(mn).append(" > ").append(base).append("scaling_min_freq\n");
        if (mx > 0) sb.append("echo ").append(mx).append(" > ").append(base).append("scaling_max_freq\n");
    }

    /** 生成 conservative.sh（应用全部 CPU0-7）。参数行无条件写入：
     *  即使调速器不是 conservative 也保留参数行，保证界面回显不丢失（echo 失败仅跳过该行） */
    public static String generateConservative(Gov g) {
        return generateConservative(g, false);
    }

    /** 生成 conservative.sh。bVariant=方案2（B/conservative.sh）：
     *  保持模块出厂 B 脚本行为——写 ignore_nice_load=1 并关闭 game_opt 早检测 */
    public static String generateConservative(Gov g, boolean bVariant) {
        StringBuilder sb = new StringBuilder();
        appendCoreOn(sb, g.cores);
        for (int i = 0; i < 8; i++) {
            String base = "/sys/devices/system/cpu/cpu" + i + "/cpufreq/";
            sb.append("chmod 777 ").append(base).append("scaling_governor\n");
            sb.append("echo \"").append(g.governor).append("\" > ").append(base).append("scaling_governor\n");
            sb.append("echo \"").append(g.upThreshold).append("\" > ").append(base).append("conservative/up_threshold\n");
            sb.append("echo \"").append(g.downThreshold).append("\" > ").append(base).append("conservative/down_threshold\n");
            sb.append("echo \"").append(g.freqStep).append("\" > ").append(base).append("conservative/freq_step\n");
            sb.append("echo \"").append(g.samplingRate).append("\" > ").append(base).append("conservative/sampling_rate\n");
            if (g.ignoreNiceLoad || bVariant) {
                sb.append("echo \"1\" > ").append(base).append("conservative/ignore_nice_load\n");
            }
            appendFreqLimit(sb, base, g);
            sb.append('\n');
        }
        if (bVariant) {
            // 与模块出厂 B/conservative.sh 一致：关闭 game_opt 早检测
            sb.append("echo \"0\" > /proc/game_opt/early_detect/ed_enable 2>/dev/null\n");
        }
        appendCoreOff(sb, g.cores);
        return sb.toString();
    }

    /** 生成 scx1.sh / scx2.sh（CPU 0/3/5/7）。负载行无条件写入（非 scx 时节点不存在则跳过该行，不影响回显） */
    public static String generateScx(Gov g) {
        StringBuilder sb = new StringBuilder();
        appendCoreOn(sb, g.cores);
        int[] cpus = {0, 3, 5, 7};
        for (int cpu : cpus) {
            String base = "/sys/devices/system/cpu/cpu" + cpu + "/cpufreq/";
            sb.append("chmod 777 ").append(base).append("scaling_governor\n");
        }
        sb.append('\n');
        for (int cpu : cpus) {
            String base = "/sys/devices/system/cpu/cpu" + cpu + "/cpufreq/";
            sb.append("echo \"").append(g.governor).append("\" > ").append(base).append("scaling_governor\n");
        }
        for (int cpu : cpus) {
            String base = "/sys/devices/system/cpu/cpu" + cpu + "/cpufreq/";
            sb.append("echo \"").append(g.targetLoads).append("\" > ").append(base).append("scx/target_loads\n");
            appendFreqLimit(sb, base, g);
        }
        // 与模块出厂 scx1/2.sh 尾部一致：启用 hmbird scx 调度与 game_opt 早检测
        sb.append("echo \"1\" > /proc/hmbird_sched/scx_enable 2>/dev/null\n");
        sb.append("echo \"1\" > /proc/game_opt/early_detect/ed_enable 2>/dev/null\n");
        appendCoreOff(sb, g.cores);
        return sb.toString();
    }

    /** 生成 freq{mode}.sh：在方案 conf 的 json_cpu_max_min 之后重新应用调速器页设置的限频。
     *  未设置限频时仅 exit 0（保留调度的限频不动）。modeIdx：0/3=CPU0-7，1/2=CPU 0/3/5/7 */
    public static String generateFreqScript(Gov g, int modeIdx) {
        long mn = mhzToKhz(g.minFreq), mx = mhzToKhz(g.maxFreq);
        if (mn <= 0 && mx <= 0) return "exit 0\n";
        int[] cpus = (modeIdx == 0 || modeIdx == 3)
                ? new int[]{0, 1, 2, 3, 4, 5, 6, 7} : new int[]{0, 3, 5, 7};
        StringBuilder sb = new StringBuilder();
        for (int c : cpus) {
            String base = "/sys/devices/system/cpu/cpu" + c + "/cpufreq/";
            if (mn > 0) sb.append("echo ").append(mn).append(" > ").append(base).append("scaling_min_freq\n");
            if (mx > 0) sb.append("echo ").append(mx).append(" > ").append(base).append("scaling_max_freq\n");
        }
        return sb.toString();
    }

    /** 生成 json_cpu_max_min 复现脚本（替代原 ELF）：
     *  复现原二进制逻辑——遍历各 policy，读 cpuinfo_max_freq 按两个百分比参数乘算后写入
     *  scaling_min/max_freq；写前先复位到 cpuinfo 上下限避免顺序约束，写后 chmod 444 复锁
     *  （与原 ELF 的 777→写→444 行为一致）。
     *  注意：调速器页限频（freq{mode}.sh）由方案 conf 中的 freq 行应用（conf 被 main.sh
     *  source 执行时 $action 在同 shell 可见），本脚本作为子进程拿不到 $action，不在此处理 */
    public static String generateJmmScript() {
        StringBuilder sb = new StringBuilder();
        sb.append("#!/system/bin/sh\n");
        sb.append("mx=\"$1\"; mn=\"$2\"\n");
        sb.append("BASE=/sys/devices/system/cpu/cpufreq\n");
        sb.append("if [ -n \"$mx\" ]; then\n");
        sb.append("  for p in $BASE/policy*; do\n");
        sb.append("    [ -f \"$p/cpuinfo_max_freq\" ] || continue\n");
        sb.append("    imf=$(cat \"$p/cpuinfo_max_freq\" 2>/dev/null)\n");
        sb.append("    imn=$(cat \"$p/cpuinfo_min_freq\" 2>/dev/null)\n");
        sb.append("    [ -n \"$imf\" ] || continue\n");
        sb.append("    [ -n \"$mn\" ] || mn=0\n");
        sb.append("    vmax=$((imf * mx / 100))\n");
        sb.append("    vmin=$((imf * mn / 100))\n");
        sb.append("    chmod 777 \"$p/scaling_max_freq\" \"$p/scaling_min_freq\" 2>/dev/null\n");
        sb.append("    echo \"$imf\" > \"$p/scaling_max_freq\" 2>/dev/null\n");
        sb.append("    echo \"$imn\" > \"$p/scaling_min_freq\" 2>/dev/null\n");
        sb.append("    echo \"$vmax\" > \"$p/scaling_max_freq\" 2>/dev/null\n");
        sb.append("    echo \"$vmin\" > \"$p/scaling_min_freq\" 2>/dev/null\n");
        sb.append("    chmod 444 \"$p/scaling_max_freq\" \"$p/scaling_min_freq\" 2>/dev/null\n");
        sb.append("  done\n");
        sb.append("fi\n");
        sb.append("exit 0\n");
        return sb.toString();
    }

    /** 生成 scx3.sh（全部 CPU0-7，切换调速器 + 负载。负载行无条件写入，非 scx 时节点不存在则跳过该行） */
    public static String generateScx3(Gov g) {
        StringBuilder sb = new StringBuilder();
        appendCoreOn(sb, g.cores);
        for (int i = 0; i < 8; i++) {
            String base = "/sys/devices/system/cpu/cpu" + i + "/cpufreq/";
            sb.append("chmod 777 ").append(base).append("scaling_governor\n");
        }
        sb.append('\n');
        for (int i = 0; i < 8; i++) {
            String base = "/sys/devices/system/cpu/cpu" + i + "/cpufreq/";
            sb.append("echo \"").append(g.governor).append("\" > ").append(base).append("scaling_governor\n");
        }
        for (int i = 0; i < 8; i++) {
            String base = "/sys/devices/system/cpu/cpu" + i + "/cpufreq/";
            sb.append("echo \"").append(g.targetLoads).append("\" > ").append(base).append("scx/target_loads\n");
            appendFreqLimit(sb, base, g);
        }
        // 与模块出厂 scx3.sh 尾部一致：启用 game_opt 早检测
        sb.append("echo \"1\" > /proc/game_opt/early_detect/ed_enable 2>/dev/null\n");
        appendCoreOff(sb, g.cores);
        return sb.toString();
    }
}
