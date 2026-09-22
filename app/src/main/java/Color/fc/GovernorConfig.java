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
    }

    private static final Pattern P_GOV = Pattern.compile("echo \"([^\"]+)\" > \\S*cpu\\d+/cpufreq/scaling_governor");
    private static final Pattern P_UP = Pattern.compile("echo \"(\\S+)\" > \\S*conservative/up_threshold");
    private static final Pattern P_DOWN = Pattern.compile("echo \"(\\S+)\" > \\S*conservative/down_threshold");
    private static final Pattern P_STEP = Pattern.compile("echo \"(\\S+)\" > \\S*conservative/freq_step");
    private static final Pattern P_RATE = Pattern.compile("echo \"(\\S+)\" > \\S*conservative/sampling_rate");
    private static final Pattern P_LOADS = Pattern.compile("echo \"(\\S+)\" > \\S*scx/target_loads");

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
            return g;
        } catch (Exception e) {
            return null;
        }
    }

    /** 生成 conservative.sh（应用全部 CPU0-7） */
    public static String generateConservative(Gov g) {
        StringBuilder sb = new StringBuilder();
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
        return sb.toString();
    }

    /** 生成 scx1.sh / scx2.sh（CPU 0/3/5/7） */
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
        if ("scx".equals(g.governor)) {
            for (int cpu : cpus) {
                String base = "/sys/devices/system/cpu/cpu" + cpu + "/cpufreq/";
                sb.append("echo \"").append(g.targetLoads).append("\" > ").append(base).append("scx/target_loads\n");
            }
        }
        return sb.toString();
    }

    /** 生成 scx3.sh（全部 CPU0-7，仅切换调速器） */
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
        return sb.toString();
    }
}
