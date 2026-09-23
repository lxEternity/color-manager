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
        /** 启用核心（索引0-7，true=online）。脚本无 online 行时保持全启用 */
        public final boolean[] cores = {true, true, true, true, true, true, true, true};
        /** 脚本是否含 online 行（无则 cores 视为未指定） */
        public boolean hasOnline = false;
    }

    /** 引号可选 + 兼容 cpu/policy 两种路径写法，避免生成的与外部脚本格式不一致导致解析失败 */
    private static final Pattern P_GOV = Pattern.compile("echo \\\"?([\\w.-]+)\\\"? > \\S*(?:cpu\\d+/cpufreq|cpufreq/policy\\d+)/scaling_governor");
    private static final Pattern P_UP = Pattern.compile("echo \\\"?([\\w.-]+)\\\"? > \\S*conservative/up_threshold");
    private static final Pattern P_DOWN = Pattern.compile("echo \\\"?([\\w.-]+)\\\"? > \\S*conservative/down_threshold");
    private static final Pattern P_STEP = Pattern.compile("echo \\\"?([\\w.-]+)\\\"? > \\S*conservative/freq_step");
    private static final Pattern P_RATE = Pattern.compile("echo \\\"?([\\w.-]+)\\\"? > \\S*conservative/sampling_rate");
    private static final Pattern P_LOADS = Pattern.compile("echo \\\"?([\\w.-]+)\\\"? > \\S*scx/target_loads");
    /** 引号可选：兼容生成的无引号与外部脚本的有引号两种写法 */
    private static final Pattern P_ONLINE = Pattern.compile("echo \\\"?([01])\\\"? > \\S*cpu(\\d+)/online");

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

    /** 生成 conservative.sh（应用全部 CPU0-7）。参数行无条件写入：
     *  即使调速器不是 conservative 也保留参数行，保证界面回显不丢失（echo 失败仅跳过该行） */
    public static String generateConservative(Gov g) {
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
            sb.append('\n');
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
