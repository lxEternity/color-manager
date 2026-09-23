package Color.fc;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 迷你悬浮窗监视器（收纳帧率录制，原独立录制悬浮窗已合并）：
 * - 头部按钮：● 录制开关 · ⚙ 单项配置 · ✕ 关闭
 * - 监测行：功耗 / CPU / GPU / CPU温度+SOC温度 / 帧率
 * - 长按（或 ⚙）进入单项配置：点按各行单独开/关监测项（持久化），✓ 完成
 * - ● 单击开始录制（屏幕帧率 + CPU 总占用 + 每核心负载，500ms 采样），再次单击停止并保存曲线图 PNG
 * - 功耗计算与主页同步（电芯模式），顺带写入功耗历史记录
 */
public class MonitorService extends Service {

    /** 主页开关状态同步用 */
    public static volatile boolean running = false;

    private WindowManager wm;
    private LinearLayout box;
    private TextView tvRec, tvGear, tvClose, tvCfgHint, tvDetail;
    /** 监测行: 0功耗 1CPU 2GPU 3温度 4帧率 */
    private TextView[] rowViews;
    private final boolean[] rowsOn = new boolean[5];
    private static final int[] ROW_COLORS = {0xFF00E5FF, 0xFFE6EDF3, 0xFFE6EDF3, 0xFFE6EDF3, 0xFF8B949E};
    private static final String[] ROW_KEYS = {"mon_power", "mon_cpu", "mon_gpu", "mon_temp", "mon_hz"};
    private WindowManager.LayoutParams lp;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean detailed = false;
    private boolean configMode = false;
    /** 电芯模式（与主页同步）：0=自动校准 1=强制单电芯 2=强制双电芯 */
    private int cellMode = 0;

    // CPU 占用差分基准（UI 2s 循环）
    private long lastIdle = -1, lastTotal = -1;
    // 录制 500ms 循环基准
    private long recLastIdle = -1, recLastTotal = -1;
    private long[] lastCoreIdle, lastCoreTotal;

    // 帧率录制数据
    private boolean recording = false;
    private long recStart;
    private final ArrayList<Long> ts = new ArrayList<>();
    private final ArrayList<Float> fps = new ArrayList<>();
    private final ArrayList<Float> cpuTot = new ArrayList<>();
    private final ArrayList<float[]> cores = new ArrayList<>();
    private int coreCount = 0;

    private static final int[] CORE_COLORS = {
            0xFF00E5FF, 0xFF22D3EE, 0xFF10B981, 0xFFF59E0B,
            0xFFEF4444, 0xFF8B5CF6, 0xFFEC4899, 0xFF84CC16
    };

    private static final String SCAN =
            "g=$(cat /sys/class/kgsl/kgsl-3d0/gpuclk 2>/dev/null);"
                    + "[ -n \"$g\" ] || g=$(cat /sys/class/kgsl/kgsl-3d0/devfreq/cur_freq 2>/dev/null);"
                    + "[ -n \"$g\" ] || g=$(cat /sys/class/devfreq/*qcom,gpu*/cur_freq 2>/dev/null);"
                    + "[ -n \"$g\" ] || g=$(cat /sys/class/devfreq/*gpu*/cur_freq 2>/dev/null);"
                    + "echo \"G:$g\";"
                    + "f=$(for p in /sys/devices/system/cpu/cpufreq/policy*; do "
                    + "cat $p/scaling_cur_freq 2>/dev/null; done | sort -n | tail -1);"
                    + "echo \"F:$f\";"
                    + "for z in /sys/class/thermal/thermal_zone*; do "
                    + "[ -f \"$z/temp\" ] || continue; "
                    + "echo \"T:$(cat \"$z/type\" 2>/dev/null):$(cat \"$z/temp\" 2>/dev/null)\"; done";

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "stop".equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        // 与主页共享电芯模式 + 单项开关持久化
        SharedPreferences p = getSharedPreferences("colorfc", MODE_PRIVATE);
        cellMode = p.getInt("cellMode", 0);
        for (int i = 0; i < rowsOn.length; i++) rowsOn[i] = p.getBoolean(ROW_KEYS[i], true);
        startForeground(1, notif());
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        buildView();
        ui.postDelayed(this::sample, 300);
    }

    @Override
    public void onDestroy() {
        running = false;
        ui.removeCallbacksAndMessages(null);
        // 录制中被关闭 → 保存数据避免丢失
        if (recording && ts.size() >= 4) stopRec(false);
        if (box != null) {
            try {
                wm.removeView(box);
            } catch (Exception ignored) {
            }
        }
        super.onDestroy();
    }

    private void buildView() {
        box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xD9101820);
        bg.setStroke(1, 0x6600E5FF);
        bg.setCornerRadius(dp(10));
        box.setBackground(bg);
        int pad = dp(10);
        box.setPadding(pad, dp(4), pad, dp(8));

        // 头部：● 录制 · ⚙ 配置 · 弹簧 · ✕ 关闭
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        tvRec = new TextView(this);
        tvRec.setTextColor(0xFF00E5FF);
        tvRec.setTextSize(12);
        tvRec.setText("●");
        tvRec.setPadding(dp(4), dp(2), dp(2), dp(2));
        head.addView(tvRec, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        tvGear = new TextView(this);
        tvGear.setTextColor(0xFF8B949E);
        tvGear.setTextSize(12);
        tvGear.setText("⚙");
        tvGear.setPadding(dp(2), dp(2), dp(2), dp(2));
        head.addView(tvGear, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        View spring = new View(this);
        head.addView(spring, new LinearLayout.LayoutParams(0, 1, 1f));
        tvClose = new TextView(this);
        tvClose.setTextColor(0xFF8B949E);
        tvClose.setTextSize(11);
        tvClose.setText("✕");
        tvClose.setPadding(dp(6), dp(2), dp(2), dp(2));
        head.addView(tvClose, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        box.addView(head);

        // 配置模式提示（默认隐藏）
        tvCfgHint = row(0xFF6B7785, 10);
        tvCfgHint.setText("点按各行开关 · 点 ✓ 完成");
        tvCfgHint.setVisibility(View.GONE);

        rowViews = new TextView[rowsOn.length];
        rowViews[0] = row(ROW_COLORS[0], 13);
        rowViews[1] = row(ROW_COLORS[1], 11);
        rowViews[2] = row(ROW_COLORS[2], 11);
        rowViews[3] = row(ROW_COLORS[3], 11);
        rowViews[4] = row(ROW_COLORS[4], 11);
        tvDetail = row(0xFF8B949E, 11);
        tvDetail.setVisibility(View.GONE);
        applyRowStates();

        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = dp(24);
        lp.y = dp(260);

        box.setOnTouchListener(new View.OnTouchListener() {
            float sx, sy, dx, dy;
            long downAt;
            boolean moved = false, longFired = false;
            final Runnable longPress = new Runnable() {
                @Override
                public void run() {
                    if (moved || longFired) return;
                    longFired = true;
                    toggleConfig();
                }
            };

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        sx = e.getRawX();
                        sy = e.getRawY();
                        dx = sx - lp.x;
                        dy = sy - lp.y;
                        downAt = System.currentTimeMillis();
                        moved = false;
                        longFired = false;
                        ui.postDelayed(longPress, 550);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (Math.abs(e.getRawX() - sx) > dp(12) || Math.abs(e.getRawY() - sy) > dp(12)) {
                            moved = true;
                            ui.removeCallbacks(longPress);
                        }
                        lp.x = (int) (e.getRawX() - dx);
                        lp.y = (int) (e.getRawY() - dy);
                        try {
                            wm.updateViewLayout(box, lp);
                        } catch (Exception ignored) {
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        ui.removeCallbacks(longPress);
                        if (longFired) return true;   // 长按已处理
                        if (Math.abs(e.getRawX() - sx) < dp(12)
                                && Math.abs(e.getRawY() - sy) < dp(12)
                                && System.currentTimeMillis() - downAt < 350) {
                            if (hit(tvClose, e)) {
                                stopSelf();
                                return true;
                            }
                            if (hit(tvRec, e)) {
                                toggleRec();
                                return true;
                            }
                            if (hit(tvGear, e)) {
                                toggleConfig();
                                return true;
                            }
                            if (configMode) {
                                toggleRowAt(e);
                                return true;
                            }
                            detailed = !detailed;
                            applyRowStates();
                        }
                        return true;
                }
                return false;
            }
        });

        wm.addView(box, lp);
    }

    /** 宽度撑满，便于整行点击命中 */
    private TextView row(int color, float sizeSp) {
        TextView tv = new TextView(this);
        tv.setTextColor(color);
        tv.setTextSize(sizeSp);
        tv.setTypeface(Typeface.MONOSPACE);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        p.bottomMargin = dp(2);
        box.addView(tv, p);
        return tv;
    }

    private boolean hit(TextView v, MotionEvent e) {
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        return e.getRawX() >= loc[0] && e.getRawX() <= loc[0] + v.getWidth()
                && e.getRawY() >= loc[1] && e.getRawY() <= loc[1] + v.getHeight();
    }

    /** 进入/退出单项配置模式 */
    private void toggleConfig() {
        configMode = !configMode;
        tvCfgHint.setVisibility(configMode ? View.VISIBLE : View.GONE);
        tvGear.setText(configMode ? "✓" : "⚙");
        tvGear.setTextColor(configMode ? 0xFF10B981 : 0xFF8B949E);
        if (!configMode) saveRows();
        applyRowStates();
    }

    /** 配置模式点按行 → 开/关对应监测项 */
    private void toggleRowAt(MotionEvent e) {
        for (int i = 0; i < rowViews.length; i++) {
            if (hit(rowViews[i], e)) {
                rowsOn[i] = !rowsOn[i];
                applyRowStates();
                return;
            }
        }
    }

    /** 应用各行显示状态：配置模式全显(关闭项置灰)，正常模式隐藏关闭项 */
    private void applyRowStates() {
        for (int i = 0; i < rowViews.length; i++) {
            TextView v = rowViews[i];
            if (configMode) {
                v.setVisibility(View.VISIBLE);
                v.setTextColor(rowsOn[i] ? ROW_COLORS[i] : 0xFF4B5563);
            } else {
                v.setVisibility(rowsOn[i] ? View.VISIBLE : View.GONE);
                v.setTextColor(ROW_COLORS[i]);
            }
        }
        tvDetail.setVisibility(!configMode && detailed ? View.VISIBLE : View.GONE);
    }

    private void saveRows() {
        SharedPreferences.Editor ed = getSharedPreferences("colorfc", MODE_PRIVATE).edit();
        for (int i = 0; i < rowsOn.length; i++) ed.putBoolean(ROW_KEYS[i], rowsOn[i]);
        ed.apply();
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ==================== 常规采样（2s） ====================

    private void sample() {
        new Thread(() -> {
            String out = "";
            try {
                RootShell.Result r = RootShell.exec(SCAN);
                if (r.ok() && r.out != null) out = r.out;
            } catch (Exception ignored) {
            }
            PowerMonitor.BatteryStat st = PowerMonitor.readOnce();
            // 功耗历史记录（内部节流）
            if (st != null) PowerHistoryManager.record(this, st);
            double busy = readCpuBusy();
            double gpuHz = parseGpu(out);
            double cpuMaxK = parseFreq(out);
            double cpuT = parseZoneTemp(out, "cpu", st != null ? st.tempC : 0);
            double socT = parseZoneTemp(out, "soc", cpuT);
            float hz = refreshRate();
            final PowerMonitor.BatteryStat fst = st;
            final double fbusy = busy, fgpu = gpuHz, fcpu = cpuMaxK, fcput = cpuT, fsoct = socT;
            final float fhz = hz;
            ui.post(() -> updateUi(fst, fbusy, fgpu, fcpu, fcput, fsoct, fhz));
            ui.postDelayed(this::sample, 2000);
        }).start();
    }

    private void updateUi(PowerMonitor.BatteryStat st, double busy, double gpuHz,
                          double cpuMaxK, double cpuT, double socT, float hz) {
        if (st != null) {
            // 与主页一致的电芯模式修正
            int cells = cellMode == 0 ? st.cells : cellMode;
            boolean up = cells >= 2 && st.cells < 2;
            double amps = up ? st.amps * 2 : st.amps;
            double w = up ? Math.abs(st.volts * amps) : Math.abs(st.watts);
            rowViews[0].setText(String.format(Locale.US, "功耗 %.2fW", w));
            if (detailed) {
                tvDetail.setText(String.format(Locale.US, "%.2fV %.2fA · %d%% %s",
                        st.volts, Math.abs(amps), st.level,
                        st.tempC > 0 ? String.format(Locale.US, "电%.0f℃", st.tempC) : ""));
            }
        } else {
            rowViews[0].setText("功耗 --W");
        }
        String cpu = "CPU ";
        cpu += busy >= 0 ? String.format(Locale.US, "%.0f%%", busy) : "--";
        if (cpuMaxK > 0) cpu += String.format(Locale.US, " · %.0fMHz", cpuMaxK / 1000);
        rowViews[1].setText(cpu);
        rowViews[2].setText(gpuHz > 0 ? String.format(Locale.US, "GPU %.0fMHz", gpuHz / 1e6) : "GPU --");
        rowViews[3].setText(String.format(Locale.US, "CPU温度 %.1f℃ SOC温度 %.1f℃", cpuT, socT));
        // 录制中该行由录制循环刷新（● mm:ss · Hz）
        if (!recording) {
            rowViews[4].setText(hz > 0 ? String.format(Locale.US, "%.0fHz", hz) : "--Hz");
        }
    }

    // ==================== 帧率录制（500ms） ====================

    private void toggleRec() {
        if (!recording) {
            recording = true;
            recStart = System.currentTimeMillis();
            ts.clear();
            fps.clear();
            cpuTot.clear();
            cores.clear();
            tvRec.setText("■");
            tvRec.setTextColor(0xFFEF4444);
            Toast.makeText(this, "开始录制：帧率 / CPU 负载", Toast.LENGTH_SHORT).show();
            recSample();
        } else {
            stopRec(true);
        }
    }

    private void stopRec(boolean toast) {
        recording = false;
        tvRec.setText("●");
        tvRec.setTextColor(0xFF00E5FF);
        int n = ts.size();
        if (n < 4) {
            if (toast) Toast.makeText(this, "录制时间太短，未保存", Toast.LENGTH_SHORT).show();
            return;
        }
        if (toast) Toast.makeText(this, "录制完成，正在生成曲线图…", Toast.LENGTH_SHORT).show();
        final int fn = n;
        new Thread(() -> saveChart(fn)).start();
    }

    private void recSample() {
        if (!recording) return;
        new Thread(() -> {
            float hz = refreshRate();
            Double busy = recCpuTotal();
            float[] coreBusy = readCores();
            if (!recording) return;
            long now = System.currentTimeMillis();
            if (busy != null && coreBusy != null) {
                ts.add(now - recStart);
                fps.add(hz);
                cpuTot.add(busy.floatValue());
                cores.add(coreBusy);
            }
            long dur = now - recStart;
            ui.post(() -> {
                if (recording) {
                    rowViews[4].setText(String.format(Locale.US, "● %02d:%02d · %.0fHz",
                            dur / 60000, (dur / 1000) % 60, hz));
                }
            });
            ui.postDelayed(this::recSample, 500);
        }).start();
    }

    /** /proc/stat 总行差分（录制专用基准）→ CPU 总占用 % */
    private Double recCpuTotal() {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/stat"))) {
            String l = r.readLine();
            if (l == null || !l.startsWith("cpu ")) return null;
            String[] p = l.split("\\s+");
            long idle = Long.parseLong(p[4]) + Long.parseLong(p[5]);
            long total = 0;
            for (int i = 1; i < p.length; i++) total += Long.parseLong(p[i]);
            if (recLastIdle >= 0 && total > recLastTotal) {
                double busy = 100.0 * (total - recLastTotal - (idle - recLastIdle)) / (total - recLastTotal);
                recLastIdle = idle;
                recLastTotal = total;
                return Math.max(0, Math.min(100, busy));
            }
            recLastIdle = idle;
            recLastTotal = total;
        } catch (Exception ignored) {
        }
        return null;
    }

    /** /proc/stat cpu0..cpuN 差分 → 每核心占用 % */
    private float[] readCores() {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/stat"))) {
            List<long[]> cur = new ArrayList<>();
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.startsWith("cpu") || line.startsWith("cpu ")) continue;
                String[] p = line.split("\\s+");
                long idle = Long.parseLong(p[4]) + Long.parseLong(p[5]);
                long total = 0;
                for (int i = 1; i < p.length; i++) total += Long.parseLong(p[i]);
                cur.add(new long[]{idle, total});
            }
            int n = cur.size();
            coreCount = Math.max(coreCount, n);
            if (n == 0) return null;
            if (lastCoreIdle == null || lastCoreIdle.length != n) {
                lastCoreIdle = new long[n];
                lastCoreTotal = new long[n];
                for (int i = 0; i < n; i++) {
                    lastCoreIdle[i] = cur.get(i)[0];
                    lastCoreTotal[i] = cur.get(i)[1];
                }
                return null;
            }
            float[] out = new float[n];
            for (int i = 0; i < n; i++) {
                long di = cur.get(i)[0] - lastCoreIdle[i];
                long dt = cur.get(i)[1] - lastCoreTotal[i];
                out[i] = dt > 0 ? (float) Math.max(0, Math.min(100, 100.0 * (dt - di) / dt)) : 0;
                lastCoreIdle[i] = cur.get(i)[0];
                lastCoreTotal[i] = cur.get(i)[1];
            }
            return out;
        } catch (Exception ignored) {
        }
        return null;
    }

    // ==================== 曲线图渲染与保存 ====================

    private void saveChart(int n) {
        try {
            Bitmap bmp = renderChart(n);
            String name = "rec_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                    .format(new Date(recStart)) + ".png";
            String[] res = writePng(bmp, name);
            if (res != null) {
                long dur = ts.get(n - 1);
                FrameRecordStore.add(this, recStart, dur, n, coreCount, fps, cpuTot, res[1], name);
                final String msg = "已保存: " + res[0];
                ui.post(() -> Toast.makeText(MonitorService.this, msg, Toast.LENGTH_LONG).show());
            } else {
                ui.post(() -> Toast.makeText(MonitorService.this, "保存失败", Toast.LENGTH_LONG).show());
            }
        } catch (Exception e) {
            ui.post(() -> Toast.makeText(MonitorService.this,
                    "保存失败: " + e, Toast.LENGTH_LONG).show());
        }
    }

    /** @return {展示路径, 读取引用(content uri 或绝对路径)}，失败 null */
    private String[] writePng(Bitmap bmp, String name) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                v.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                v.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ColorFC");
                Uri uri = getContentResolver()
                        .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                if (uri != null) {
                    try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                        bmp.compress(Bitmap.CompressFormat.PNG, 100, os);
                    }
                    return new String[]{"Pictures/ColorFC/" + name, uri.toString()};
                }
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_PICTURES), "ColorFC");
                if (dir.isDirectory() || dir.mkdirs()) {
                    File f = new File(dir, name);
                    try (FileOutputStream fo = new FileOutputStream(f)) {
                        bmp.compress(Bitmap.CompressFormat.PNG, 100, fo);
                    }
                    return new String[]{f.getAbsolutePath(), f.getAbsolutePath()};
                }
            }
        } catch (Exception ignored) {
        }
        // 兜底：应用外部私有目录
        try {
            File dir = new File(getExternalFilesDir(null), "ColorFC");
            if (dir.isDirectory() || dir.mkdirs()) {
                File f = new File(dir, name);
                try (FileOutputStream fo = new FileOutputStream(f)) {
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, fo);
                }
                return new String[]{f.getAbsolutePath(), f.getAbsolutePath()};
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private Bitmap renderChart(int n) {
        int W = 1080, H = 1750;
        Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(0xFF0D1520);

        Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);   // 标题
        tp.setColor(0xFFE6EDF3);
        tp.setTextSize(40);
        tp.setTypeface(Typeface.DEFAULT_BOLD);
        Paint sp = new Paint(Paint.ANTI_ALIAS_FLAG);   // 副文字
        sp.setColor(0xFF8B949E);
        sp.setTextSize(26);
        Paint hp = new Paint(Paint.ANTI_ALIAS_FLAG);   // 小标题
        hp.setColor(0xFF00E5FF);
        hp.setTextSize(30);
        hp.setTypeface(Typeface.DEFAULT_BOLD);

        // ===== 标题区 =====
        long dur = ts.get(n - 1);
        SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        c.drawText("帧率录制报告", 40, 66, tp);
        c.drawText(String.format(Locale.US, "%s ｜ 时长 %d:%02d ｜ 采样 %d 条",
                df.format(new Date(recStart)), dur / 60000, (dur / 1000) % 60, n), 40, 108, sp);

        float x0 = 40, w = W - 80;

        // ===== 图1: 屏幕帧率 =====
        float y1 = 150;
        c.drawText("屏幕帧率 (Hz)", x0, y1 + 34, hp);
        c.drawText(fpsStats(), x0, y1 + 70, sp);
        drawSeries(c, x0, y1 + 90, w, 300,
                new float[][]{toFloat(fps)}, new int[]{0xFF00E5FF},
                niceMax(maxOf(fps, 120)));

        // ===== 图2: CPU 总占用 =====
        float y2 = y1 + 90 + 300 + 70;
        c.drawText("CPU 实时使用率 (%)", x0, y2 + 34, hp);
        c.drawText(cpuStats(), x0, y2 + 70, sp);
        drawSeries(c, x0, y2 + 90, w, 300,
                new float[][]{toFloat(cpuTot)}, new int[]{0xFFF59E0B}, 100);

        // ===== 图3: 各核心负载 =====
        float y3 = y2 + 90 + 300 + 70;
        int cc = Math.min(coreCount, 8);
        c.drawText("CPU 线程负载 (%)", x0, y3 + 34, hp);
        StringBuilder legend = new StringBuilder();
        float[][] series = new float[cc][];
        int[] colors = new int[cc];
        for (int k = 0; k < cc; k++) {
            series[k] = coreSeries(k);
            colors[k] = CORE_COLORS[k % CORE_COLORS.length];
            if (k > 0) legend.append("  ");
            legend.append("C").append(k);
        }
        c.drawText(legend.toString(), x0, y3 + 70, sp);
        drawSeries(c, x0, y3 + 90, w, 420, series, colors, 100);

        return bmp;
    }

    private String fpsStats() {
        float min = Float.MAX_VALUE, max = 0, sum = 0;
        for (float v : fps) {
            if (v > 0) {
                min = Math.min(min, v);
                max = Math.max(max, v);
                sum += v;
            }
        }
        int cnt = fps.size();
        return String.format(Locale.US, "平均 %.1f ｜ 最低 %.0f ｜ 最高 %.0f",
                cnt > 0 ? sum / cnt : 0, min == Float.MAX_VALUE ? 0 : min, max);
    }

    private String cpuStats() {
        float max = 0, sum = 0;
        for (float v : cpuTot) {
            max = Math.max(max, v);
            sum += v;
        }
        int cnt = cpuTot.size();
        return String.format(Locale.US, "平均 %.1f%% ｜ 峰值 %.1f%%",
                cnt > 0 ? sum / cnt : 0, max);
    }

    private float[] toFloat(ArrayList<Float> list) {
        float[] a = new float[list.size()];
        for (int i = 0; i < a.length; i++) a[i] = list.get(i);
        return a;
    }

    private float maxOf(ArrayList<Float> list, float def) {
        float m = 0;
        for (float v : list) m = Math.max(m, v);
        return m > 0 ? m : def;
    }

    private float niceMax(float v) {
        if (v <= 30) return 30;
        if (v <= 60) return 60;
        if (v <= 90) return 90;
        if (v <= 120) return 120;
        if (v <= 144) return 144;
        return (float) (Math.ceil(v / 60.0) * 60);
    }

    /** 取第 k 核心的序列 */
    private float[] coreSeries(int k) {
        float[] a = new float[cores.size()];
        for (int i = 0; i < cores.size(); i++) {
            float[] s = cores.get(i);
            a[i] = k < s.length ? s[k] : 0;
        }
        return a;
    }

    /** 绘制曲线组：网格 + Y轴刻度 + 多条折线 */
    private void drawSeries(Canvas c, float x, float y, float w, float h,
                            float[][] series, int[] colors, float vmax) {
        Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
        grid.setColor(0x1AFFFFFF);
        grid.setStrokeWidth(1);
        Paint axis = new Paint(Paint.ANTI_ALIAS_FLAG);
        axis.setColor(0xFF303D4D);
        axis.setStrokeWidth(2);
        Paint lab = new Paint(Paint.ANTI_ALIAS_FLAG);
        lab.setColor(0xFF6B7785);
        lab.setTextSize(22);

        // 背景板
        Paint panel = new Paint(Paint.ANTI_ALIAS_FLAG);
        panel.setColor(0xFF111C28);
        c.drawRoundRect(x - 16, y - 12, x + w + 16, y + h + 16, 14, 14, panel);

        // 水平网格 + Y 刻度
        for (int i = 0; i <= 4; i++) {
            float gy = y + h * i / 4f;
            c.drawLine(x, gy, x + w, gy, grid);
            c.drawText(String.format(Locale.US, "%.0f", vmax * (4 - i) / 4f),
                    x + 6, gy - 5, lab);
        }
        // 垂直网格
        for (int i = 1; i < 6; i++) {
            float gx = x + w * i / 6f;
            c.drawLine(gx, y, gx, y + h, grid);
        }
        // 边框
        c.drawLine(x, y, x + w, y, axis);
        c.drawLine(x, y + h, x + w, y + h, axis);
        c.drawLine(x, y, x, y + h, axis);
        c.drawLine(x + w, y, x + w, y + h, axis);

        if (series.length == 0 || series[0].length < 2) return;

        // X 轴时间刻度
        long total = ts.get(ts.size() - 1);
        for (int i = 0; i <= 3; i++) {
            long t = total * i / 3;
            String s = t >= 60000 ? String.format(Locale.US, "%dm", t / 60000)
                    : String.format(Locale.US, "%ds", t / 1000);
            float tx = x + w * i / 3f;
            c.drawText(s, Math.min(tx + 4, x + w - 40), y + h + 44, lab);
        }

        // 折线
        for (int k = 0; k < series.length; k++) {
            float[] d = series[k];
            int n = d.length;
            if (n < 2) continue;
            Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
            line.setColor(colors[k]);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(4);
            Path path = new Path();
            for (int i = 0; i < n; i++) {
                float px = x + w * i / (float) (n - 1);
                float py = y + h - (float) (Math.max(0, Math.min(vmax, d[i])) / vmax) * h;
                if (i == 0) path.moveTo(px, py);
                else path.lineTo(px, py);
            }
            c.drawPath(path, line);
        }
    }

    // ==================== 常规解析工具 ====================

    /** /proc/stat 首行差分 → CPU 总占用 %（UI 循环） */
    private double readCpuBusy() {
        try {
            BufferedReader r = new BufferedReader(new FileReader("/proc/stat"));
            String l = r.readLine();
            r.close();
            if (l == null || !l.startsWith("cpu ")) return -1;
            String[] p = l.split("\\s+");
            long idle = Long.parseLong(p[4]) + Long.parseLong(p[5]);
            long total = 0;
            for (int i = 1; i < p.length; i++) total += Long.parseLong(p[i]);
            if (lastIdle >= 0 && total > lastTotal) {
                double busy = 100.0 * (total - lastTotal - (idle - lastIdle)) / (total - lastTotal);
                lastIdle = idle;
                lastTotal = total;
                return Math.max(0, Math.min(100, busy));
            }
            lastIdle = idle;
            lastTotal = total;
        } catch (Exception ignored) {
        }
        return -1;
    }

    /** GPU 频率（Hz）：kgsl gpuclk 直接为 MHz，devfreq 为 Hz */
    private double parseGpu(String out) {
        try {
            for (String line : out.split("\\n")) {
                if (line.startsWith("G:")) {
                    double v = Double.parseDouble(line.substring(2).trim());
                    if (v <= 0) return 0;
                    if (v < 3000) return v * 1e6;     // MHz
                    return v;                          // Hz
                }
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    /** CPU 最大频率（kHz） */
    private double parseFreq(String out) {
        try {
            for (String line : out.split("\\n")) {
                if (line.startsWith("F:")) {
                    return Double.parseDouble(line.substring(2).trim());
                }
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    /**
     * 分类温区最大值（℃）：
     * - cpu: 含 cpu/apc/big 的温区，回退电池温度
     * - soc: 含 soc 的温区，回退 CPU 温度
     */
    private double parseZoneTemp(String out, String kind, double fallback) {
        double max = 0;
        try {
            for (String line : out.split("\\n")) {
                if (!line.startsWith("T:")) continue;
                String body = line.substring(2);
                int idx = body.lastIndexOf(':');
                if (idx <= 0) continue;
                String ty = body.substring(0, idx).toLowerCase();
                double v = Double.parseDouble(body.substring(idx + 1).trim());
                if (v > 1000) v = v / 1000.0;              // 毫摄氏度 → ℃
                if (v <= 0 || v > 120) continue;
                boolean hit = "cpu".equals(kind)
                        ? (ty.contains("cpu") || ty.contains("apc") || ty.contains("big"))
                        : ty.contains("soc");
                if (hit) max = Math.max(max, v);
            }
        } catch (Exception ignored) {
        }
        return Math.max(max, fallback);
    }

    private float refreshRate() {
        try {
            DisplayManager dm = (DisplayManager) getSystemService(DISPLAY_SERVICE);
            return dm.getDisplay(0).getRefreshRate();
        } catch (Exception e) {
            return 0;
        }
    }

    private Notification notif() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel("monitor",
                "迷你监视器", NotificationManager.IMPORTANCE_LOW);
        nm.createNotificationChannel(ch);
        PendingIntent close = PendingIntent.getService(this, 1,
                new Intent(this, MonitorService.class).setAction("stop"),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, "monitor")
                .setContentTitle("迷你监视器运行中")
                .setContentText("长按单项开关 · ● 帧率录制 · 单击详细 · ✕ 关闭")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .addAction(new Notification.Action.Builder(null, "关闭", close).build())
                .setOngoing(true)
                .build();
    }
}
