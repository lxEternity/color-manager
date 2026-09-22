package Color.fc;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

/**
 * 电池功耗监控：通过 root 批量扫描 /sys/class/power_supply 下的 Battery 节点，
 * 自动校准单位（µA/mA、µV/mV、µW/mW）与单/双电芯，计算实时功耗。
 * 注：普通 App 受 SELinux 限制无法直接读 sysfs，必须走 su。
 */
public class PowerMonitor {

    public static class BatteryStat {
        public double watts;    // 总功耗 W
        public double volts;    // 总电压 V
        public double amps;     // 电流 A
        public int cells = 1;  // 电芯数
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
            double totalV = 0, sumI = 0, pn = 0;
            int used = 0;
            for (String[] p : rows) {
                double v = toD(p[1]);
                double i = toD(p[2]);
                if (v == 0 && i == 0) continue;
                totalV += calibV(v);
                sumI += calibI(i);
                used++;
            }
            // 优先使用内核直接给出的功率节点
            for (String[] p : rows) {
                pn = toD(p[3]);
                if (pn != 0) break;
            }
            if (used == 0 && pn == 0) {
                // 电压电流都读不到，仅剩容量/温度也有意义
                fillBasic(st, rows.get(0));
                if (st.level < 0 && st.tempC == 0) return null;
                return st;
            }

            st.cells = Math.max(used, 1);
            if (used > 0) {
                double avgI = sumI / used;
                st.volts = totalV;
                st.amps = avgI;
                st.watts = pn != 0 ? calibW(pn) : totalV * avgI;
            } else {
                st.watts = calibW(pn);
            }
            fillBasic(st, rows.get(0));
            return st;
        } catch (Exception e) {
            return null;
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
