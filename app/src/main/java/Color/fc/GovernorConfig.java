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
        /** 小核最小频率限制 MHz（null/空/"0" = 不限制，照搬 Kin 小核/大核分簇方案） */
        public String minFreqL = null;
        /** 小核最大频率限制 MHz */
        public String maxFreqL = null;
        /** 大核最小频率限制 MHz */
        public String minFreqB = null;
        /** 大核最大频率限制 MHz */
        public String maxFreqB = null;
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
    /** CPU 限频行（scaling_min_freq / scaling_max_freq，值为 kHz，旧版单对格式兼容用） */
    private static final Pattern P_MIN_FREQ = Pattern.compile("echo \\\"?([\\w.-]+)\\\"? > \\S*scaling_min_freq");
    private static final Pattern P_MAX_FREQ = Pattern.compile("echo \\\"?([\\w.-]+)\\\"? > \\S*scaling_max_freq");
    /** 新版限频标记（kHz，freqLimitBlock 生成）：#FREQ L:<min> <max> / #FREQ B:<min> <max> */
    private static final Pattern P_FREQ_L = Pattern.compile("#FREQ L:(\\d+) (\\d+)");
    private static final Pattern P_FREQ_B = Pattern.compile("#FREQ B:(\\d+) (\\d+)");

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
            // CPU 限频（新格式：#FREQ 标记，小核/大核分开）
            Matcher fm = P_FREQ_L.matcher(content);
            if (fm.find()) {
                g.minFreqL = khzToMhz(fm.group(1));
                g.maxFreqL = khzToMhz(fm.group(2));
                found++;
            }
            fm = P_FREQ_B.matcher(content);
            if (fm.find()) {
                g.minFreqB = khzToMhz(fm.group(1));
                g.maxFreqB = khzToMhz(fm.group(2));
                found++;
            }
            // 旧格式兼容：单对 scaling_min/max（小/大核同值）
            if (g.minFreqL == null && g.maxFreqL == null && g.minFreqB == null && g.maxFreqB == null) {
                Matcher mf = P_MIN_FREQ.matcher(content);
                if (mf.find()) {
                    String v = khzToMhz(mf.group(1));
                    if (v != null && !"0".equals(v)) {
                        g.minFreqL = g.minFreqB = v;
                        found++;
                    }
                }
                mf = P_MAX_FREQ.matcher(content);
                if (mf.find()) {
                    String v = khzToMhz(mf.group(1));
                    if (v != null && !"0".equals(v)) {
                        g.maxFreqL = g.maxFreqB = v;
                        found++;
                    }
                }
            }
            if (found == 0) return null;
            return g;
        } catch (Exception e) {
            return null;
        }
    }

    /** 核心开关不再写入脚本：改为即时写 sysfs（见 CpuCoreManager，照搬 Kin-app），
     *  切换调速方案不影响手动开关的核心状态 */

    /** MHz 字符串 → kHz（无效/0 返回 0 = 不限制） */
    private static long mhzToKhz(String s) {
        try {
            long v = Long.parseLong(s.trim());
            return v > 0 ? v * 1000 : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /** kHz 字符串 → MHz（无效返回 null，0 → "0"） */
    private static String khzToMhz(String s) {
        try {
            long k = Long.parseLong(s.trim());
            return String.valueOf(k / 1000);
        } catch (Exception e) {
            return null;
        }
    }

    /** 小核/大核限频块（照搬 Kin 分簇方案）：#FREQ 标记（回读解析用）+ 运行时按
     *  cpuinfo_max_freq 分簇（最高簇=大核，其余=小核）写 policy 级 scaling_min/max。
     *  写前先复位到 cpuinfo 上下限避免顺序约束，写后 chmod 444 复锁（与原 ELF 一致）。
     *  四项全部未设置时返回空串（不产生任何行） */
    static String freqLimitBlock(Gov g) {
        long mnL = mhzToKhz(g.minFreqL), mxL = mhzToKhz(g.maxFreqL);
        long mnB = mhzToKhz(g.minFreqB), mxB = mhzToKhz(g.maxFreqB);
        if (mnL <= 0 && mxL <= 0 && mnB <= 0 && mxB <= 0) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("#FREQ L:").append(mnL).append(' ').append(mxL).append('\n');
        sb.append("#FREQ B:").append(mnB).append(' ').append(mxB).append('\n');
        sb.append("gm=0\n");
        sb.append("for p in /sys/devices/system/cpu/cpufreq/policy*; do\n");
        sb.append("  f=$(cat \"$p/cpuinfo_max_freq\" 2>/dev/null)\n");
        sb.append("  [ -n \"$f\" ] && [ \"$f\" -gt \"$gm\" ] 2>/dev/null && gm=$f\n");
        sb.append("done\n");
        sb.append("if [ \"$gm\" -gt 0 ] 2>/dev/null; then\n");
        sb.append("  for p in /sys/devices/system/cpu/cpufreq/policy*; do\n");
        sb.append("    imf=$(cat \"$p/cpuinfo_max_freq\" 2>/dev/null)\n");
        sb.append("    [ -n \"$imf\" ] || continue\n");
        sb.append("    if [ \"$imf\" -lt \"$gm\" ] 2>/dev/null; then mn=").append(mnL)
                .append("; mx=").append(mxL).append("; else mn=").append(mnB)
                .append("; mx=").append(mxB).append("; fi\n");
        sb.append("    imn=$(cat \"$p/cpuinfo_min_freq\" 2>/dev/null)\n");
        sb.append("    chmod 777 \"$p/scaling_min_freq\" \"$p/scaling_max_freq\" 2>/dev/null\n");
        sb.append("    echo \"$imf\" > \"$p/scaling_max_freq\" 2>/dev/null\n");
        sb.append("    [ -n \"$imn\" ] && echo \"$imn\" > \"$p/scaling_min_freq\" 2>/dev/null\n");
        sb.append("    [ \"$mn\" -gt 0 ] 2>/dev/null && echo \"$mn\" > \"$p/scaling_min_freq\" 2>/dev/null\n");
        sb.append("    [ \"$mx\" -gt 0 ] 2>/dev/null && echo \"$mx\" > \"$p/scaling_max_freq\" 2>/dev/null\n");
        sb.append("    chmod 444 \"$p/scaling_min_freq\" \"$p/scaling_max_freq\" 2>/dev/null\n");
        sb.append("  done\n");
        sb.append("fi\n");
        return sb.toString();
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
            sb.append('\n');
        }
        String fb = freqLimitBlock(g);
        if (!fb.isEmpty()) sb.append(fb);
        if (bVariant) {
            // 与模块出厂 B/conservative.sh 一致：关闭 game_opt 早检测
            sb.append("echo \"0\" > /proc/game_opt/early_detect/ed_enable 2>/dev/null\n");
        }
        return sb.toString();
    }

    /** 生成 scx1.sh / scx2.sh（CPU 0/3/5/7）。负载行无条件写入（非 scx 时节点不存在则跳过该行，不影响回显） */
    public static String generateScx(Gov g) {
        StringBuilder sb = new StringBuilder();
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
        }
        String fb = freqLimitBlock(g);
        if (!fb.isEmpty()) sb.append(fb);
        // 与模块出厂 scx1/2.sh 尾部一致：启用 hmbird scx 调度与 game_opt 早检测
        sb.append("echo \"1\" > /proc/hmbird_sched/scx_enable 2>/dev/null\n");
        sb.append("echo \"1\" > /proc/game_opt/early_detect/ed_enable 2>/dev/null\n");
        return sb.toString();
    }

    /** 生成 freq{mode}.sh：在方案 conf 的 json_cpu_max_min 之后重新应用调速器页设置的
     *  小核/大核限频（policy 级分簇，运行时按 cpuinfo_max_freq 判定小核/大核）。
     *  未设置限频时仅 exit 0（保留调度的限频不动） */
    public static String generateFreqScript(Gov g) {
        String fb = freqLimitBlock(g);
        if (fb.isEmpty()) return "exit 0\n";
        return "#!/system/bin/sh\n" + fb + "exit 0\n";
    }

    /** 生成 json_cpu_max_min 复现脚本（替代原 ELF，照搬 Kin FSM 小核/大核/GPU 限频方案）：
     *  新 4 参：json_cpu_max_min <小核上限%> <大核上限%> <下限%> <GPU上限%>
     *    - 小核/大核按各 policy 的 cpuinfo_max_freq 分簇（最高簇=大核，其余=小核），
     *      上限 = 本簇 cpuinfo_max_freq × 百分比（Kin FSM maxL/maxB 语义）
     *    - 下限% 对全部 policy 生效（各簇按自身最高频折算，钳到 cpuinfo_min）
     *    - GPU 上限% 按可用档位最高频折算写 devfreq max_freq；0/100 或越大越接近解除
     *  旧 2 参（兼容未重存的 conf / 原 ELF 语义）：参数一=上限%（小/大核同值），参数二=下限%
     *  写前先复位到 cpuinfo 上下限避免顺序约束，写后 chmod 444 复锁（与原 ELF 行为一致）。
     *  注意：调速器页限频（freq{mode}.sh）由方案 conf 中的 freq 行应用（conf 被 main.sh
     *  source 执行时 $action 在同 shell 可见），本脚本作为子进程拿不到 $action，不在此处理 */
    public static String generateJmmScript() {
        StringBuilder sb = new StringBuilder();
        sb.append("#!/system/bin/sh\n");
        sb.append("BASE=/sys/devices/system/cpu/cpufreq\n");
        sb.append("case $# in\n");
        sb.append("  4) mL=$1; mB=$2; mn=$3; gpu=$4;;\n");
        sb.append("  2) mL=$1; mB=$1; mn=$2; gpu=0;;\n");
        sb.append("  *) exit 0;;\n");
        sb.append("esac\n");
        sb.append("gm=0\n");
        sb.append("for p in $BASE/policy*; do\n");
        sb.append("  [ -f \"$p/cpuinfo_max_freq\" ] || continue\n");
        sb.append("  f=$(cat \"$p/cpuinfo_max_freq\" 2>/dev/null)\n");
        sb.append("  [ -n \"$f\" ] && [ \"$f\" -gt \"$gm\" ] 2>/dev/null && gm=$f\n");
        sb.append("done\n");
        sb.append("for p in $BASE/policy*; do\n");
        sb.append("  [ -f \"$p/cpuinfo_max_freq\" ] || continue\n");
        sb.append("  imf=$(cat \"$p/cpuinfo_max_freq\" 2>/dev/null)\n");
        sb.append("  [ -n \"$imf\" ] || continue\n");
        sb.append("  pct=$mL\n");
        sb.append("  [ \"$gm\" -gt 0 ] && [ \"$imf\" -eq \"$gm\" ] 2>/dev/null && pct=$mB\n");
        sb.append("  vmax=$((imf * pct / 100))\n");
        sb.append("  imn=$(cat \"$p/cpuinfo_min_freq\" 2>/dev/null)\n");
        sb.append("  vmin=$((imf * mn / 100))\n");
        sb.append("  [ -n \"$imn\" ] && [ \"$vmin\" -lt \"$imn\" ] 2>/dev/null && vmin=$imn\n");
        sb.append("  chmod 777 \"$p/scaling_max_freq\" \"$p/scaling_min_freq\" 2>/dev/null\n");
        sb.append("  echo \"$imf\" > \"$p/scaling_max_freq\" 2>/dev/null\n");
        sb.append("  [ -n \"$imn\" ] && echo \"$imn\" > \"$p/scaling_min_freq\" 2>/dev/null\n");
        sb.append("  echo \"$vmax\" > \"$p/scaling_max_freq\" 2>/dev/null\n");
        sb.append("  echo \"$vmin\" > \"$p/scaling_min_freq\" 2>/dev/null\n");
        sb.append("  chmod 444 \"$p/scaling_max_freq\" \"$p/scaling_min_freq\" 2>/dev/null\n");
        sb.append("done\n");
        // GPU 上限（kgsl devfreq 优先，通用 devfreq 兜底；0/100=解除到硬件最高档）
        sb.append("for d in /sys/class/kgsl/kgsl-3d0/devfreq /sys/class/devfreq/*kgsl* /sys/class/devfreq/*gpu*; do\n");
        sb.append("  [ -f \"$d/available_frequencies\" ] || continue\n");
        sb.append("  hw=$(cat \"$d/available_frequencies\" 2>/dev/null | tr ' ' '\\n' | sort -n | tail -1)\n");
        sb.append("  [ -n \"$hw\" ] || continue\n");
        sb.append("  v=$hw\n");
        sb.append("  [ \"$gpu\" -gt 0 ] 2>/dev/null && [ \"$gpu\" -lt 100 ] 2>/dev/null && v=$((hw * gpu / 100))\n");
        sb.append("  echo \"$hw\" > \"$d/max_freq\" 2>/dev/null\n");
        sb.append("  echo \"$v\" > \"$d/max_freq\" 2>/dev/null\n");
        sb.append("done\n");
        sb.append("exit 0\n");
        return sb.toString();
    }

    /** 生成 scx3.sh（全部 CPU0-7，切换调速器 + 负载。负载行无条件写入，非 scx 时节点不存在则跳过该行） */
    public static String generateScx3(Gov g) {
        StringBuilder sb = new StringBuilder();
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
        }
        String fb = freqLimitBlock(g);
        if (!fb.isEmpty()) sb.append(fb);
        // 与模块出厂 scx3.sh 尾部一致：启用 game_opt 早检测
        sb.append("echo \"1\" > /proc/game_opt/early_detect/ed_enable 2>/dev/null\n");
        return sb.toString();
    }
}
