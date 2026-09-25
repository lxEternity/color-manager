package Color.fc;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * CPU 核心状态读取与即时开关。
 * 逻辑照搬 Kin-app SysfsReader：
 * - 免 root File 直读优先，读不到再 root 兜底（readFileOrRoot）
 * - cpuN 无 online 节点（cpu0 恒在线）：读不到视为在线
 * - 开关即时写 sysfs（echo 0/1 > cpuN/online），不经过调速器脚本，点了立即生效
 */
public class CpuCoreManager {

    private static final String CPU_BASE = "/sys/devices/system/cpu";

    /** 全核快照：在线状态 + 每核当前频率 */
    public static class Snapshot {
        public int cores = 0;          // 核心总数
        public boolean[] online;       // 各核在线状态（索引即核心号）
        public int[] freqMhz;          // 各核当前频率 MHz（读不到 -1）
        public int onlineCount() {
            int n = 0;
            for (int i = 0; i < cores && i < online.length; i++) if (online[i]) n++;
            return n;
        }
    }

    /** File 直读（无 root），失败/空返回 null */
    private static String readFile(String path) {
        try {
            File f = new File(path);
            if (!f.canRead()) return null;
            BufferedReader r = new BufferedReader(new InputStreamReader(new java.io.FileInputStream(f)));
            String line = r.readLine();
            r.close();
            return (line == null || line.trim().isEmpty()) ? null : line.trim();
        } catch (Exception e) {
            return null;
        }
    }

    /** 免 root 优先 + root 兜底（照搬 Kin readFileOrRoot） */
    public static String readFileOrRoot(String path) {
        String s = readFile(path);
        if (s != null) return s;
        RootShell.Result r = RootShell.exec("cat '" + path + "' 2>/dev/null");
        if (r.ok() && r.out != null && !r.out.trim().isEmpty()) return r.out.trim();
        return null;
    }

    /** 核心总数：解析 /sys/devices/system/cpu/present（"0-7" / "0,2-4,7"），失败回退枚举 cpuN 目录 */
    public static int coreCount() {
        String s = readFileOrRoot(CPU_BASE + "/present");
        if (s != null) {
            try {
                int max = -1;
                for (String part : s.split(",")) {
                    String r = part.trim();
                    int dash = r.indexOf('-');
                    if (dash > 0) {
                        max = Math.max(max, Integer.parseInt(r.substring(dash + 1).trim()));
                    } else {
                        max = Math.max(max, Integer.parseInt(r));
                    }
                }
                if (max >= 0) return max + 1;
            } catch (Exception ignored) {
            }
        }
        // 回退：枚举 /sys/devices/system/cpu 下的 cpuN 目录
        try {
            File d = new File(CPU_BASE);
            File[] fs = d.listFiles();
            int max = -1;
            if (fs != null) {
                for (File f : fs) {
                    String n = f.getName();
                    if (n.startsWith("cpu") && n.length() > 3) {
                        try {
                            max = Math.max(max, Integer.parseInt(n.substring(3)));
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
            if (max >= 0) return max + 1;
        } catch (Exception ignored) {
        }
        return 0;
    }

    /** 单核在线状态：cpuN 无 online 节点（cpu0 恒在线）时读不到按在线算（照搬 Kin coreOnline） */
    public static boolean coreOnline(int cpu) {
        String s = readFileOrRoot(CPU_BASE + "/cpu" + cpu + "/online");
        return !"0".equals(s);
    }

    /** 即时开关核心（照搬 Kin 直接写 sysfs 的方式，不经脚本，立即生效） */
    public static boolean setCoreOnline(int cpu, boolean on) {
        RootShell.Result r = RootShell.exec(
                "echo " + (on ? 1 : 0) + " > " + CPU_BASE + "/cpu" + cpu + "/online 2>/dev/null"
                        + " && cat " + CPU_BASE + "/cpu" + cpu + "/online 2>/dev/null");
        // 写后回读校验：节点不存在（cpu0 恒在线）或读回失败但退出码 0 也视为成功，
        // 下一次快照刷新会反映真实状态
        return r.ok() || r.out != null && !r.out.trim().isEmpty();
    }

    /**
     * 全核快照（一次 root 调用读全部 online + 频率；root 不可用回退逐核 File 直读）。
     * 输出格式：每核一行 "O:核心号:0或1:F:频率kHz"
     */
    public static Snapshot snapshot(int cores) {
        Snapshot sp = new Snapshot();
        sp.cores = cores;
        sp.online = new boolean[cores];
        sp.freqMhz = new int[cores];
        for (int i = 0; i < cores; i++) sp.online[i] = true;

        // 免 root 直读（online 多为 0644 可读，cpufreq 多为 0444 可读）
        boolean direct = true;
        for (int i = 0; i < cores; i++) {
            String o = readFile(CPU_BASE + "/cpu" + i + "/online");
            String f = readFile(CPU_BASE + "/cpu" + i + "/cpufreq/scaling_cur_freq");
            if (o == null && f == null && i > 0) {
                direct = false;
                break;
            }
            if (o != null) sp.online[i] = !"0".equals(o);
            sp.freqMhz[i] = f != null ? parseKhz(f) : -1;
        }
        if (direct) return sp;

        // root 兜底：一次 su 读全部
        StringBuilder cmd = new StringBuilder();
        for (int i = 0; i < cores; i++) {
            cmd.append("echo \"O:").append(i).append(":$(cat ").append(CPU_BASE).append("/cpu").append(i)
                    .append("/online 2>/dev/null):F:$(cat ").append(CPU_BASE).append("/cpu").append(i)
                    .append("/cpufreq/scaling_cur_freq 2>/dev/null)\";");
        }
        RootShell.Result r = RootShell.exec(cmd.toString());
        if (r.out != null) {
            for (String line : r.out.split("\n")) {
                line = line.trim();
                if (!line.startsWith("O:")) continue;
                String[] p = line.split(":");
                if (p.length < 4) continue;
                try {
                    int c = Integer.parseInt(p[1]);
                    if (c < 0 || c >= cores) continue;
                    sp.online[c] = !"0".equals(p[2]);   // 空（无节点）按在线，对齐 Kin 语义
                    sp.freqMhz[c] = p[3].isEmpty() ? -1 : parseKhz(p[3]);
                } catch (Exception ignored) {
                }
            }
        }
        return sp;
    }

    private static int parseKhz(String s) {
        try {
            long k = Long.parseLong(s.trim());
            return k > 0 ? (int) (k / 1000) : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    /** 动态枚举 cpufreq policy 编号（升序，照搬 Kin discoverPolicies，供簇名显示用） */
    public static List<int[]> policyTopology() {
        List<int[]> out = new ArrayList<>();   // [policy, firstCpu, lastCpu]
        try {
            File d = new File(CPU_BASE + "/cpufreq");
            File[] fs = d.listFiles();
            List<Integer> pols = new ArrayList<>();
            if (fs != null) {
                for (File f : fs) {
                    String n = f.getName();
                    if (n.startsWith("policy")) {
                        try {
                            pols.add(Integer.parseInt(n.substring(6)));
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
            java.util.Collections.sort(pols);
            for (int p : pols) {
                String rc = readFileOrRoot(CPU_BASE + "/cpufreq/policy" + p + "/related_cpus");
                int first = -1, last = -1;
                if (rc != null) {
                    for (String t : rc.trim().split("\\s+")) {
                        try {
                            int c = Integer.parseInt(t);
                            if (first < 0) first = c;
                            last = c;
                        } catch (Exception ignored) {
                        }
                    }
                }
                out.add(new int[]{p, first, last});
            }
        } catch (Exception ignored) {
        }
        return out;
    }
}
