package Color.fc;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 功耗记录：周期采样电池状态（自动节流 1 分钟一条），按充/放电分段生成历史会话。
 * 存储: filesDir/power_samples.csv，每行: 时间ms,电量%,功率W,状态(C充电/D放电/F满电)
 */
public class PowerHistoryManager {

    public static class Sample {
        public long t;
        public int level;
        public double watts;
        public char status;   // C / D / F
    }

    public static class Session {
        public boolean charging;
        public long start, end;
        public int startLevel, endLevel;
        public double avgW, maxW, energyWh;
        public int samples;
    }

    /** 采样间隔 */
    private static final long SAMPLE_MS = 60_000;
    /** 保留上限（约 10 天），超出裁剪一半 */
    private static final int KEEP_SAMPLES = 15000;
    /** 会话断流阈值：超过 15 分钟无采样则视为新会话 */
    private static final long GAP_MS = 15 * 60_000;

    private static final List<Sample> data = new ArrayList<>();
    private static long lastT = 0;
    private static boolean loaded = false;
    private static long lastTrim = 0;

    private static synchronized File file(Context ctx) {
        return new File(ctx.getFilesDir(), "power_samples.csv");
    }

    private static synchronized void load(Context ctx) {
        if (loaded) return;
        loaded = true;
        try (BufferedReader r = new BufferedReader(new FileReader(file(ctx)))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split(",");
                if (p.length < 4) continue;
                Sample s = new Sample();
                s.t = Long.parseLong(p[0]);
                s.level = (int) Double.parseDouble(p[1]);
                s.watts = Double.parseDouble(p[2]);
                s.status = p[3].charAt(0);
                data.add(s);
            }
        } catch (Exception ignored) {
        }
    }

    /** 记录一条采样（内部节流，可高频调用） */
    public static synchronized void record(Context ctx, PowerMonitor.BatteryStat st) {
        if (st == null || st.level < 0) return;
        load(ctx);
        long now = System.currentTimeMillis();
        if (now - lastT < SAMPLE_MS) return;
        lastT = now;

        char status;
        if ("Charging".equalsIgnoreCase(st.status)) status = 'C';
        else if ("Full".equalsIgnoreCase(st.status)) status = 'F';
        else if (st.status != null && !st.status.isEmpty()) status = 'D';
        else status = st.amps < 0 ? 'C' : 'D';

        Sample s = new Sample();
        s.t = now;
        s.level = st.level;
        s.watts = Math.abs(st.watts);
        s.status = status;
        data.add(s);

        try (PrintWriter w = new PrintWriter(new FileWriter(file(ctx), true))) {
            w.println(s.t + "," + s.level + "," + String.format(Locale.US, "%.2f", s.watts) + "," + s.status);
        } catch (Exception ignored) {
        }

        if (now - lastTrim > 3600_000 && data.size() > KEEP_SAMPLES) {
            lastTrim = now;
            trim(ctx);
        }
    }

    /** 裁剪旧数据，保留后一半 */
    private static synchronized void trim(Context ctx) {
        int keep = KEEP_SAMPLES / 2;
        while (data.size() > keep) data.remove(0);
        try (PrintWriter w = new PrintWriter(new FileWriter(file(ctx)))) {
            for (Sample s : data) {
                w.println(s.t + "," + s.level + "," + String.format(Locale.US, "%.2f", s.watts) + "," + s.status);
            }
        } catch (Exception ignored) {
        }
    }

    /** 清空全部记录 */
    public static synchronized void clear(Context ctx) {
        data.clear();
        lastT = 0;
        try {
            //noinspection ResultOfMethodCallIgnored
            file(ctx).delete();
        } catch (Exception ignored) {
        }
    }

    /** 由采样分段生成会话（充电/放电），status=F 归入非充电 */
    public static synchronized List<Session> sessions(Context ctx, int limit) {
        load(ctx);
        List<Session> out = new ArrayList<>();
        for (int i = data.size() - 1; i >= 0 && out.size() < limit; i--) {
            Sample cur = data.get(i);
            boolean curCharging = cur.status == 'C';
            // 向前聚合同类型且时间连续的采样
            if (!out.isEmpty()) {
                Session top = out.get(out.size() - 1);
                if ((top.charging == curCharging) && top.start - cur.t <= GAP_MS) {
                    top.start = cur.t;
                    top.startLevel = cur.level;
                    top.avgW += cur.watts;
                    top.maxW = Math.max(top.maxW, cur.watts);
                    top.samples++;
                    continue;
                }
            }
            Session s = new Session();
            s.charging = curCharging;
            s.start = s.end = cur.t;
            s.startLevel = s.endLevel = cur.level;
            s.avgW = cur.watts;
            s.maxW = cur.watts;
            s.samples = 1;
            out.add(s);
        }
        for (Session s : out) {
            long durMin = Math.max(1, (s.end - s.start) / 60000);
            s.energyWh = s.avgW * durMin / 60.0;
            s.avgW = s.samples > 0 ? s.avgW / s.samples : 0;
        }
        return out;
    }

    /** 主页卡片摘要：今日充电/放电统计 + 最近会话 */
    public static synchronized String todaySummary(Context ctx) {
        load(ctx);
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        long midnight = cal.getTimeInMillis();

        double chargeWh = 0, dischargeWh = 0;
        int chargeCnt = 0;
        for (int i = 0; i < data.size(); i++) {
            Sample s = data.get(i);
            if (s.t < midnight) continue;
            long prev = i > 0 ? data.get(i - 1).t : s.t;
            long dtMin = Math.max(1, Math.min(GAP_MS, s.t - prev) / 60000);
            if (s.status == 'C') {
                chargeWh += s.watts * dtMin / 60.0;
            } else {
                dischargeWh += s.watts * dtMin / 60.0;
            }
        }
        List<Session> ss = sessions(ctx, 5);
        // 今日充电次数按会话统计
        for (Session s : ss) {
            if (s.charging && s.end >= midnight) chargeCnt++;
        }

        StringBuilder sb = new StringBuilder();
        if (data.isEmpty()) {
            sb.append("暂无记录 · 保持应用运行自动采样");
        } else {
            sb.append(String.format(Locale.US, "今日充电 %d 次 · %.1fWh ｜ 放电 %.1fWh",
                    chargeCnt, chargeWh, dischargeWh));
            Session last = ss.isEmpty() ? null : ss.get(0);
            if (last != null) {
                sb.append(String.format(Locale.US, "%n最近: %s %d%%→%d%% · 平均%.1fW",
                        last.charging ? "充电" : "放电",
                        last.startLevel, last.endLevel, last.avgW));
            }
        }
        return sb.toString();
    }

    /** 历史弹窗正文 */
    public static synchronized String historyText(Context ctx, int limit) {
        load(ctx);
        List<Session> ss = sessions(ctx, limit);
        SimpleDateFormat df = new SimpleDateFormat("MM-dd HH:mm", Locale.US);
        StringBuilder sb = new StringBuilder();
        sb.append(todaySummary(ctx)).append("\n\n");
        if (ss.isEmpty()) {
            sb.append("暂无历史会话");
            return sb.toString();
        }
        for (Session s : ss) {
            long durMin = Math.max(1, (s.end - s.start) / 60000);
            sb.append(String.format(Locale.US, "%s ~ %s (%d分)%n  %s %d%%→%d%% · 平均 %.1fW · 峰值 %.1fW · %.1fWh%n",
                    df.format(new Date(s.start)),
                    durMin >= 60
                            ? String.format(Locale.US, "%s+%dh", df.format(new Date(s.end)).substring(6), durMin / 60)
                            : df.format(new Date(s.end)).substring(6),
                    durMin,
                    s.charging ? "充电" : "放电",
                    s.startLevel, s.endLevel, s.avgW, s.maxW, s.energyWh));
        }
        return sb.toString();
    }
}
