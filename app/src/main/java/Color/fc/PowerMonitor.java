package Color.fc;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

/**
 * 电池功耗监控：自动扫描 /sys/class/power_supply 下的 Battery 节点，
 * 自动校准单位（µA/mA、µV/mV、µW/mW）与单/双电芯，计算实时功耗。
 */
public class PowerMonitor {

    public static class BatteryStat {
        public double watts;    // 总功耗 W
        public double volts;   // 总电压 V
        public double amps;    // 电流 A
        public int cells = 1;  // 电芯数
        public int level = -1;// 电量 %
        public double tempC;   // 温度 ℃
        public String status = "";
    }

    public static BatteryStat readOnce() {
        try {
            File dir = new File("/sys/class/power_supply");
            File[] subs = dir.listFiles();
            if (subs == null) return null;
            List<File> bats = new ArrayList<>();
            for (File f : subs) {
                if (!f.isDirectory()) continue;
                String t = readTrim(new File(f, "type"));
                if (t != null && t.trim().equalsIgnoreCase("Battery")) bats.add(f);
            }
            if (bats.isEmpty()) return null;

            BatteryStat st = new BatteryStat();
            double totalV = 0, sumI = 0;
            int used = 0;
            for (File b : bats) {
                double v = readDouble(new File(b, "voltage_now"));
                double i = readDouble(new File(b, "current_now"));
                if (v == 0 && i == 0) continue;
                totalV += calibV(v);
                sumI += calibI(i);
                used++;
            }

            // 优先使用内核直接给出的功率节点
            double pn = 0;
            for (File b : bats) {
                pn = readDouble(new File(b, "power_now"));
                if (pn != 0) break;
            }

            if (used == 0 && pn == 0) return null;

            st.cells = Math.max(used, 1);
            if (used > 0) {
                double avgI = sumI / used;
                st.volts = totalV;
                st.amps = avgI;
                st.watts = pn != 0 ? calibW(pn) : totalV * avgI;
            } else {
                st.watts = calibW(pn);
            }
            // 双电芯：多个 Battery 节点（串联/并联均按总功率求和）
            st.cells = Math.max(used, 1);
            if (bats.size() >= 2 && used >= 2) st.cells = used;

            File main = bats.get(0);
            st.level = (int) readDouble(new File(main, "capacity"));
            double temp = readDouble(new File(main, "temp"));
            st.tempC = temp > 1000 ? temp / 10.0 : temp;
            String s = readTrim(new File(main, "status"));
            if (s != null) st.status = s.trim();
            return st;
        } catch (Exception e) {
            return null;
        }
    }

    /** 统计 CPU 核心数 */
    public static int cpuCount() {
        int n = 0;
        for (int i = 0; i < 16; i++) {
            if (new File("/sys/devices/system/cpu/cpu" + i).exists()) n++;
        }
        return n;
    }

    private static double readDouble(File f) {
        String s = readTrim(f);
        if (s == null) return 0;
        try {
            return Double.parseDouble(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static String readTrim(File f) {
        try {
            BufferedReader r = new BufferedReader(new FileReader(f));
            String l = r.readLine();
            r.close();
            return l;
        } catch (Exception e) {
            return null;
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
