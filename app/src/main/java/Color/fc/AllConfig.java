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
        /** 小核上限 %（json_cpu_max_min 参数一，照搬 Kin FSM maxL） */
        public String cpuMaxL = "42";
        /** 大核上限 %（json_cpu_max_min 参数二，照搬 Kin FSM maxB） */
        public String cpuMaxB = "42";
        public String cpuMin = "5";
        /** GPU 上限 %（0=不限制，照搬 Kin FSM gpu） */
        public String gpuMax = "0";
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

    /** 默认参数（a/b 初始一致）。GPU 上限照搬 Kin FSM 内置曲线：省电40/均衡60/性能85/极速100 */
    public static AllConfig defaults() {
        AllConfig c = new AllConfig();
        Mode ps = new Mode();
        ps.opt2 = "0"; ps.cpuMaxL = "42"; ps.cpuMaxB = "42"; ps.cpuMin = "5"; ps.gpuMax = "40"; ps.llcc = "350000";
        ps.uclampDisplay = "6"; ps.uclampSsfg = "5"; ps.uclampTouch = "8";
        ps.uclampMm = "6"; ps.uclampRt = "3"; ps.uclampTopApp = "10";
        Mode bl = new Mode();
        bl.opt2 = "26"; bl.cpuMaxL = "64"; bl.cpuMaxB = "64"; bl.cpuMin = "20"; bl.gpuMax = "60"; bl.llcc = "680000";
        bl.uclampDisplay = "30"; bl.uclampSsfg = "28"; bl.uclampTouch = "40";
        bl.uclampMm = "32"; bl.uclampRt = "40"; bl.uclampTopApp = "26";
        Mode pf = new Mode();
        pf.opt2 = "52"; pf.cpuMaxL = "90"; pf.cpuMaxB = "90"; pf.cpuMin = "35"; pf.gpuMax = "85"; pf.llcc = "1220000";
        pf.uclampDisplay = "78"; pf.uclampSsfg = "76"; pf.uclampTouch = "92";
        pf.uclampMm = "80"; pf.uclampRt = "92"; pf.uclampTopApp = "74";
        Mode fa = new Mode();
        fa.opt2 = "88"; fa.cpuMaxL = "100"; fa.cpuMaxB = "100"; fa.cpuMin = "42"; fa.gpuMax = "100"; fa.llcc = "1800000";
        fa.uclampDisplay = "82"; fa.uclampSsfg = "80"; fa.uclampTouch = "94";
        fa.uclampMm = "84"; fa.uclampRt = "94"; fa.uclampTopApp = "78";
        fa.walt1 = "0"; fa.walt2 = "1500";
        c.modes.put("powersave", ps);
        c.modes.put("balance", bl);
        c.modes.put("performance", pf);
        c.modes.put("fast", fa);
        return c;
    }

    /** 方案3（C 方案，无风驰内核机型如骁龙8gen2/8+）出厂默认：
     *  json_cpu_max_min 保持 2 参（上限%/下限%，小/大核同值），GPU 不限制 */
    public static AllConfig defaultsC() {
        AllConfig c = new AllConfig();
        Mode ps = new Mode();
        ps.opt2 = "0"; ps.cpuMaxL = "36"; ps.cpuMaxB = "36"; ps.cpuMin = "4"; ps.gpuMax = "0"; ps.llcc = "300000";
        ps.uclampDisplay = "4"; ps.uclampSsfg = "3"; ps.uclampTouch = "6";
        ps.uclampMm = "4"; ps.uclampRt = "2"; ps.uclampTopApp = "8";
        Mode bl = new Mode();
        bl.opt2 = "32"; bl.cpuMaxL = "68"; bl.cpuMaxB = "68"; bl.cpuMin = "24"; bl.gpuMax = "0"; bl.llcc = "720000";
        bl.uclampDisplay = "34"; bl.uclampSsfg = "30"; bl.uclampTouch = "44";
        bl.uclampMm = "36"; bl.uclampRt = "44"; bl.uclampTopApp = "30";
        Mode pf = new Mode();
        pf.opt2 = "60"; pf.cpuMaxL = "94"; pf.cpuMaxB = "94"; pf.cpuMin = "38"; pf.gpuMax = "0"; pf.llcc = "1350000";
        pf.uclampDisplay = "82"; pf.uclampSsfg = "78"; pf.uclampTouch = "94";
        pf.uclampMm = "84"; pf.uclampRt = "94"; pf.uclampTopApp = "76";
        Mode fa = new Mode();
        fa.opt2 = "92"; fa.cpuMaxL = "100"; fa.cpuMaxB = "100"; fa.cpuMin = "46"; fa.gpuMax = "0"; fa.llcc = "1920000";
        fa.uclampDisplay = "86"; fa.uclampSsfg = "82"; fa.uclampTouch = "96";
        fa.uclampMm = "88"; fa.uclampRt = "96"; fa.uclampTopApp = "80";
        fa.walt1 = "0"; fa.walt2 = "1500";
        c.modes.put("powersave", ps);
        c.modes.put("balance", bl);
        c.modes.put("performance", pf);
        c.modes.put("fast", fa);
        return c;
    }

    private static final Pattern P_ACTION = Pattern.compile("\\[\\[ \\$action == \"(\\w+)\" ]]");
    private static final Pattern P_OPT2 = Pattern.compile("opt2 (\\S+)");
    /** 新 4 参：小核上限% 大核上限% 下限% GPU上限%（后两参可选，兼容旧 2 参 conf） */
    private static final Pattern P_JSON = Pattern.compile(
            "json_cpu_max_min \"(\\S+)\" \"(\\S+)\"(?: \"(\\S+)\")?(?: \"(\\S+)\")?");
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
                    md.cpuMaxL = mj.group(1);
                    md.cpuMaxB = mj.group(2);
                    if (mj.group(3) != null) {
                        // 新 4 参：小核上限% 大核上限% 下限% GPU上限%
                        md.cpuMin = mj.group(3);
                        if (mj.group(4) != null) md.gpuMax = mj.group(4);
                    } else {
                        // 旧 2 参（原 ELF 语义）：参数一=上限%（小/大核同值），参数二=下限%
                        md.cpuMaxB = mj.group(1);
                        md.cpuMin = mj.group(2);
                    }
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
        sb.append("    $mokzdz/A/opt2 ").append(ps.opt2).append(" 2>/dev/null\n");
        sb.append("    $mokzdz/A/conservative.sh\n");
        sb.append("    $mokzdz/A/json_cpu_max_min \"").append(ps.cpuMaxL).append("\" \"").append(ps.cpuMaxB)
                .append("\" \"").append(ps.cpuMin).append("\" \"").append(ps.gpuMax).append("\"\n");
        sb.append("    sh $mokzdz/A/freq0.sh 2>/dev/null\n");
        sb.append("    $mokzdz/A/llcc.sh set_max_freq ").append(ps.llcc).append('\n');
        sb.append("    \n");
        appendUclamp(sb, ps);
        sb.append("fi\n\n");

        sb.append("if [[ $action == \"balance\" ]]; then\n");
        sb.append("\t# 均衡\n");
        appendUnlock(sb);
        sb.append("\techo \"balance\" > $pan1\n");
        sb.append("    $mokzdz/A/opt2 ").append(bl.opt2).append(" 2>/dev/null\n");
        sb.append("    $mokzdz/A/scx1.sh\n");
        sb.append("    $mokzdz/A/json_cpu_max_min \"").append(bl.cpuMaxL).append("\" \"").append(bl.cpuMaxB)
                .append("\" \"").append(bl.cpuMin).append("\" \"").append(bl.gpuMax).append("\"\n");
        sb.append("    sh $mokzdz/A/freq1.sh 2>/dev/null\n");
        sb.append("    $mokzdz/A/llcc.sh set_max_freq ").append(bl.llcc).append('\n');
        sb.append("   \n");
        appendUclamp(sb, bl);
        sb.append("fi\n\n");

        sb.append("if [[ $action == \"performance\" ]]; then\n");
        sb.append("\t# 性能\n");
        appendUnlock(sb);
        sb.append("\techo \"performance\" > $pan1\n");
        sb.append("    $mokzdz/A/opt2 ").append(pf.opt2).append(" 2>/dev/null\n");
        sb.append("    $mokzdz/A/scx2.sh\n");
        sb.append("    $mokzdz/A/json_cpu_max_min \"").append(pf.cpuMaxL).append("\" \"").append(pf.cpuMaxB)
                .append("\" \"").append(pf.cpuMin).append("\" \"").append(pf.gpuMax).append("\"\n");
        sb.append("    sh $mokzdz/A/freq2.sh 2>/dev/null\n");
        sb.append("    $mokzdz/A/llcc.sh set_max_freq ").append(pf.llcc).append('\n');
        appendUclamp(sb, pf);
        sb.append("fi\n\n");

        sb.append("if [[ $action == \"fast\" ]]; then\n");
        sb.append("\t# 极速\n");
        appendUnlock(sb);
        sb.append("\techo \"fast\" > $pan1\n");
        sb.append("\t$mokzdz/A/walt_up_rate_limit_us \"").append(fa.walt1).append("\" \"").append(fa.walt2).append("\" 2>/dev/null\n");
        sb.append("    $mokzdz/A/opt2 ").append(fa.opt2).append(" 2>/dev/null\n");
        sb.append("    $mokzdz/A/scx3.sh\n");
        sb.append("    $mokzdz/A/llcc.sh set_max_freq ").append(fa.llcc).append('\n');
        sb.append("    $mokzdz/A/json_cpu_max_min \"").append(fa.cpuMaxL).append("\" \"").append(fa.cpuMaxB)
                .append("\" \"").append(fa.cpuMin).append("\" \"").append(fa.gpuMax).append("\"\n");
        sb.append("    sh $mokzdz/A/freq3.sh 2>/dev/null\n");
        sb.append('\n');
        appendUclamp(sb, fa);
        sb.append("fi\n");
        return sb.toString();
    }

    /** 生成 C 方案（方案3）脚本内容：目录用 C/，json_cpu_max_min 保持 2 参（上限%/下限%），
     *  与模块出厂 c.all.sh 及 WebUI 读写格式一致（出厂 C/json_cpu_max_min 仅支持 2 参） */
    public static String generateC(AllConfig cfg) {
        Mode ps = cfg.modes.get("powersave");
        Mode bl = cfg.modes.get("balance");
        Mode pf = cfg.modes.get("performance");
        Mode fa = cfg.modes.get("fast");
        StringBuilder sb = new StringBuilder();
        sb.append("SOC_PLAT=$(getprop ro.board.platform)\n\n");

        sb.append("if [[ $action == \"powersave\" ]]; then\n");
        sb.append("\t# 省电\n");
        sb.append("\techo \"powersave\" > $pan1\n");
        sb.append("    $mokzdz/C/opt2 ").append(ps.opt2).append(" 2>/dev/null\n");
        sb.append("    $mokzdz/C/conservative.sh\n");
        sb.append("    $mokzdz/C/json_cpu_max_min \"").append(ps.cpuMaxL).append("\" \"").append(ps.cpuMin).append("\"\n");
        sb.append("    sh $mokzdz/C/freq0.sh 2>/dev/null\n");
        sb.append("    $mokzdz/C/llcc.sh set_max_freq ").append(ps.llcc).append('\n');
        sb.append("    \n");
        appendUclamp(sb, ps);
        sb.append("fi\n\n");

        sb.append("if [[ $action == \"balance\" ]]; then\n");
        sb.append("\t# 均衡\n");
        appendUnlockC(sb);
        sb.append("\techo \"balance\" > $pan1\n");
        sb.append("    $mokzdz/C/opt2 ").append(bl.opt2).append(" 2>/dev/null\n");
        sb.append("    $mokzdz/C/scx1.sh\n");
        sb.append("    $mokzdz/C/json_cpu_max_min \"").append(bl.cpuMaxL).append("\" \"").append(bl.cpuMin).append("\"\n");
        sb.append("    sh $mokzdz/C/freq1.sh 2>/dev/null\n");
        sb.append("    $mokzdz/C/llcc.sh set_max_freq ").append(bl.llcc).append('\n');
        sb.append("   \n");
        appendUclamp(sb, bl);
        sb.append("fi\n\n");

        sb.append("if [[ $action == \"performance\" ]]; then\n");
        sb.append("\t# 性能\n");
        appendUnlockC(sb);
        sb.append("\techo \"performance\" > $pan1\n");
        sb.append("    $mokzdz/C/opt2 ").append(pf.opt2).append(" 2>/dev/null\n");
        sb.append("    $mokzdz/C/scx2.sh\n");
        sb.append("    $mokzdz/C/json_cpu_max_min \"").append(pf.cpuMaxL).append("\" \"").append(pf.cpuMin).append("\"\n");
        sb.append("    sh $mokzdz/C/freq2.sh 2>/dev/null\n");
        sb.append("    $mokzdz/C/llcc.sh set_max_freq ").append(pf.llcc).append('\n');
        appendUclamp(sb, pf);
        sb.append("fi\n\n");

        sb.append("if [[ $action == \"fast\" ]]; then\n");
        sb.append("\t# 极速\n");
        appendUnlockC(sb);
        sb.append("\techo \"fast\" > $pan1\n");
        sb.append("\t$mokzdz/C/walt_up_rate_limit_us \"").append(fa.walt1).append("\" \"").append(fa.walt2).append("\" 2>/dev/null\n");
        sb.append("    $mokzdz/C/opt2 ").append(fa.opt2).append(" 2>/dev/null\n");
        sb.append("    $mokzdz/C/scx3.sh\n");
        sb.append("    $mokzdz/C/llcc.sh set_max_freq ").append(fa.llcc).append('\n');
        sb.append("    $mokzdz/C/json_cpu_max_min \"").append(fa.cpuMaxL).append("\" \"").append(fa.cpuMin).append("\"\n");
        sb.append("    sh $mokzdz/C/freq3.sh 2>/dev/null\n");
        sb.append('\n');
        appendUclamp(sb, fa);
        sb.append("fi\n");
        return sb.toString();
    }

    /** 给已部署的方案 conf 补上 freq 调用行（幂等）：每个模式块的 json_cpu_max_min 之后
     *  追加 sh $mokzdz/{dir}/freq{块}.sh（a.all.sh 用 A，b.all.sh 用 B）。
     *  调速器页保存时对旧 conf 打补丁，无需重新保存调度 */
    public static String patchFreqLines(String content, String dir) {
        if (content == null || content.trim().isEmpty()) return content;
        StringBuilder sb = new StringBuilder();
        int mode = -1;
        for (String line : content.split("\n", -1)) {
            // 移除旧的补丁行（A 或 B 引用），保证重复执行不叠加
            if (line.contains("$mokzdz") && (line.contains("/A/freq") || line.contains("/B/freq"))) {
                continue;
            }
            if (line.contains("$action == \"powersave\"")) mode = 0;
            else if (line.contains("$action == \"balance\"")) mode = 1;
            else if (line.contains("$action == \"performance\"")) mode = 2;
            else if (line.contains("$action == \"fast\"")) mode = 3;
            sb.append(line).append('\n');
            if (mode >= 0 && line.contains("json_cpu_max_min")) {
                sb.append("    sh $mokzdz/").append(dir).append("/freq").append(mode).append(".sh 2>/dev/null\n");
            }
        }
        return sb.toString();
    }

    private static void appendUnlock(StringBuilder sb) {
        sb.append("    $mokzdz/A/llcc.sh unlock_llcc\n");
        sb.append("    chattr -i /sys/class/devfreq/soc:qcom,memlat-drv/max_freq\n");
        sb.append("    chattr -i /sys/class/devfreq/soc:qcom,memlat-drv/min_freq\n");
        sb.append("    chattr -i /sys/class/devfreq/soc:qcom,memlat-drv/boost_freq\n\n");
    }

    private static void appendUnlockC(StringBuilder sb) {
        sb.append("    $mokzdz/C/llcc.sh unlock_llcc\n");
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
