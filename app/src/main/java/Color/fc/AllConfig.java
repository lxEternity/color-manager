package Color.fc;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * a.all.sh / b.all.sh 调度配置的解析与生成
 */
public class AllConfig {

    public static class Mode {
        public String opt2 = "0";
        public String cpuMax = "42";
        public String cpuMin = "5";
        public String llcc = "350000";
        public String uclampDisplay = "6";
        public String uclampSsfg = "5";
        public String uclampTouch = "8";
        public String uclampMm = "6";
        public String uclampRt = "3";
        public String uclampTopApp = "10";
        public String walt1 = "0";   // 仅极速模式
        public String walt2 = "1500";// 仅极速模式
    }

    public final Map<String, Mode> modes = new LinkedHashMap<>();

    public static final String[] MODE_KEYS = {"powersave", "balance", "performance", "fast"};
    public static final String[] MODE_NAMES = {"省电模式", "均衡模式", "性能模式", "极速模式"};
    public static final String[] MODE_DESC = {
            "powersave · 最长续航，压制频率与提升值",
            "balance · 日常使用，兼顾流畅与功耗",
            "performance · 高负载场景，激进提升",
            "fast · 极限性能，全力释放"
    };

    /** 默认参数（a/b 初始一致） */
    public static AllConfig defaults() {
        AllConfig c = new AllConfig();
        Mode ps = new Mode();
        ps.opt2 = "0"; ps.cpuMax = "42"; ps.cpuMin = "5"; ps.llcc = "350000";
        ps.uclampDisplay = "6"; ps.uclampSsfg = "5"; ps.uclampTouch = "8";
        ps.uclampMm = "6"; ps.uclampRt = "3"; ps.uclampTopApp = "10";
        Mode bl = new Mode();
        bl.opt2 = "26"; bl.cpuMax = "64"; bl.cpuMin = "20"; bl.llcc = "680000";
        bl.uclampDisplay = "30"; bl.uclampSsfg = "28"; bl.uclampTouch = "40";
        bl.uclampMm = "32"; bl.uclampRt = "40"; bl.uclampTopApp = "26";
        Mode pf = new Mode();
        pf.opt2 = "52"; pf.cpuMax = "90"; pf.cpuMin = "35"; pf.llcc = "1220000";
        pf.uclampDisplay = "78"; pf.uclampSsfg = "76"; pf.uclampTouch = "92";
        pf.uclampMm = "80"; pf.uclampRt = "92"; pf.uclampTopApp = "74";
        Mode fa = new Mode();
        fa.opt2 = "88"; fa.cpuMax = "100"; fa.cpuMin = "42"; fa.llcc = "1800000";
        fa.uclampDisplay = "82"; fa.uclampSsfg = "80"; fa.uclampTouch = "94";
        fa.uclampMm = "84"; fa.uclampRt = "94"; fa.uclampTopApp = "78";
        fa.walt1 = "0"; fa.walt2 = "1500";
        c.modes.put("powersave", ps);
        c.modes.put("balance", bl);
        c.modes.put("performance", pf);
        c.modes.put("fast", fa);
        return c;
    }

    private static final Pattern P_ACTION = Pattern.compile("\\[\\[ \\$action == \"(\\w+)\" ]]");
    private static final Pattern P_OPT2 = Pattern.compile("opt2 (\\S+)");
    private static final Pattern P_JSON = Pattern.compile("json_cpu_max_min \"(\\S+)\" \"(\\S+)\"");
    private static final Pattern P_LLCC = Pattern.compile("llcc\\.sh set_max_freq (\\S+)");
    private static final Pattern P_ECHO = Pattern.compile("echo \"?([\\w.-]+)\"? > (\\S+)");
    private static final Pattern P_WALT = Pattern.compile("walt_up_rate_limit_us \"(\\S+)\" \"(\\S+)\"");

    /** 解析脚本内容，失败或为空时返回 null */
    public static AllConfig parse(String content) {
        if (content == null || content.trim().isEmpty()) return null;
        AllConfig cfg = defaults();
        try {
            String current = null;
            for (String raw : content.split("\n")) {
                String line = raw.trim();
                Matcher m = P_ACTION.matcher(line);
                if (m.find()) {
                    current = m.group(1);
                    continue;
                }
                if (line.equals("fi")) {
                    current = null;
                    continue;
                }
                if (current == null) continue;
                Mode md = cfg.modes.get(current);
                if (md == null) continue;
                if (line.startsWith("#")) continue;
                Matcher mw = P_WALT.matcher(line);
                if (mw.find()) {
                    md.walt1 = mw.group(1);
                    md.walt2 = mw.group(2);
                    continue;
                }
                Matcher mo = P_OPT2.matcher(line);
                if (mo.find()) {
                    md.opt2 = mo.group(1);
                    continue;
                }
                Matcher mj = P_JSON.matcher(line);
                if (mj.find()) {
                    md.cpuMax = mj.group(1);
                    md.cpuMin = mj.group(2);
                    continue;
                }
                Matcher ml = P_LLCC.matcher(line);
                if (ml.find()) {
                    md.llcc = ml.group(1);
                    continue;
                }
                Matcher me = P_ECHO.matcher(line);
                if (me.find()) {
                    String val = me.group(1);
                    String path = me.group(2);
                    if (path.contains("cpuctl")) {
                        if (path.contains("display")) md.uclampDisplay = val;
                        else if (path.contains("ssfg")) md.uclampSsfg = val;
                        else if (path.contains("touch")) md.uclampTouch = val;
                        else if (path.contains("multimedia")) md.uclampMm = val;
                        else if (path.contains("/rt/")) md.uclampRt = val;
                        else if (path.contains("top-app")) md.uclampTopApp = val;
                    }
                }
            }
            return cfg;
        } catch (Exception e) {
            return null;
        }
    }

    /** 生成脚本内容 */
    public static String generate(AllConfig cfg) {
        Mode ps = cfg.modes.get("powersave");
        Mode bl = cfg.modes.get("balance");
        Mode pf = cfg.modes.get("performance");
        Mode fa = cfg.modes.get("fast");
        StringBuilder sb = new StringBuilder();
        sb.append("SOC_PLAT=$(getprop ro.board.platform)\n\n");

        sb.append("if [[ $action == \"powersave\" ]]; then\n");
        sb.append("\t# 省电\n");
        sb.append("\techo \"powersave\" > $pan1\n");
        sb.append("    $mokzdz/A/opt2 ").append(ps.opt2).append('\n');
        sb.append("    $mokzdz/A/conservative.sh\n");
        sb.append("    $mokzdz/A/json_cpu_max_min \"").append(ps.cpuMax).append("\" \"").append(ps.cpuMin).append("\"\n");
        sb.append("    $mokzdz/A/llcc.sh set_max_freq ").append(ps.llcc).append('\n');
        sb.append("    \n");
        appendUclamp(sb, ps);
        sb.append("fi\n\n");

        sb.append("if [[ $action == \"balance\" ]]; then\n");
        sb.append("\t# 均衡\n");
        appendUnlock(sb);
        sb.append("\techo \"balance\" > $pan1\n");
        sb.append("    $mokzdz/A/opt2 ").append(bl.opt2).append('\n');
        sb.append("    $mokzdz/A/scx1.sh\n");
        sb.append("    $mokzdz/A/json_cpu_max_min \"").append(bl.cpuMax).append("\" \"").append(bl.cpuMin).append("\"\n");
        sb.append("    $mokzdz/A/llcc.sh set_max_freq ").append(bl.llcc).append('\n');
        sb.append("   \n");
        appendUclamp(sb, bl);
        sb.append("fi\n\n");

        sb.append("if [[ $action == \"performance\" ]]; then\n");
        sb.append("\t# 性能\n");
        appendUnlock(sb);
        sb.append("\techo \"performance\" > $pan1\n");
        sb.append("    $mokzdz/A/opt2 ").append(pf.opt2).append('\n');
        sb.append("    $mokzdz/A/scx2.sh\n");
        sb.append("    $mokzdz/A/json_cpu_max_min \"").append(pf.cpuMax).append("\" \"").append(pf.cpuMin).append("\"\n");
        sb.append("    $mokzdz/A/llcc.sh set_max_freq ").append(pf.llcc).append('\n');
        appendUclamp(sb, pf);
        sb.append("fi\n\n");

        sb.append("if [[ $action == \"fast\" ]]; then\n");
        sb.append("\t# 极速\n");
        appendUnlock(sb);
        sb.append("\techo \"fast\" > $pan1\n");
        sb.append("\t$mokzdz/A/walt_up_rate_limit_us \"").append(fa.walt1).append("\" \"").append(fa.walt2).append("\"\n");
        sb.append("    $mokzdz/A/opt2 ").append(fa.opt2).append('\n');
        sb.append("    $mokzdz/A/scx3.sh\n");
        sb.append("    $mokzdz/A/llcc.sh set_max_freq ").append(fa.llcc).append('\n');
        sb.append("    $mokzdz/A/json_cpu_max_min \"").append(fa.cpuMax).append("\" \"").append(fa.cpuMin).append("\"\n");
        sb.append('\n');
        appendUclamp(sb, fa);
        sb.append("fi\n");
        return sb.toString();
    }

    private static void appendUnlock(StringBuilder sb) {
        sb.append("    $mokzdz/A/llcc.sh unlock_llcc\n");
        sb.append("    chattr -i /sys/class/devfreq/soc:qcom,memlat-drv/max_freq\n");
        sb.append("    chattr -i /sys/class/devfreq/soc:qcom,memlat-drv/min_freq\n");
        sb.append("    chattr -i /sys/class/devfreq/soc:qcom,memlat-drv/boost_freq\n\n");
    }

    private static void appendUclamp(StringBuilder sb, Mode m) {
        sb.append("    echo \"").append(m.uclampDisplay).append("\" > /dev/cpuctl/display/cpu.uclamp.min\n");
        sb.append("    echo \"").append(m.uclampSsfg).append("\" > /dev/cpuctl/ssfg/cpu.uclamp.min\n");
        sb.append("    echo \"").append(m.uclampTouch).append("\" > /dev/cpuctl/touch/cpu.uclamp.min\n");
        sb.append("    echo \"").append(m.uclampMm).append("\" > /dev/cpuctl/multimedia/cpu.uclamp.min\n");
        sb.append("    echo \"").append(m.uclampRt).append("\" > /dev/cpuctl/rt/cpu.uclamp.min\n");
        sb.append("    echo \"").append(m.uclampTopApp).append("\" > /dev/cpuctl/top-app/cpu.uclamp.min\n");
    }
}
