package Color.fc;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

/**
 * 电池功耗监控：
 * 1) root 扫描 /sys/class/power_supply 下所有 Battery 节点，自动校准单位（µA/mA、µV/mV、µW/mW）
 * 2) 电芯判定：
 *    - 单体锂电池电压上限约 4.5V，节点电压 > 5.5V → 串联双电芯（voltage_now 为总压）
 *    - 出现 2 个以上带电压的独立电池节点（排除 fuel gauge 类）→ 双电芯
 * 3) sysfs 全部失败时回退 dumpsys battery（电量/电压/温度/状态）
 */
public class PowerMonitor {

    public static class BatteryStat {
        public double watts;    // 总功耗 W
        public double volts;    // 电压 V（串联双电芯为总电压）
        public double amps;     // 总电流 A
        public int cells = 1;   // 电芯数
        public int level = -1; // 电量 %
        public double tempC;   // 温度 ℃
        public String status = "";
    }

    /** 一条 su 脚本批量读取所有 Battery 节点：path|V|I|P|cap|temp|status */
    private static final String SCAN_SCRIPT =
            "for d in /sys/class/power_supply/*; do "
                    + "[ -f \"$d/type\" ] || continue; "
                    + "t=$(cat \"$d/type\" 2>/dev/null); "
                    + "[ \"$t\" = \"Battery\" ] || [ \"$t\" = \"battery\" ] || continue; "
                    + "echo \"$d|$(cat \"$d/voltage_now\" 2>/dev/null)|$(cat \"$d/current_now\" 2>/dev/null)"
                    + "|$(cat \"$d/power_now\" 2>/dev/null)|$(cat \"$d/capacity\" 2>/dev/null)"
                    + "|$(cat \"$d/temp\" 2>/dev/null)|$(cat \"$d/status\" 2>/dev/null)\"; "
                    + "done";

    public static BatteryStat readOnce() {
        BatteryStat st = fromSysfs();
        if (st == null) st = fromDumpsys();
        return st;
    }

    private static class Node {
        final String name;
        final double v;   // 已校准 V
        final double i;   // 已校准 A
        Node(String name, double v, double i) {
            this.name = name;
            this.v = v;
            this.i = i;
        }
    }

    /** fuel gauge / 计量芯片节点，与主 battery 是同一电芯，不能算独立电芯 */
    private static boolean isGaugeNode(String name) {
        String n = name.toLowerCase();
        return n.contains("bms") || n.contains("fg") || n.contains("gauge")
                || n.contains("charger") || n.contains("fgauge");
    }

    private static BatteryStat fromSysfs() {
        try {
            RootShell.Result r = RootShell.exec(SCAN_SCRIPT);
            if (!r.ok() || r.out == null || r.out.trim().isEmpty()) return null;

            List<String[]> rows = new ArrayList<>();
            for (String line : r.out.split("\\n")) {
                if (line.trim().isEmpty()) continue;
                String[] p = line.split("\\|");
                if (p.length >= 7) rows.add(p);
            }
            if (rows.isEmpty()) return null;

            BatteryStat st = new BatteryStat();

            List<Node> live = new ArrayList<>();
            double pn = 0;
            for (String[] p : rows) {
                double v = calibV(toD(p[1]));
                double i = calibI(toD(p[2]));
                if (v > 0 || i != 0) {
                    String path = p[0] == null ? "" : p[0];
                    String name = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
                    live.add(new Node(name, v, i));
                }
            }
            for (String[] p : rows) {
                pn = toD(p[3]);
                if (pn != 0) break;
            }

            double maxV = 0;
            for (Node n : live) maxV = Math.max(maxV, n.v);

            // ===== 电芯判定 =====
            boolean series = maxV > 5.5;                    // 电压超单体上限 → 串联双电芯
            int indep = 0;
            for (Node n : live) if (!isGaugeNode(n.name)) indep++;
            boolean multi = !series && indep >= 2;          // 两个独立电池节点 → 双电芯
            st.cells = (series || multi) ? 2 : 1;

            if (!live.isEmpty()) {
                double sumI = 0;
                for (Node n : live) sumI += n.i;
                if (series) {
                    // 串联：电压=总压，电流取均值（同一通路）
                    st.volts = maxV;
                    st.amps = sumI / live.size();
                } else if (multi) {
                    // 并联/独立：电压同压取最大，电流相加为总电流
                    st.volts = maxV;
                    st.amps = sumI;
                } else {
                    st.volts = maxV;
                    st.amps = sumI / live.size();
                }
                st.watts = pn != 0 ? calibW(pn) : st.volts * st.amps;
            } else if (pn != 0) {
                st.watts = calibW(pn);
            }

            fillBasic(st, rows.get(0));
            if (st.volts == 0 && st.watts == 0 && st.level < 0 && st.tempC == 0) return null;
            return st;
        } catch (Exception e) {
            return null;
        }
    }

    /** dumpsys battery 兜底：电量 / 温度 / 电压 / 状态（无电流则功耗无法计算） */
    private static BatteryStat fromDumpsys() {
        try {
            RootShell.Result r = RootShell.exec("dumpsys battery");
            if (!r.ok() || r.out == null || r.out.trim().isEmpty()) return null;
            BatteryStat st = new BatteryStat();
            for (String line : r.out.split("\\n")) {
                String t = line.trim();
                if (t.startsWith("level:")) {
                    st.level = (int) toD(t.substring(6).trim());
                } else if (t.startsWith("temperature:")) {
                    double v = toD(t.substring(12).trim());
                    st.tempC = v > 60 ? v / 10.0 : v;
                } else if (t.startsWith("voltage:")) {
                    st.volts = calibV(toD(t.substring(8).trim()));
                } else if (t.startsWith("status:")) {
                    st.status = dumpsysStatus(t.substring(7).trim());
                }
            }
            if (st.level < 0 && st.tempC == 0 && st.volts == 0) return null;
            return st;
        } catch (Exception e) {
            return null;
        }
    }

    private static String dumpsysStatus(String v) {
        switch (v) {
            case "1": return "Unknown";
            case "2": return "Charging";
            case "3": return "Discharging";
            case "4": return "Not charging";
            case "5": return "Full";
            default: return v;
        }
    }

    /** 电量 / 温度 / 充电状态（温度节点单位 0.1℃，自动换算） */
    private static void fillBasic(BatteryStat st, String[] p) {
        st.level = (int) toD(p[4]);
        double t = toD(p[5]);
        st.tempC = t > 60 ? t / 10.0 : t;   // 285 → 28.5℃；直接给 25 则保留
        if (p[6] != null) st.status = p[6].trim();
    }

    /** 统计 CPU 核心数（/proc 对普通进程可读） */
    public static int cpuCount() {
        int n = 0;
        try {
            BufferedReader r = new BufferedReader(new FileReader("/proc/cpuinfo"));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("processor")) n++;
            }
            r.close();
        } catch (Exception ignored) {
        }
        return n > 0 ? n : 8;
    }

    private static double toD(String s) {
        if (s == null) return 0;
        try {
            return Double.parseDouble(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    /** 电压校准 → V（µV / mV / V） */
    private static double calibV(double v) {
        double a = Math.abs(v);
        if (a > 1000000) return v / 1e6;
        if (a > 10000) return v / 1000;
        return v;
    }

    /** 电流校准 → A（µA / mA） */
    private static double calibI(double i) {
        double a = Math.abs(i);
        if (a > 100000) return i / 1e6;
        if (a > 1) return i / 1000;
        return i;
    }

    /** 功率校准 → W（µW / mW） */
    private static double calibW(double w) {
        double a = Math.abs(w);
        if (a > 100000) return w / 1e6;
        if (a > 100) return w / 1000;
        return w;
    }
}
