package Color.fc;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.Locale;

/**
 * 迷你悬浮窗监视器：
 * - 前台服务 + 悬浮窗（右上角 ✕ 关闭；功耗/CPU温度/SOC温度 文字标签；CPU 频率 MHz）
 * - 功耗计算与主页同步（电芯模式：自动校准 / 强制单 / 强制双）
 * - 拖动移动位置；单击切换 紧凑/详细 两档
 * - 采样：/proc/stat 直读（CPU 占用）+ 一条 su 脚本（GPU/温区/CPU 最大频）+ PowerMonitor（功耗）
 * - 顺带写入功耗历史记录（1 分钟节流）
 */
public class MonitorService extends Service {

    /** 主页开关状态同步用 */
    public static volatile boolean running = false;

    private WindowManager wm;
    private LinearLayout box;
    private TextView tvClose, tvPower, tvCpu, tvGpu, tvTemp, tvHz, tvDetail;
    private WindowManager.LayoutParams lp;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean detailed = false;
    /** 电芯模式（与主页同步）：0=自动校准 1=强制单电芯 2=强制双电芯 */
    private int cellMode = 0;

    // CPU 占用差分基准
    private long lastIdle = -1, lastTotal = -1;

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
        // 与主页共享电芯模式，保证功耗显示一致
        cellMode = getSharedPreferences("colorfc", MODE_PRIVATE).getInt("cellMode", 0);
        startForeground(1, notif());
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
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xD9101820);
        bg.setStroke(1, 0x6600E5FF);
        bg.setCornerRadius(dp(10));
        box.setBackground(bg);
        int pad = dp(10);
        box.setPadding(pad, dp(4), pad, dp(8));

        // 顶部关闭按钮行（右对齐 ✕）
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        View spring = new View(this);
        head.addView(spring, new LinearLayout.LayoutParams(
                0, 1, 1f));
        tvClose = new TextView(this);
        tvClose.setTextColor(0xFF8B949E);
        tvClose.setTextSize(11);
        tvClose.setText("✕");
        tvClose.setPadding(dp(6), dp(2), dp(2), dp(2));
        head.addView(tvClose, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        box.addView(head);

        tvPower = row(0xFF00E5FF, 13);
        tvCpu = row(0xFFE6EDF3, 11);
        tvGpu = row(0xFFE6EDF3, 11);
        tvTemp = row(0xFFE6EDF3, 11);
        tvHz = row(0xFF8B949E, 11);
        tvDetail = row(0xFF8B949E, 11);
        tvDetail.setVisibility(View.GONE);

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
                        // 位移小 + 时间短 → 视为单击
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
                            detailed = !detailed;
                            tvDetail.setVisibility(detailed ? View.VISIBLE : View.GONE);
                        }
                        return true;
                }
                return false;
            }
        });

        wm.addView(box, lp);
    }

    private TextView row(int color, float sizeSp) {
        TextView tv = new TextView(this);
        tv.setTextColor(color);
        tv.setTextSize(sizeSp);
        tv.setTypeface(Typeface.MONOSPACE);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        p.bottomMargin = dp(2);
        box.addView(tv, p);
        return tv;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

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
            tvPower.setText(String.format(Locale.US, "功耗 %.2fW", w));
            if (detailed) {
                tvDetail.setText(String.format(Locale.US, "%.2fV %.2fA · %d%% %s",
                        st.volts, Math.abs(amps), st.level,
                        st.tempC > 0 ? String.format(Locale.US, "电%.0f℃", st.tempC) : ""));
            }
        } else {
            tvPower.setText("功耗 --W");
        }
        String cpu = "CPU ";
        cpu += busy >= 0 ? String.format(Locale.US, "%.0f%%", busy) : "--";
        if (cpuMaxK > 0) cpu += String.format(Locale.US, " · %.0fMHz", cpuMaxK / 1000);
        tvCpu.setText(cpu);
        tvGpu.setText(gpuHz > 0 ? String.format(Locale.US, "GPU %.0fMHz", gpuHz / 1e6) : "GPU --");
        tvTemp.setText(String.format(Locale.US, "CPU温度 %.1f℃ SOC温度 %.1f℃", cpuT, socT));
        tvHz.setText(hz > 0 ? String.format(Locale.US, "%.0fHz", hz) : "--Hz");
    }

    /** /proc/stat 首行差分 → CPU 总占用 % */
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
     * - soc: 含 soc 的温区，回退电池温度，再回退 CPU 温度
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
                .setContentText("拖动移动 · 单击切换详细模式 · ✕ 关闭")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .addAction(new Notification.Action.Builder(null, "关闭", close).build())
                .setOngoing(true)
                .build();
    }
}
