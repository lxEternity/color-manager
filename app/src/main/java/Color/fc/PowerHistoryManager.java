package Color.fc;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

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
 * 存储: filesDir/power_samples.csv，每行: 时间ms,电量%,功率W,状态(C充电/D放电/F满电),温度℃
 */
public class PowerHistoryManager {

    public static class Sample {
        public long t;
        public int level;
        public double watts;
        public char status;   // C / D / F
        public double tempC;  // ℃（旧格式无此列时为 0）
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
                if (p.length >= 5) s.tempC = Double.parseDouble(p[4]);
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
        s.tempC = st.tempC;
        data.add(s);

        try (PrintWriter w = new PrintWriter(new FileWriter(file(ctx), true))) {
            w.println(s.t + "," + s.level + "," + String.format(Locale.US, "%.2f", s.watts)
                    + "," + s.status + "," + String.format(Locale.US, "%.1f", s.tempC));
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
                w.println(s.t + "," + s.level + "," + String.format(Locale.US, "%.2f", s.watts)
                        + "," + s.status + "," + String.format(Locale.US, "%.1f", s.tempC));
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

    /** 最近 minutes 分钟的功耗采样曲线（时间正序），用于主页功耗统计图 */
    public static synchronized float[] recentWatts(Context ctx, int minutes) {
        load(ctx);
        long cutoff = System.currentTimeMillis() - minutes * 60_000L;
        ArrayList<Float> rev = new ArrayList<>();
        for (int i = data.size() - 1; i >= 0; i--) {
            Sample s = data.get(i);
            if (s.t < cutoff) break;
            rev.add((float) s.watts);
        }
        float[] out = new float[rev.size()];
        for (int i = 0; i < rev.size(); i++) out[i] = rev.get(rev.size() - 1 - i);
        return out;
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

    /** 历史弹窗正文（时间精确到分秒） */
    public static synchronized String historyText(Context ctx, int limit) {
        load(ctx);
        List<Session> ss = sessions(ctx, limit);
        SimpleDateFormat df = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US);
        StringBuilder sb = new StringBuilder();
        sb.append(todaySummary(ctx)).append("\n\n");
        if (ss.isEmpty()) {
            sb.append("暂无历史会话");
            return sb.toString();
        }
        for (Session s : ss) {
            long durSec = Math.max(1, (s.end - s.start) / 1000);
            sb.append(String.format(Locale.US, "%s ~ %s (%d分%02d秒)%n  %s %d%%→%d%% · 平均 %.1fW · 峰值 %.1fW · %.1fWh%n",
                    df.format(new Date(s.start)),
                    df.format(new Date(s.end)),
                    durSec / 60, durSec % 60,
                    s.charging ? "充电" : "放电",
                    s.startLevel, s.endLevel, s.avgW, s.maxW, s.energyWh));
        }
        return sb.toString();
    }

    /** 近 minutes 分钟的采样副本（时间正序），用于 Scene 样式详细记录 */
    public static synchronized List<Sample> recentSamples(Context ctx, int minutes) {
        load(ctx);
        long cutoff = System.currentTimeMillis() - minutes * 60_000L;
        List<Sample> out = new ArrayList<>();
        for (Sample s : data) {
            if (s.t >= cutoff) out.add(s);
        }
        return out;
    }

    // ==================== Scene 样式功耗详细记录 ====================

    /** 沉浸背景（自定义背景图/透壁纸）是否生效 */
    private static boolean immersiveOn(Context ctx) {
        return ThemeStore.transparentBg(ctx)
                || (ThemeStore.imageBg(ctx) && ThemeStore.bgFile(ctx).exists());
    }

    /** 沉浸时的玻璃透明度（0-255），与主题「控件玻璃透明度」同步 */
    private static int glassA(Context ctx) {
        return immersiveOn(ctx) ? Math.max(120, Math.round(ThemeStore.glassAlpha(ctx) * 2.55f)) : 255;
    }

    /** 打开详细记录：网格坐标轴 + 双色曲线（充电绿/放电主题色）+ 渐变填充 + 能耗摘要 */
    public static void openDetail(Activity act) {
        new Thread(() -> {
            final List<Sample> ss = recentSamples(act, 180);
            PowerMonitor.BatteryStat live = null;
            try {
                live = PowerMonitor.readOnce();
            } catch (Exception ignored) {
            }
            final PowerMonitor.BatteryStat fl = live;
            act.runOnUiThread(() -> {
                AlertDialog dlg = new AlertDialog.Builder(act)
                        .setTitle("功耗记录")
                        .setView(buildDetailBody(act, ss, fl))
                        .setPositiveButton("关闭", null)
                        .setNeutralButton("清空记录", (d, w) -> {
                            clear(act);
                            Toast.makeText(act, "已清空功耗记录", Toast.LENGTH_SHORT).show();
                        })
                        .show();
                // 自定义背景图沉浸：弹窗底同步玻璃透明度，透出背景
                if (immersiveOn(act) && dlg.getWindow() != null) {
                    ColorDrawable cd = new ColorDrawable(act.getColor(R.color.bgCard));
                    cd.setAlpha(glassA(act));
                    dlg.getWindow().setBackgroundDrawable(cd);
                }
            });
        }).start();
    }

    /** 详细记录内容：标题 + 曲线 + 摘要行 */
    private static View buildDetailBody(Context ctx, List<Sample> ss, PowerMonitor.BatteryStat live) {
        int dp = Math.round(ctx.getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp * 4, dp * 2, dp * 4, 0);

        int n = ss.size();
        double avgW = 0, energyWh = 0, tempSum = 0;
        int tempN = 0;
        for (int i = 0; i < n; i++) {
            Sample s = ss.get(i);
            avgW += s.watts;
            if (s.tempC > 0) {
                tempSum += s.tempC;
                tempN++;
            }
            if (i > 0) {
                energyWh += (ss.get(i - 1).watts + s.watts) / 2.0
                        * (s.t - ss.get(i - 1).t) / 3_600_000.0;   // 梯形积分 Wh
            }
        }
        avgW = n > 0 ? avgW / n : 0;
        double peakW = 0;
        for (int i = 0; i < n; i++) peakW = Math.max(peakW, ss.get(i).watts);
        long spanMs = n > 1 ? ss.get(n - 1).t - ss.get(0).t : 0;
        int levelNow = live != null && live.level >= 0 ? live.level : (n > 0 ? ss.get(n - 1).level : -1);
        double volts = live != null ? live.volts : 0;
        boolean charging = live != null && !live.status.isEmpty()
                ? "Charging".equalsIgnoreCase(live.status)
                : n > 0 && ss.get(n - 1).status == 'C';
        boolean full = live != null && "Full".equalsIgnoreCase(live.status);
        double avgTemp = tempN > 0 ? tempSum / tempN : (live != null ? live.tempC : 0);

        // 理论续航：按窗口内电量下降速率估算（%/h → 剩余可用小时）
        String est = "--";
        if (!charging && !full && n > 1 && spanMs > 0 && levelNow >= 0) {
            double drop = ss.get(0).level - ss.get(n - 1).level;
            double rate = drop / (spanMs / 3_600_000.0);
            if (rate > 0.5) est = fmtDur((long) (levelNow / rate * 3_600_000.0));
        }

        // 标题行：使用过程 + 当前电量（曲线顶部信息条为功率/温度，不与电量重复）
        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(ctx);
        title.setText("使用过程 · 近3小时");
        title.setTextColor(ctx.getColor(R.color.textPrimary));
        title.setTextSize(13);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView lvl = new TextView(ctx);
        lvl.setText(levelNow >= 0 ? "电量 " + levelNow + "%" : "电量 --");
        lvl.setTextColor(ctx.getColor(charging || full ? R.color.green : R.color.accent));
        lvl.setTextSize(12);
        lvl.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(lvl);
        root.addView(head);

        // 最近一次充电会话（窗口尾部连续 C/F 段）：已充电时长 / 充满后总时长 / 已充 Wh
        if (n > 0) {
            int e = n - 1;
            while (e >= 0 && (ss.get(e).status == 'C' || ss.get(e).status == 'F')) e--;
            int cs = e + 1;
            if (cs < n && ss.get(cs).status == 'C') {
                long st = ss.get(cs).t;
                long fullAt = 0;
                double wh = 0, lastW = ss.get(cs).watts;
                long lastT = st;
                for (int k = cs + 1; k < n; k++) {
                    Sample s2 = ss.get(k);
                    if (fullAt == 0 && s2.status == 'F') fullAt = s2.t;
                    if (s2.status == 'C') wh += (lastW + s2.watts) / 2.0
                            * (s2.t - lastT) / 3_600_000.0;   // 只累计充电阶段
                    lastT = s2.t;
                    lastW = s2.watts;
                }
                long end = charging ? System.currentTimeMillis() : ss.get(n - 1).t;
                SimpleDateFormat dfm = new SimpleDateFormat("HH:mm:ss", Locale.US);
                String chargeInfo = String.format(Locale.US, "%s %s 起 · 已充 %s · 总时长 %s · 已充 %.1fWh",
                        fullAt > 0 ? "充满" : "充电中",
                        dfm.format(new Date(st)),
                        fmtDur(fullAt > 0 ? fullAt - st : end - st),
                        fmtDur(Math.max(0, end - st)),
                        wh);
                TextView ci = new TextView(ctx);
                ci.setText(chargeInfo);
                ci.setTextColor(ctx.getColor(fullAt > 0 || charging ? R.color.green : R.color.textSecondary));
                ci.setTextSize(10);
                ci.setTypeface(Typeface.DEFAULT_BOLD);
                ci.setPadding(0, dp * 3, 0, 0);
                root.addView(ci);
            }
        }

        if (n == 0) {
            TextView empty = new TextView(ctx);
            empty.setText("暂无记录 · 保持应用运行将自动采样（每分钟一条）");
            empty.setTextColor(ctx.getColor(R.color.textSecondary));
            empty.setTextSize(10);
            empty.setPadding(0, dp * 6, 0, 0);
            root.addView(empty);
        }

        SceneChart chart = new SceneChart(ctx, ss, glassA(ctx));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp * 230);
        cp.topMargin = dp * 6;
        root.addView(chart, cp);

        // 摘要行（Scene: 总能耗 / 平均温度 / 电压 / 充电状态）
        root.addView(colRow(ctx, dp, false, new String[][]{
                {"总能耗", n > 0 ? String.format(Locale.US, "%.1fWh", energyWh) : "--"},
                {"平均温度", avgTemp > 0 ? String.format(Locale.US, "%.1f℃", avgTemp) : "--"},
                {"电压", volts > 0 ? String.format(Locale.US, "%.2fV", volts) : "--"},
                {"充电状态", full ? "已充满" : (charging ? "充电中" : "未充电")}
        }));

        // 大数字行（Scene: 平均功耗 / 峰值 / 已使用 / 理论续航）
        root.addView(colRow(ctx, dp, true, new String[][]{
                {"平均功耗", n > 0 ? String.format(Locale.US, "%.2fW", avgW) : "--"},
                {"峰值", n > 0 ? String.format(Locale.US, "%.2fW", peakW) : "--"},
                {"已使用", n > 1 ? fmtDur(spanMs) : "--"},
                {"理论续航", est}
        }));
        return root;
    }

    /** 横向等分列；big=true 数值在上（大号主题色），否则标签在上 */
    private static LinearLayout colRow(Context ctx, int dp, boolean big, String[]... cols) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (String[] col : cols) {
            LinearLayout cell = new LinearLayout(ctx);
            cell.setOrientation(LinearLayout.VERTICAL);
            TextView l = new TextView(ctx);
            l.setText(col[0]);
            l.setTextColor(ctx.getColor(R.color.textDim));
            l.setTextSize(9);
            TextView v = new TextView(ctx);
            v.setText(col[1]);
            v.setTextSize(big ? 16 : 12);
            v.setTypeface(Typeface.DEFAULT_BOLD);
            v.setTextColor(big ? ctx.getColor(R.color.accent) : ctx.getColor(R.color.textPrimary));
            if (big) {
                cell.addView(v);
                cell.addView(l);
            } else {
                cell.addView(l);
                cell.addView(v);
            }
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            p.topMargin = dp * 10;
            row.addView(cell, p);
        }
        return row;
    }

    /** 时长格式化（精确到秒）：1h23m45s / 41m32s / 45s */
    private static String fmtDur(long ms) {
        long s = Math.max(0, ms / 1000);
        long h = s / 3600;
        s %= 3600;
        long m = s / 60;
        s %= 60;
        if (h > 0) return h + "h" + m + "m" + s + "s";
        if (m > 0) return m + "m" + s + "s";
        return s + "s";
    }

    /**
     * Scene 风格功耗曲线：圆角卡（玻璃透明度同步沉浸背景）+ 网格坐标轴
     * + 双色分段曲线（充电=绿色 / 放电=主题色，实线细线）+ 渐变填充
     * + 双 Y 轴：左功率（量程随峰值自适应）+ 右温度 20~70℃ 橙色实线
     * + 顶部信息条（实时功率/实时温度，黑色大字与曲线色区分、不与曲线重叠）
     * + X 轴相对分钟刻度（-180/-135/-90/-45/0）
     */
    private static class SceneChart extends View {
        private final List<Sample> ss;
        private final int cAccent, cGreen, cOrange, cCard, cGrid, cDim, cText;
        private final int cardAlpha;
        private final float dp;
        private final Paint pGrid = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pGridV = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pAxis = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pLab = new Paint(Paint.ANTI_ALIAS_FLAG);      // 轴刻度
        private final Paint pHeadL = new Paint(Paint.ANTI_ALIAS_FLAG);   // 信息条标签
        private final Paint pHeadV = new Paint(Paint.ANTI_ALIAS_FLAG);    // 信息条数值
        private final Paint pLine = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pTemp = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pDot = new Paint(Paint.ANTI_ALIAS_FLAG);

        SceneChart(Context ctx, List<Sample> ss, int cardAlpha) {
            super(ctx);
            this.ss = ss;
            this.cardAlpha = cardAlpha;
            dp = ctx.getResources().getDisplayMetrics().density;
            cAccent = ctx.getColor(R.color.accent);
            cGreen = ctx.getColor(R.color.green);
            cOrange = ctx.getColor(R.color.orange);
            cCard = ctx.getColor(R.color.bgCard);
            cGrid = ctx.getColor(R.color.divider);
            cDim = ctx.getColor(R.color.textSecondary);
            cText = ctx.getColor(R.color.textPrimary);
            pGrid.setColor(cDim);
            pGrid.setStrokeWidth(1);
            pGrid.setAlpha(170);   // 水平网格明显
            pGridV.setColor(cDim);
            pGridV.setStrokeWidth(1);
            pGridV.setAlpha(70);    // 垂直网格淡
            pAxis.setColor(cDim);
            pAxis.setStrokeWidth(1);
            pAxis.setAlpha(160);
            pLab.setColor(cDim);
            pLab.setTextSize(8 * dp);
            pHeadL.setColor(cDim);
            pHeadL.setTextSize(8 * dp);
            pHeadV.setColor(cText);
            pHeadV.setTextSize(16 * dp);
            pHeadV.setTypeface(Typeface.DEFAULT_BOLD);
            pLine.setStyle(Paint.Style.STROKE);
            pLine.setStrokeWidth(1.2f * dp);   // 线条更细
            pTemp.setColor(cOrange);
            pTemp.setStyle(Paint.Style.STROKE);
            pTemp.setStrokeWidth(1.2f * dp);   // 温度实线
            pDot.setStyle(Paint.Style.FILL);
        }

        @Override
        protected void onDraw(Canvas c) {
            super.onDraw(c);
            int w = getWidth(), h = getHeight();
            float left = 30 * dp, right = w - 32 * dp, top = 46 * dp, bottom = h - 18 * dp;
            float pw = right - left, ph = bottom - top;

            // 圆角卡底：透明度与沉浸背景玻璃设置同步
            Paint card = new Paint(Paint.ANTI_ALIAS_FLAG);
            card.setColor(cCard);
            card.setAlpha(cardAlpha);
            c.drawRoundRect(0, 0, w, h, 10 * dp, 10 * dp, card);

            int n = ss.size();
            if (n < 2 || pw <= 0 || ph <= 0) {
                pLab.setColor(cDim);
                c.drawText("采样中…", left, h / 2f, pLab);
                return;
            }

            double maxW = 0;
            double maxT = 0;
            for (Sample s : ss) {
                if (s.watts > maxW) maxW = s.watts;
                if (s.tempC > maxT) maxT = s.tempC;
            }
            boolean hasTemp = maxT > 0;

            // 功率尺度自适应：跟随曲线峰值上升（5 档网格），窗口高度不变、曲线压缩
            int[] steps = {1, 2, 4, 5, 10, 20, 25, 50};
            int step = 50;
            for (int s : steps) {
                if (maxW <= s * 5.0) {
                    step = s;
                    break;
                }
            }
            float vmax = step * 5f;
            // 温度轴固定 20~70℃（5 档，10℃ 一档），与功率网格线共用
            float tMin = 20f, tMax = 70f;

            long t0 = ss.get(0).t;
            long span = Math.max(60_000L, ss.get(n - 1).t - t0);
            Sample last = ss.get(n - 1);

            // 顶部信息条（曲线框外）：左半「实时功率」/ 右半「实时温度」，黑色大字与曲线色区分
            stat(c, "实时功率", String.format(Locale.US, "%.2fW", last.watts),
                    left + pw * 0.25f);
            stat(c, "实时温度", hasTemp && last.tempC > 0
                            ? String.format(Locale.US, "%.1f℃", last.tempC) : "--",
                    left + pw * 0.75f);

            // 水平网格 + 双 Y 轴刻度：左功率 0~5×step，右温度 20~70℃
            for (int i = 0; i <= 5; i++) {
                float gy = bottom - ph * i / 5f;
                c.drawLine(left, gy, right, gy, pGrid);
                String lb = (i * step) + "W";
                c.drawText(lb, 2 * dp, gy + 3 * dp, pLab);
                String tb = ((int) (tMax - (tMax - tMin) * i / 5f)) + "℃";
                float tw = pLab.measureText(tb);
                c.drawText(tb, w - 3 * dp - tw, gy + 3 * dp, pLab);
            }
            // 垂直网格（淡）+ X 轴时钟刻度（框下外侧 HH:mm:ss，无负数歧义）
            SimpleDateFormat tf = new SimpleDateFormat("HH:mm:ss", Locale.US);
            for (int i = 0; i <= 4; i++) {
                float gx = left + pw * i / 4f;
                c.drawLine(gx, top, gx, bottom, pGridV);
                String lb = tf.format(new Date(t0 + span * i / 4));
                float tw = pLab.measureText(lb);
                float tx = Math.min(Math.max(gx - tw / 2, left), right - tw);
                c.drawText(lb, tx, bottom + 12 * dp, pLab);
            }
            // 底部轴线（Scene 样式：下边框加深）
            c.drawLine(left, bottom, right, bottom, pAxis);

            // 功率曲线：充电段绿色 / 放电段主题色，分段渐变填充
            int i = 0;
            while (i < n) {
                boolean ch = ss.get(i).status == 'C';
                Path line = new Path();
                Path fill = new Path();
                float px0 = xOf(ss.get(i).t, t0, span, left, pw);
                float py0 = yOf(ss.get(i).watts, bottom, ph, vmax);
                if (i > 0) {   // 与前一段衔接处用本段颜色补画连接线
                    float prx = xOf(ss.get(i - 1).t, t0, span, left, pw);
                    float pry = yOf(ss.get(i - 1).watts, bottom, ph, vmax);
                    line.moveTo(prx, pry);
                    fill.moveTo(prx, bottom);
                    fill.lineTo(prx, pry);
                } else {
                    fill.moveTo(px0, bottom);
                }
                line.lineTo(px0, py0);
                fill.lineTo(px0, py0);
                int j = i + 1;
                while (j < n && (ss.get(j).status == 'C') == ch) {
                    line.lineTo(xOf(ss.get(j).t, t0, span, left, pw),
                            yOf(ss.get(j).watts, bottom, ph, vmax));
                    fill.lineTo(xOf(ss.get(j).t, t0, span, left, pw),
                            yOf(ss.get(j).watts, bottom, ph, vmax));
                    j++;
                }
                fill.lineTo(xOf(ss.get(j - 1).t, t0, span, left, pw), bottom);
                fill.close();
                int col = ch ? cGreen : cAccent;
                pLine.setColor(col);
                pFill.setShader(new LinearGradient(0, top, 0, bottom,
                        (col & 0x00FFFFFF) | 0x2E000000, 0x00000000, Shader.TileMode.CLAMP));
                c.drawPath(fill, pFill);   // 分段渐变面积（上浓下淡）
                c.drawPath(line, pLine);   // 分段实线曲线
                i = j;
            }

            // 温度曲线（橙色实线，右轴 20~70℃，仅存在温度数据时绘制）
            if (hasTemp) {
                Path tp = new Path();
                boolean started = false;
                for (Sample s : ss) {
                    float x = xOf(s.t, t0, span, left, pw);
                    float y = bottom - ph * (float) (Math.max(0, Math.min(tMax, s.tempC) - tMin) / (tMax - tMin));
                    if (!started) {
                        tp.moveTo(x, y);
                        started = true;
                    } else {
                        tp.lineTo(x, y);
                    }
                }
                c.drawPath(tp, pTemp);
                pDot.setColor(cOrange);
                c.drawCircle(Math.min(xOf(last.t, t0, span, left, pw), right - 2 * dp),
                        bottom - ph * (float) (Math.max(0, Math.min(tMax, last.tempC) - tMin) / (tMax - tMin)),
                        2 * dp, pDot);
            }

            // 功率终点小圆点（无文字，避免与曲线重叠）
            pDot.setColor(last.status == 'C' ? cGreen : cAccent);
            c.drawCircle(Math.min(xOf(last.t, t0, span, left, pw), right - 2 * dp),
                    Math.max(yOf(last.watts, bottom, ph, vmax), top + 2 * dp), 2 * dp, pDot);
        }

        /** 顶部信息条单项：标签与数值均以 x 为中心对齐 */
        private void stat(Canvas c, String label, String value, float x) {
            float lx = x - pHeadL.measureText(label) / 2f;
            c.drawText(label, lx, 13 * dp, pHeadL);
            float vx = x - pHeadV.measureText(value) / 2f;
            c.drawText(value, vx, 34 * dp, pHeadV);
        }

        private float xOf(long t, long t0, long span, float left, float pw) {
            return left + pw * (t - t0) / span;
        }

        private float yOf(double watts, float bottom, float ph, float vmax) {
            return bottom - ph * (float) (Math.max(0, Math.min(vmax, watts)) / vmax);
        }
    }
}
