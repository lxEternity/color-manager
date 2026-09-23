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
 * - 前台服务 + 悬浮窗（CPU 占用/最大频率、GPU 频率、温度、功耗、刷新率）
 * - 拖动移动位置；单击切换 紧凑/详细 两档
 * - 采样：/proc/stat 直读（CPU 占用）+ 一条 su 脚本（GPU/温区/CPU 最大频）+ PowerMonitor（功耗）
 */
public class MonitorService extends Service {

    /** 主页开关状态同步用 */
    public static volatile boolean running = false;

    private WindowManager wm;
    private LinearLayout box;
    private TextView tvPower, tvCpu, tvGpu, tvTemp, tvHz, tvDetail;
    private WindowManager.LayoutParams lp;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean detailed = false;

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
        box.setPadding(pad, dp(8), pad, dp(8));

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
                        // 位移小 + 时间短 → 视为单击，切换 紧凑/详细
                        if (Math.abs(e.getRawX() - sx) < dp(12)
                                && Math.abs(e.getRawY() - sy) < dp(12)
                                && System.currentTimeMillis() - downAt < 350) {
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
            double busy = readCpuBusy();
            double gpuHz = parseGpu(out);
            double cpuMaxK = parseFreq(out);
            double temp = parseTemp(out, st);
            float hz = refreshRate();
            final PowerMonitor.BatteryStat fst = st;
            final double fbusy = busy, fgpu = gpuHz, fcpu = cpuMaxK, ftemp = temp;
            final float fhz = hz;
            ui.post(() -> updateUi(fst, fbusy, fgpu, fcpu, ftemp, fhz));
            ui.postDelayed(this::sample, 2000);
        }).start();
    }

    private void updateUi(PowerMonitor.BatteryStat st, double busy, double gpuHz,
                          double cpuMaxK, double temp, float hz) {
        if (st != null) {
            double w = Math.abs(st.watts);
            tvPower.setText(String.format(Locale.US, "\u26A1 %.2fW", w));
            if (detailed) {
                tvDetail.setText(String.format(Locale.US, "%.2fV %.2fA \u00b7 %d%% %s",
                        st.volts, Math.abs(st.amps), st.level,
                        st.tempC > 0 ? String.format(Locale.US, "\u7535%.0f\u2103", st.tempC) : ""));
            }
        } else {
            tvPower.setText("\u26A1 --W");
        }
        String cpu = "CPU ";
        cpu += busy >= 0 ? String.format(Locale.US, "%.0f%%", busy) : "--";
        if (cpuMaxK > 0) cpu += String.format(Locale.US, " \u00b7 %.2fG", cpuMaxK / 1e6);
        tvCpu.setText(cpu);
        tvGpu.setText(gpuHz > 0 ? String.format(Locale.US, "GPU %.0fM", gpuHz) : "GPU --");
        tvTemp.setText(temp > 0 ? String.format(Locale.US, "\uD83D\uDD25 %.1f\u2103", temp) : "\uD83D\uDD25 --");
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

    /** 温度：cpu/gpu/soc 相关温区最大值，回退电池温度 */
    private double parseTemp(String out, PowerMonitor.BatteryStat st) {
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
                boolean related = ty.contains("cpu") || ty.contains("gpu") || ty.contains("soc")
                        || ty.contains("apc") || ty.contains("ap") || ty.contains("big");
                if (related) max = Math.max(max, v);
            }
        } catch (Exception ignored) {
        }
        if (max > 0) return max;
        return st != null ? st.tempC : 0;
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
                .setContentText("拖动移动 · 单击切换详细模式")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .addAction(new Notification.Action.Builder(null, "关闭", close).build())
                .setOngoing(true)
                .build();
    }
}
