package Color.fc;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 调速器脚本（conservative.sh / scx1.sh / scx2.sh / scx3.sh）的解析与生成
 */
public class GovernorConfig {

    public static class Gov {
        public String governor = "conservative"; // 调速器名称
        public String upThreshold = "98";       // 升频阈值 %
        public String downThreshold = "93";     // 降频阈值 %
        public String freqStep = "1";           // 调频步进 %
        public String samplingRate = "14000";   // 采样周期 µs
        public String targetLoads = "90";       // scx 目标负载 %
        /** 启用核心（索引0-7，true=online）。旧脚本无 online 行时全启用 */
        public final boolean[] cores = {true, true, true, true, true, true, true, true};
    }

    private static final Pattern P_GOV = Pattern.compile("echo \"([^\"]+)\" > \\S*cpu\\d+/cpufreq/scaling_governor");
    private static final Pattern P_UP = Pattern.compile("echo \"(\\S+)\" > \\S*conservative/up_threshold");
    private static final Pattern P_DOWN = Pattern.compile("echo \"(\\S+)\" > \\S*conservative/down_threshold");
    private static final Pattern P_STEP = Pattern.compile("echo \"(\\S+)\" > \\S*conservative/freq_step");
    private static final Pattern P_RATE = Pattern.compile("echo \"(\\S+)\" > \\S*conservative/sampling_rate");
    private static final Pattern P_LOADS = Pattern.compile("echo \"(\\S+)\" > \\S*scx/target_loads");
    private static final Pattern P_ONLINE = Pattern.compile("echo \"([01])\" > \\S*cpu(\\d+)/online");

    /** 解析调速器脚本，内容为空返回 null */
    public static Gov parse(String content, boolean conservative) {
        if (content == null || content.trim().isEmpty()) return null;
        Gov g = new Gov();
        try {
            Matcher mg = P_GOV.matcher(content);
            if (mg.find()) g.governor = mg.group(1);
            if (conservative) {
                Matcher m = P_UP.matcher(content);
                if (m.find()) g.upThreshold = m.group(1);
                m = P_DOWN.matcher(content);
                if (m.find()) g.downThreshold = m.group(1);
                m = P_STEP.matcher(content);
                if (m.find()) g.freqStep = m.group(1);
                m = P_RATE.matcher(content);
                if (m.find()) g.samplingRate = m.group(1);
            } else {
                Matcher m = P_LOADS.matcher(content);
                if (m.find()) g.targetLoads = m.group(1);
            }
            // 核心启用状态（脚本含 online 行才覆盖默认全开）
            Matcher mo = P_ONLINE.matcher(content);
            while (mo.find()) {
                try {
                    int c = Integer.parseInt(mo.group(2));
                    if (c >= 0 && c < 8) g.cores[c] = "1".equals(mo.group(1));
                } catch (Exception ignored) {
                }
            }
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

    /** 脚本开头：先把要用的核拉回 online（保证后续调速器写入生效） */
    private static void appendCoreOn(StringBuilder sb, boolean[] cores) {
        if (!anyOff(cores)) return;   // 全开时不产生任何 online 行（兼容旧脚本）
        for (int i = 0; i < 8; i++) {
            if (cores[i]) sb.append("echo 1 > /sys/devices/system/cpu/cpu").append(i).append("/online\n");
        }
        sb.append('\n');
    }

    /** 脚本结尾：关闭不用的核 */
    private static void appendCoreOff(StringBuilder sb, boolean[] cores) {
        if (!anyOff(cores)) return;
        for (int i = 0; i < 8; i++) {
            if (!cores[i]) sb.append("echo 0 > /sys/devices/system/cpu/cpu").append(i).append("/online\n");
        }
    }

    /** 生成 conservative.sh（应用全部 CPU0-7） */
    public static String generateConservative(Gov g) {
        StringBuilder sb = new StringBuilder();
        appendCoreOn(sb, g.cores);
        boolean isCons = "conservative".equals(g.governor);
        for (int i = 0; i < 8; i++) {
            String base = "/sys/devices/system/cpu/cpu" + i + "/cpufreq/";
            sb.append("chmod 777 ").append(base).append("scaling_governor\n");
            sb.append("echo \"").append(g.governor).append("\" > ").append(base).append("scaling_governor\n");
            if (isCons) {
                sb.append("echo \"").append(g.upThreshold).append("\" > ").append(base).append("conservative/up_threshold\n");
                sb.append("echo \"").append(g.downThreshold).append("\" > ").append(base).append("conservative/down_threshold\n");
                sb.append("echo \"").append(g.freqStep).append("\" > ").append(base).append("conservative/freq_step\n");
                sb.append("echo \"").append(g.samplingRate).append("\" > ").append(base).append("conservative/sampling_rate\n");
            }
            sb.append('\n');
        }
        appendCoreOff(sb, g.cores);
        return sb.toString();
    }

    /** 生成 scx1.sh / scx2.sh（CPU 0/3/5/7） */
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
        if ("scx".equals(g.governor)) {
            for (int cpu : cpus) {
                String base = "/sys/devices/system/cpu/cpu" + cpu + "/cpufreq/";
                sb.append("echo \"").append(g.targetLoads).append("\" > ").append(base).append("scx/target_loads\n");
            }
        }
        appendCoreOff(sb, g.cores);
        return sb.toString();
    }

    /** 生成 scx3.sh（全部 CPU0-7，仅切换调速器） */
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
        appendCoreOff(sb, g.cores);
        return sb.toString();
    }
}
