package Color.fc;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
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
 * 帧率录制迷你悬浮窗（独立于监视器）：
 * - 显示当前屏幕帧率，单击开始录制，再次单击停止并保存
 * - 录制内容：屏幕帧率 + CPU 总占用 + 每核心(线程)负载，500ms 采样
 * - 停止后渲染曲线图 PNG 保存到 Pictures/ColorFC
 */
public class FrameRecService extends Service {

    public static volatile boolean running = false;

    private WindowManager wm;
    private LinearLayout box;
    private TextView tvMain, tvClose;
    private WindowManager.LayoutParams lp;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private boolean recording = false;
    private long recStart;
    private int idleTick;

    // /proc/stat 差分基准（总 + 每核心）
    private long lastIdle = -1, lastTotal = -1;
    private long[] lastCoreIdle, lastCoreTotal;

    // 录制数据
    private final ArrayList<Long> ts = new ArrayList<>();
    private final ArrayList<Float> fps = new ArrayList<>();
    private final ArrayList<Float> cpuTot = new ArrayList<>();
    private final ArrayList<float[]> cores = new ArrayList<>();
    private int coreCount = 0;

    private static final int[] CORE_COLORS = {
            0xFF00E5FF, 0xFF22D3EE, 0xFF10B981, 0xFFF59E0B,
            0xFFEF4444, 0xFF8B5CF6, 0xFFEC4899, 0xFF84CC16
    };

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
        startForeground(2, notif());
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        buildView();
        ui.postDelayed(this::sample, 300);
    }

    @Override
    public void onDestroy() {
        running = false;
        ui.removeCallbacksAndMessages(null);
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
        box.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xD9101820);
        bg.setStroke(1, 0x66EF4444);
        bg.setCornerRadius(dp(16));
        box.setBackground(bg);
        int pad = dp(12);
        box.setPadding(pad, dp(6), dp(8), dp(6));

        tvMain = new TextView(this);
        tvMain.setTextColor(0xFF00E5FF);
        tvMain.setTextSize(12);
        tvMain.setTypeface(Typeface.MONOSPACE);
        tvMain.setText("…Hz ▶");
        box.addView(tvMain, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        tvClose = new TextView(this);
        tvClose.setTextColor(0xFF8B949E);
        tvClose.setTextSize(12);
        tvClose.setText("  ✕");
        // 扩大点击热区
        tvClose.setPadding(dp(6), dp(4), dp(4), dp(4));
        box.addView(tvClose, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = dp(24);
        lp.y = dp(160);

        box.setOnTouchListener(new View.OnTouchListener() {
            float sx, sy, dx, dy;
            long downAt;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        sx = e.getRawX();
                        sy = e.getRawY();
                        dx = sx - lp.x;
                        dy = sy - lp.y;
                        downAt = System.currentTimeMillis();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        lp.x = (int) (e.getRawX() - dx);
                        lp.y = (int) (e.getRawY() - dy);
                        try {
                            wm.updateViewLayout(box, lp);
                        } catch (Exception ignored) {
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (Math.abs(e.getRawX() - sx) < dp(12)
                                && Math.abs(e.getRawY() - sy) < dp(12)
                                && System.currentTimeMillis() - downAt < 350) {
                            // 命中 ✕ → 关闭悬浮窗
                            int[] loc = new int[2];
                            tvClose.getLocationOnScreen(loc);
                            if (e.getRawX() >= loc[0] && e.getRawX() <= loc[0] + tvClose.getWidth()
                                    && e.getRawY() >= loc[1] && e.getRawY() <= loc[1] + tvClose.getHeight()) {
                                stopSelf();
                                return true;
                            }
                            toggleRec();
                        }
                        return true;
                }
                return false;
            }
        });

        wm.addView(box, lp);
    }

    /** 单击切换录制状态 */
    private void toggleRec() {
        if (!recording) {
            recording = true;
            recStart = System.currentTimeMillis();
            ts.clear();
            fps.clear();
            cpuTot.clear();
            cores.clear();
            tvMain.setTextColor(0xFFEF4444);
            tvMain.setText("● REC");
            Toast.makeText(this, "开始录制：帧率 / CPU 负载", Toast.LENGTH_SHORT).show();
        } else {
            recording = false;
            tvMain.setTextColor(0xFF00E5FF);
            final int n = ts.size();
            if (n < 4) {
                Toast.makeText(this, "录制时间太短，未保存", Toast.LENGTH_SHORT).show();
                return;
            }
            Toast.makeText(this, "录制完成，正在生成曲线图…", Toast.LENGTH_SHORT).show();
            new Thread(() -> saveChart(n)).start();
        }
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** 500ms 采样：帧率 + CPU 总占用 + 每核心负载 */
    private void sample() {
        new Thread(() -> {
            float hz = refreshRate();
            Double busy = readCpuTotal();
            float[] coreBusy = readCores();

            long now = System.currentTimeMillis();
            if (recording && busy != null && coreBusy != null) {
                ts.add(now - recStart);
                fps.add(hz);
                cpuTot.add(busy.floatValue());
                cores.add(coreBusy);
            }

            final float fhz = hz;
            final long dur = now - recStart;
            ui.post(() -> {
                if (recording) {
                    tvMain.setText(String.format(Locale.US, "● %02d:%02d %.0fHz",
                            dur / 60000, (dur / 1000) % 60, fhz));
                } else {
                    // 空闲时 2 秒刷新一次帧率
                    if (++idleTick % 2 == 0) {
                        tvMain.setText(String.format(Locale.US, "%.0fHz ▶", fhz));
                    }
                }
            });
            ui.postDelayed(this::sample, 500);
        }).start();
    }

    private float refreshRate() {
        try {
            DisplayManager dm = (DisplayManager) getSystemService(DISPLAY_SERVICE);
            return dm.getDisplay(0).getRefreshRate();
        } catch (Exception e) {
            return 0;
        }
    }

    /** /proc/stat 总行差分 → CPU 总占用 % */
    private Double readCpuTotal() {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/stat"))) {
            String l = r.readLine();
            if (l == null || !l.startsWith("cpu ")) return null;
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
            String where = writePng(bmp, name);
            if (where != null) {
                final String msg = "已保存: " + where;
                ui.post(() -> Toast.makeText(FrameRecService.this, msg, Toast.LENGTH_LONG).show());
            } else {
                ui.post(() -> Toast.makeText(FrameRecService.this, "保存失败", Toast.LENGTH_LONG).show());
            }
        } catch (Exception e) {
            ui.post(() -> Toast.makeText(FrameRecService.this,
                    "保存失败: " + e, Toast.LENGTH_LONG).show());
        }
    }

    private String writePng(Bitmap bmp, String name) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                v.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                v.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ColorFC");
                android.net.Uri uri = getContentResolver()
                        .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                if (uri != null) {
                    try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                        bmp.compress(Bitmap.CompressFormat.PNG, 100, os);
                    }
                    return "Pictures/ColorFC/" + name;
                }
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_PICTURES), "ColorFC");
                if (dir.isDirectory() || dir.mkdirs()) {
                    File f = new File(dir, name);
                    try (FileOutputStream fo = new FileOutputStream(f)) {
                        bmp.compress(Bitmap.CompressFormat.PNG, 100, fo);
                    }
                    return f.getAbsolutePath();
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
                return f.getAbsolutePath();
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
        c.drawText(fpsStats(n), x0, y1 + 70, sp);
        drawSeries(c, x0, y1 + 90, w, 300,
                new float[][]{toFloat(fps)}, new int[]{0xFF00E5FF},
                niceMax(maxOf(fps, 120)), false);

        // ===== 图2: CPU 总占用 =====
        float y2 = y1 + 90 + 300 + 70;
        c.drawText("CPU 实时使用率 (%)", x0, y2 + 34, hp);
        c.drawText(cpuStats(n), x0, y2 + 70, sp);
        drawSeries(c, x0, y2 + 90, w, 300,
                new float[][]{toFloat(cpuTot)}, new int[]{0xFFF59E0B}, 100, false);

        // ===== 图3: 各核心负载 =====
        float y3 = y2 + 90 + 300 + 70;
        int cc = Math.min(coreCount, 8);
        c.drawText("CPU 线程负载 (%)", x0, y3 + 34, hp);
        StringBuilder legend = new StringBuilder();
        float[][] series = new float[cc][];
        int[] colors = new int[cc];
        for (int k = 0; k < cc; k++) {
            series[k] = coreSeries(k, n);
            colors[k] = CORE_COLORS[k % CORE_COLORS.length];
            if (k > 0) legend.append("  ");
            legend.append("C").append(k);
        }
        c.drawText(legend.toString(), x0, y3 + 70, sp);
        drawSeries(c, x0, y3 + 90, w, 420, series, colors, 100, true);

        return bmp;
    }

    private String fpsStats(int n) {
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

    private String cpuStats(int n) {
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
    private float[] coreSeries(int k, int n) {
        float[] a = new float[cores.size()];
        for (int i = 0; i < cores.size(); i++) {
            float[] s = cores.get(i);
            a[i] = k < s.length ? s[k] : 0;
        }
        return a;
    }

    /** 绘制曲线组：网格 + Y轴刻度 + 多条折线 */
    private void drawSeries(Canvas c, float x, float y, float w, float h,
                            float[][] series, int[] colors, float vmax, boolean fill) {
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
        lab.setColor(0xFF6B7785);
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
            line.setStrokeWidth(fill ? 3 : 4);
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

    private Notification notif() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel("framerec",
                "帧率录制", NotificationManager.IMPORTANCE_LOW);
        nm.createNotificationChannel(ch);
        PendingIntent close = PendingIntent.getService(this, 2,
                new Intent(this, FrameRecService.class).setAction("stop"),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, "framerec")
                .setContentTitle("帧率录制悬浮窗")
                .setContentText("单击悬浮窗开始/停止录制")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .addAction(new Notification.Action.Builder(null, "关闭", close).build())
                .setOngoing(true)
                .build();
    }
}
