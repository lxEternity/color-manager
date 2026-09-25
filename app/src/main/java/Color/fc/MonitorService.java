package Color.fc;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.os.Vibrator;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 悬浮窗管理器（1:1 照搬 Kin-app FloatWindowService/OverlayWindows 架构，仿 Metric）：
 * - 单前台服务同时管理多个独立监视悬浮窗，各自开关互不影响：
 *   负载监视器（三环胶囊，点击展开参数网格）/ 进程监视器（top 进程列表，双击结束）
 *   迷你监视器（顶部居中细条，点击穿透）/ 温度监视器（BAT/CPU/GPU/DDR 四行）
 *   帧率记录器与源码一致暂时下线（不在选择列表显示）
 * - 窗口全部可拖动（迷你条除外，其点击穿透）；移动阈值 3dp 内抬手 = 单击，
 *   500ms 未动 = 长按（负载/温度窗关闭）；无吸边/无落点记忆（源码行为）
 * - 采样：CPU /proc/stat 差分（免 root）、GPU/集群/温度 2s 扫描、FPS SF 增量差分、
 *   功耗 PowerMonitor（与主页电芯模式同步）、进程 toybox top（回退 /proc 差分）
 * - 服务生命周期：全部窗口关闭 → 自动停止；被杀后 START_STICKY 按 Prefs 恢复
 */
public class MonitorService extends Service {

    /** 主页按钮状态同步用 */
    public static volatile boolean running = false;

    // ===== 窗口类型（顺序 = 功能列表顺序） =====
    public static final int T_LOAD = 0, T_PROCESS = 1, T_MINI = 2, T_TEMP = 3, N_TYPES = 4;
    /** 持久化开关键（照搬 Kin Prefs ovl_*，悬浮窗管理列表与主页共用） */
    public static final String[] PREF_KEYS = {"ovl_load", "ovl_process", "ovl_mini", "ovl_temp"};

    private WindowManager wm;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private float dp;
    private int dp(float v) {
        return Math.round(v * dp);
    }

    // ===== 各窗口持有 =====
    private final View[] win = new View[N_TYPES];
    private final boolean[] winOpen = new boolean[N_TYPES];
    private OverlayWindow[] wins = new OverlayWindow[N_TYPES];

    // ===== 采样状态 =====
    /** 电芯模式（与主页同步）：0=自动校准 1=强制单电芯 2=强制双电芯 */
    private int cellMode = 0;
    private long lastIdle = -1, lastTotal = -1;
    private long[] lastCoreIdle, lastCoreTotal;
    private int coreCount = 0;
    private float peakHz = 0;
    private String fpsLayer, fpsPkg;
    private long sfPrevNewest = -1;
    private long gfxFrames = -1, gfxAt;
    private float lastGoodHz = 0;
    private long lastGoodAt = 0;
    private int tick = 0;

    /** 上次快照（窗口懒更新用） */
    private Snap lastSnap;

    // ===== 进程采样（top 主路径 + /proc 差分回退，照搬 Kin ProcSampler） =====
    private boolean topUsable = true;
    private long lastTotalJiffies = -1;
    private final Map<Integer, Long> lastProcJiffies = new HashMap<>();

    // ==================== 生命周期 ====================

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        String action = intent != null ? intent.getAction() : null;
        int type = typeOf(intent != null ? intent.getStringExtra("type") : null);
        if ("show".equals(action) && type >= 0) {
            showWindow(type);
        } else if ("hide".equals(action) && type >= 0) {
            hideWindow(type);
        } else {
            syncFromPrefs();   // 服务重建（START_STICKY，intent 为空）按 Prefs 恢复
        }
        return START_STICKY;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        dp = getResources().getDisplayMetrics().density;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        cellMode = getSharedPreferences("colorfc", MODE_PRIVATE).getInt("cellMode", 0);
        try {
            float pk = 0;
            android.hardware.display.DisplayManager dm =
                    (android.hardware.display.DisplayManager) getSystemService(DISPLAY_SERVICE);
            for (android.view.Display d : dm.getDisplays()) {
                for (android.view.Display.Mode m : d.getSupportedModes()) {
                    pk = Math.max(pk, m.getRefreshRate());
                }
            }
            if (pk > 30) peakHz = pk;
        } catch (Exception ignored) {
        }
        startForeground(0x6CB1, notif(0));
        ui.postDelayed(this::sampleLoop, 300);
    }

    @Override
    public void onDestroy() {
        running = false;
        ui.removeCallbacksAndMessages(null);
        for (int i = 0; i < N_TYPES; i++) {
            if (win[i] != null && win[i].getParent() != null) {
                try {
                    wm.removeView(win[i]);
                } catch (Exception ignored) {
                }
            }
            win[i] = null;
            wins[i] = null;
            winOpen[i] = false;
        }
        super.onDestroy();
    }

    static int typeOf(String id) {
        if (id == null) return -1;
        switch (id) {
            case "load": return T_LOAD;
            case "process": return T_PROCESS;
            case "mini": return T_MINI;
            case "temp": return T_TEMP;
        }
        return -1;
    }

    // ==================== 窗口工厂 / 显隐 ====================

    private OverlayWindow factory(int type) {
        OverlayWindow w;
        switch (type) {
            case T_LOAD: w = new LoadOverlayWindow(0xFF0FA5A5); break;
            case T_PROCESS: w = new ProcessOverlayWindow(0xFF34A853); break;
            case T_MINI: w = new MiniOverlayWindow(0xFFFF8A34); break;
            default: w = new TempOverlayWindow(0xFFF97316); break;
        }
        final int t = type;
        w.onCloseRequest = () -> requestClose(t);
        return w;
    }

    /** 窗内关闭（✕ / 长按）：持久化关闭并隐藏（防 START_STICKY 重建复活，照搬 Kin） */
    private void requestClose(int type) {
        getSharedPreferences("colorfc", MODE_PRIVATE).edit()
                .putBoolean(PREF_KEYS[type], false).apply();
        hideWindow(type);
    }

    /** Metric 各窗固定初始落点(dp)：负载/进程 (16,96)，温度 (16,168)，迷你顶部居中 */
    private int[] initialPos(int type) {
        if (type == T_TEMP) return new int[]{dp(16), dp(168)};
        return new int[]{dp(16), dp(96)};
    }

    private WindowManager.LayoutParams buildLp(int type) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT);
        if (type == T_MINI) {
            // Metric 迷你条：+NOT_TOUCHABLE +LAYOUT_NO_LIMITS，高 12dp 顶部居中 y=0
            lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
            lp.height = dp(12);
            lp.width = WindowManager.LayoutParams.WRAP_CONTENT;
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            lp.x = 0;
            lp.y = 0;
            if (Build.VERSION.SDK_INT >= 28) {
                lp.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            }
        } else {
            lp.gravity = Gravity.TOP | Gravity.START;
            int[] p = initialPos(type);
            lp.x = p[0];
            lp.y = p[1];
        }
        return lp;
    }

    private void showWindow(int type) {
        if (winOpen[type]) return;
        OverlayWindow w = factory(type);
        View v = w.getRootView();
        WindowManager.LayoutParams lp = buildLp(type);
        try {
            wm.addView(v, lp);
        } catch (Exception e) {
            if (noneOpen()) stopSelf();
            return;
        }
        win[type] = v;
        wins[type] = w;
        winOpen[type] = true;
        if (type != T_MINI) attachDrag(type, w, v);
        if (lastSnap != null) w.onTick(new TickData(lastSnap, null));
        updateNotif();
    }

    private void hideWindow(int type) {
        if (!winOpen[type]) {
            if (noneOpen()) stopSelf();
            return;
        }
        winOpen[type] = false;
        try {
            wm.removeView(win[type]);
        } catch (Exception ignored) {
        }
        win[type] = null;
        wins[type] = null;
        if (noneOpen()) stopSelf();
        else updateNotif();
    }

    private boolean noneOpen() {
        for (boolean b : winOpen) if (b) return false;
        return true;
    }

    /** 服务重建：按 Prefs 恢复各窗口 */
    private void syncFromPrefs() {
        SharedPreferences p = getSharedPreferences("colorfc", MODE_PRIVATE);
        for (int i = 0; i < N_TYPES; i++) {
            if (p.getBoolean(PREF_KEYS[i], false)) showWindow(i);
        }
        if (noneOpen()) stopSelf();
    }

    // ==================== 拖动 + 单击/长按分发（照搬 Kin：阈值 3dp / 长按 500ms） ====================

    private void attachDrag(int type, OverlayWindow w, View v) {
        final float[] down = new float[2];
        final int[] start = new int[2];
        final boolean[] moved = new boolean[1];
        final boolean[] longFired = new boolean[1];
        final Runnable longRun = () -> {
            if (!moved[0]) {
                longFired[0] = true;
                try {
                    w.onLongPress();
                } catch (Exception ignored) {
                }
            }
        };
        v.setOnTouchListener((view, ev) -> {
            WindowManager.LayoutParams lp = (WindowManager.LayoutParams) view.getLayoutParams();
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down[0] = ev.getRawX();
                    down[1] = ev.getRawY();
                    start[0] = lp.x;
                    start[1] = lp.y;
                    moved[0] = false;
                    longFired[0] = false;
                    view.postDelayed(longRun, 500);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int dx = (int) (ev.getRawX() - down[0]);
                    int dy = (int) (ev.getRawY() - down[1]);
                    if (!moved[0] && (Math.abs(dx) > dp(3) || Math.abs(dy) > dp(3))) {
                        moved[0] = true;
                        view.removeCallbacks(longRun);
                    }
                    lp.x = start[0] + dx;
                    lp.y = start[1] + dy;
                    try {
                        wm.updateViewLayout(view, lp);
                    } catch (Exception ignored) {
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    view.removeCallbacks(longRun);
                    if (!moved[0] && !longFired[0]) {
                        try {
                            w.onTap();
                        } catch (Exception ignored) {
                        }
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    view.removeCallbacks(longRun);
                    return true;
            }
            return false;
        });
    }

    // ==================== 采样循环（1s 一拍，照搬 Kin sampleLoop） ====================

    private void sampleLoop() {
        final boolean needProc = tick % 2 == 0 && winOpen[T_PROCESS];
        new Thread(() -> {
            final Snap s = new Snap();
            try {
                readCpu(s);                       // /proc/stat 差分（总 + 每核）
            } catch (Exception ignored) {
            }
            try {
                scanSlow(s);                      // GPU/集群/温度（shell，2s 节流）
            } catch (Exception ignored) {
            }
            if (winOpen[T_LOAD] || winOpen[T_MINI]) {
                try {
                    s.fps = currentFps();
                } catch (Exception ignored) {
                }
            }
            if (winOpen[T_LOAD] || winOpen[T_MINI] || winOpen[T_TEMP]) {
                try {
                    readPower(s);                 // 功耗 + 电池温度/电量
                } catch (Exception ignored) {
                }
            }
            final List<Proc> procs = needProc ? sampleProcs() : null;
            final TickData d = new TickData(s, procs);
            lastSnap = s;
            ui.post(() -> {
                for (int i = 0; i < N_TYPES; i++) {
                    if (winOpen[i] && wins[i] != null) {
                        try {
                            wins[i].onTick(d);
                        } catch (Exception ignored) {
                        }
                    }
                }
            });
            tick++;
            ui.postDelayed(this::sampleLoop, 1000);
        }).start();
    }

    // ==================== 快照数据 ====================

    static class Cluster {
        int mhz;
        int[] cpus;
    }

    static class Snap {
        int cpuLoadPct = -1;
        int[] coreLoadPct;
        int[] coreMhz;
        List<Cluster> clusters = Collections.emptyList();
        int gpuLoadPct = -1, gpuMhz = -1, ddrMhz = -1;
        float ramUsedPct = -1;
        double cpuTempC = -999, gpuTempC = -999, ddrTempC = -999, battTempC = -999;
        float fps = -1, watt = 0, battPct = -1;
    }

    static class Proc {
        int pid;
        String name;
        float cpuPct;
    }

    static class TickData {
        final Snap snap;
        final List<Proc> procs;

        TickData(Snap s, List<Proc> p) {
            snap = s;
            procs = p;
        }
    }

    // ==================== CPU 占用（/proc/stat 差分，免 root 优先，被 SELinux 挡时 root 兜底） ====================

    /** 整读 /proc/stat 文本：Java 直读失败（Android 10+ 部分系统 SELinux 拦普通应用）
     *  时回退 root cat（照搬 Kin MetricReader.procStatText） */
    private String procStatText() {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/stat"))) {
            StringBuilder sb = new StringBuilder();
            String l;
            while ((l = r.readLine()) != null) {
                sb.append(l).append('\n');
                if (l.startsWith("cpu ") && sb.length() > 4096) break;
            }
            if (sb.length() > 0 && sb.charAt(0) == 'c') return sb.toString();
        } catch (Exception ignored) {
        }
        try {
            RootShell.Result r = RootShell.exec("cat /proc/stat 2>/dev/null", 6);
            if (r.ok() && r.out != null && r.out.startsWith("cpu")) return r.out;
        } catch (Exception ignored) {
        }
        return null;
    }

    private void readCpu(Snap s) {
        String text = procStatText();
        if (text == null) return;
        String[] lines = text.split("\n");
        int ln = 0;
        if (ln < lines.length && lines[ln].startsWith("cpu ")) {
            String[] p = lines[ln].split("\\s+");
            long idle = Long.parseLong(p[4]) + Long.parseLong(p[5]);
            long total = 0;
            for (int i = 1; i < p.length; i++) total += Long.parseLong(p[i]);
            if (lastIdle >= 0 && total > lastTotal) {
                s.cpuLoadPct = (int) Math.max(0, Math.min(100,
                        100.0 * (total - lastTotal - (idle - lastIdle)) / (total - lastTotal)));
            }
            lastIdle = idle;
            lastTotal = total;
            ln++;
        }
        List<long[]> cur = new ArrayList<>();
        for (; ln < lines.length; ln++) {
            String line = lines[ln];
            if (!line.startsWith("cpu") || line.startsWith("cpu ")) continue;
            String[] p = line.split("\\s+");
            if (p.length < 5) continue;
            try {
                long idle = Long.parseLong(p[4]) + Long.parseLong(p[5]);
                long total = 0;
                for (int i = 1; i < p.length; i++) total += Long.parseLong(p[i]);
                cur.add(new long[]{idle, total});
            } catch (Exception ignored) {
            }
        }
        int n = cur.size();
        coreCount = Math.max(coreCount, n);
        if (n > 0) {
            if (lastCoreIdle == null || lastCoreIdle.length != n) {
                lastCoreIdle = new long[n];
                lastCoreTotal = new long[n];
                for (int i = 0; i < n; i++) {
                    lastCoreIdle[i] = cur.get(i)[0];
                    lastCoreTotal[i] = cur.get(i)[1];
                }
            } else {
                int[] out = new int[n];
                for (int i = 0; i < n; i++) {
                    long di = cur.get(i)[0] - lastCoreIdle[i];
                    long dt = cur.get(i)[1] - lastCoreTotal[i];
                    out[i] = dt > 0 ? (int) Math.max(0, Math.min(100, 100.0 * (dt - di) / dt)) : 0;
                    lastCoreIdle[i] = cur.get(i)[0];
                    lastCoreTotal[i] = cur.get(i)[1];
                }
                s.coreLoadPct = out;
            }
        }
    }

    // ==================== 慢速扫描（GPU/集群/温度/RAM/DDR，shell 合并单次调用） ====================

    private static final String SCAN =
            "g=$(cat /sys/class/kgsl/kgsl-3d0/gpuclk 2>/dev/null);"
                    + "[ -n \"$g\" ] || g=$(cat /sys/class/kgsl/kgsl-3d0/devfreq/cur_freq 2>/dev/null);"
                    + "[ -n \"$g\" ] || g=$(cat /sys/class/devfreq/*qcom,gpu*/cur_freq 2>/dev/null);"
                    + "[ -n \"$g\" ] || g=$(cat /sys/class/devfreq/*kgsl*/cur_freq 2>/dev/null);"
                    + "[ -n \"$g\" ] || g=$(cat /sys/class/devfreq/*gpu*/cur_freq 2>/dev/null);"
                    + "echo \"G:$g\";"
                    + "gl=$(cat /sys/class/kgsl/kgsl-3d0/gpu_busy_percentage 2>/dev/null);"
                    + "[ -n \"$gl\" ] || gl=$(cat /sys/class/kgsl/kgsl-3d0/devfreq/gpu_load 2>/dev/null);"
                    + "[ -n \"$gl\" ] || gl=$(cat /sys/class/kgsl/kgsl-3d0/gpuload 2>/dev/null);"
                    + "echo \"GL:$gl\";"
                    + "d=$(cat /sys/class/devfreq/*ddr*/cur_freq 2>/dev/null);"
                    + "[ -n \"$d\" ] || d=$(cat /sys/class/devfreq/*dvfsrc*/cur_freq 2>/dev/null);"
                    + "[ -n \"$d\" ] || d=$(cat /sys/class/devfreq/*bimc*/cur_freq 2>/dev/null);"
                    + "[ -n \"$d\" ] || d=$(cat /sys/class/devfreq/*qcom,mem*/cur_freq 2>/dev/null);"
                    + "echo \"D:$d\";"
                    + "for q in /sys/devices/system/cpu/cpufreq/policy*; do "
                    + "rc=$(cat $q/related_cpus 2>/dev/null);"
                    + "[ -n \"$rc\" ] || rc=$(cat $q/affected_cpus 2>/dev/null);"
                    + "echo \"Q:$(cat $q/scaling_cur_freq 2>/dev/null):$rc\"; done;"
                    + "for z in /sys/class/thermal/thermal_zone*; do "
                    + "[ -f \"$z/temp\" ] || continue; "
                    + "echo \"T:$(cat \"$z/type\" 2>/dev/null):$(cat \"$z/temp\" 2>/dev/null)\"; done";

    private volatile long lastScanAt = 0;
    private volatile String scanCache = "";
    /** 每核频率缓存（cluster 折算到核） */
    private volatile int[] cachedCoreMhz;
    private volatile List<Cluster> cachedClusters;
    private volatile int cachedGpuMhz = -1, cachedGpuLoad = -1, cachedDdrMhz = -1;
    private volatile double cachedCpuT = -999, cachedGpuT = -999, cachedDdrT = -999;

    private void scanSlow(Snap s) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastScanAt < 1900) {
            // 复用上一轮结果（2s 扫描，1s 一拍）
            s.gpuMhz = cachedGpuMhz;
            s.gpuLoadPct = cachedGpuLoad;
            s.ddrMhz = cachedDdrMhz;
            s.clusters = cachedClusters != null ? cachedClusters : Collections.emptyList();
            s.coreMhz = cachedCoreMhz;
            s.cpuTempC = cachedCpuT;
            s.gpuTempC = cachedGpuT;
            s.ddrTempC = cachedDdrT;
            s.ramUsedPct = lastRamPct;
            return;
        }
        lastScanAt = now;
        String out = "";
        try {
            RootShell.Result r = RootShell.exec(SCAN);
            if (r.ok() && r.out != null) out = r.out;
        } catch (Exception ignored) {
        }
        List<Cluster> cls = new ArrayList<>();
        List<String> freqs = new ArrayList<>();
        for (String line : out.split("\\n")) {
            if (line.startsWith("G:")) {
                cachedGpuMhz = (int) Math.round(parseGpuHzToMhz(line.substring(2).trim()));
            } else if (line.startsWith("GL:")) {
                cachedGpuLoad = parseGpuLoad(line);
            } else if (line.startsWith("D:")) {
                try {
                    double v = Double.parseDouble(line.substring(2).trim());
                    // 按量级判单位（照搬 Kin readDdrMhz）：>10M=Hz，>10K=kHz，否则已是 MHz
                    if (v > 10_000_000) cachedDdrMhz = (int) Math.round(v / 1e6);
                    else if (v > 10_000) cachedDdrMhz = (int) Math.round(v / 1000);
                    else if (v > 0) cachedDdrMhz = (int) v;
                } catch (Exception ignored) {
                }
            } else if (line.startsWith("Q:")) {
                freqs.add(line.substring(2));
            }
        }
        // Q:freq:related_cpus
        for (String q : freqs) {
            int c = q.lastIndexOf(':');
            if (c <= 0) continue;
            try {
                int mhz = (int) Math.round(Double.parseDouble(q.substring(0, c).trim()) / 1000.0);
                String[] cs = q.substring(c + 1).trim().split("\\s+");
                List<Integer> cpus = new ArrayList<>();
                for (String x : cs) {
                    try {
                        cpus.add(Integer.parseInt(x.trim()));
                    } catch (Exception ignored) {
                    }
                }
                if (!cpus.isEmpty()) {
                    Cluster cl = new Cluster();
                    cl.mhz = mhz;
                    cl.cpus = new int[cpus.size()];
                    for (int i = 0; i < cpus.size(); i++) cl.cpus[i] = cpus.get(i);
                    cls.add(cl);
                }
            } catch (Exception ignored) {
            }
        }
        cachedClusters = cls;
        // 每核频率 = 所在集群频率
        if (!cls.isEmpty()) {
            int maxCpu = 0;
            for (Cluster cl : cls) for (int c : cl.cpus) maxCpu = Math.max(maxCpu, c);
            int[] cm = new int[maxCpu + 1];
            for (Cluster cl : cls) for (int c : cl.cpus) cm[c] = cl.mhz;
            cachedCoreMhz = cm;
        }
        cachedCpuT = parseZoneTemp(out, "cpu", -999);
        cachedGpuT = parseZoneTemp(out, "gpu", -999);
        cachedDdrT = parseZoneTemp(out, "ddr", -999);
        readMem();
        s.gpuMhz = cachedGpuMhz;
        s.gpuLoadPct = cachedGpuLoad;
        s.ddrMhz = cachedDdrMhz;
        s.clusters = cls;
        s.coreMhz = cachedCoreMhz;
        s.cpuTempC = cachedCpuT;
        s.gpuTempC = cachedGpuT;
        s.ddrTempC = cachedDdrT;
        s.ramUsedPct = lastRamPct;
    }

    private volatile float lastRamPct = -1;

    private void readMem() {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/meminfo"))) {
            long total = -1, avail = -1;
            String l;
            while ((l = r.readLine()) != null) {
                if (total < 0 && l.startsWith("MemTotal:")) {
                    total = Long.parseLong(l.split("\\s+")[1]);
                } else if (avail < 0 && l.startsWith("MemAvailable:")) {
                    avail = Long.parseLong(l.split("\\s+")[1]);
                } else if (total >= 0 && avail >= 0) break;
            }
            if (total > 0 && avail >= 0) lastRamPct = 100f * (total - avail) / total;
        } catch (Exception ignored) {
        }
    }

    /** GPU 频率：kgsl gpuclk 直接为 MHz，devfreq 为 Hz */
    private double parseGpuHzToMhz(String v) {
        try {
            double d = Double.parseDouble(v);
            if (d <= 0) return 0;
            if (d < 3000) return d;    // MHz
            if (d < 1_000_000) return d / 1000;
            return d / 1e6;
        } catch (Exception ignored) {
        }
        return 0;
    }

    /** GPU 负载 %（>100 视作十分位百分比） */
    private int parseGpuLoad(String line) {
        try {
            String s = line.substring(3).trim().replace("%", "");
            s = s.replaceAll("[^0-9.].*$", "").trim();
            if (s.isEmpty()) return -1;
            float v = Float.parseFloat(s);
            if (v > 100 && v <= 1000) v /= 10f;
            return (int) Math.max(0, Math.min(100, v));
        } catch (Exception ignored) {
        }
        return -1;
    }

    /** 分类温区最大值（℃）：cpu=cpu/apc/big / gpu=gpu / ddr=ddr */
    private double parseZoneTemp(String out, String kind, double fallback) {
        double max = 0;
        try {
            for (String line : out.split("\\n")) {
                if (!line.startsWith("T:")) continue;
                String body = line.substring(2);
                int idx = body.lastIndexOf(':');
                if (idx <= 0) continue;
                String ty = body.substring(0, idx).toLowerCase();
                double v;
                try {
                    v = Double.parseDouble(body.substring(idx + 1).trim());
                } catch (Exception e) {
                    continue;
                }
                if (v > 1000) v = v / 1000.0;
                if (v <= 0 || v > 120) continue;
                boolean hit = false;
                switch (kind) {
                    case "cpu": hit = ty.contains("cpu") || ty.contains("apc") || ty.contains("big"); break;
                    case "gpu": hit = ty.contains("gpu"); break;
                    case "ddr": hit = ty.contains("ddr"); break;
                }
                if (hit) max = Math.max(max, v);
            }
        } catch (Exception ignored) {
        }
        return max > 0 ? max : fallback;
    }

    // ==================== 功耗 + 电池（与主页电芯模式同步） ====================

    private void readPower(Snap s) {
        try {
            PowerMonitor.BatteryStat st = PowerMonitor.readOnce();
            if (st != null) {
                double w = PowerMonitor.applyCellMode(st, cellMode);
                if (w != 0 && !Double.isInfinite(w)) s.watt = (float) w;
                if (st.tempC > 0) s.battTempC = st.tempC;
            }
        } catch (Exception ignored) {
        }
        try {
            Intent b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (b != null) {
                int lvl = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int sc = b.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                if (lvl >= 0 && sc > 0) s.battPct = 100f * lvl / sc;
                int t = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
                if (t > 0) s.battTempC = t / 10f;
            }
        } catch (Exception ignored) {
        }
    }

    // ==================== 实时帧率（SF latency 增量差分，照搬旧实现） ====================

    private static final String FPS_CMD =
            "f=$(dumpsys window 2>/dev/null | grep -m1 -E 'mCurrentFocus|mFocusedApp');"
                    + "p=$(echo \"$f\" | grep -oE '[A-Za-z0-9_.]+/' | head -1); p=${p%/};"
                    + "echo \"P:$p\";"
                    + "if [ -n \"$p\" ]; then"
                    + " dumpsys gfxinfo \"$p\" 2>/dev/null | grep -m1 'Total frames rendered'"
                    + " | grep -oE '[0-9]+' | tail -1;"
                    + " ll=$(dumpsys SurfaceFlinger --list 2>/dev/null | grep -F \"$p\""
                    + " | grep -viE 'StatusBar|NavigationBar|ColorFade|ScreenDecor|Wallpaper|Sprite|Cursor|Ink|Dim|Toast|InputMethod|SplashScreen|saveLayer|ripple|Screenshot|Effect|Blur');"
                    + " l=$(echo \"$ll\" | grep -m1 'SurfaceView');"
                    + " [ -n \"$l\" ] || l=$(echo \"$ll\" | head -1);"
                    + " echo \"L:$l\";"
                    + " [ -n \"$l\" ] && dumpsys SurfaceFlinger --latency \"$l\" 2>/dev/null;"
                    + "fi";

    private float currentFps() {
        Float f = realFps();
        if (f != null && f > 0) {
            lastGoodHz = f;
            lastGoodAt = SystemClock.elapsedRealtime();
            return f;
        }
        return lastGoodAt > 0 ? lastGoodHz : 0f;
    }

    /** 增量差分帧率：SF --latency 新增帧 ÷ 跨越时长；无数据回退 gfxinfo 总帧差分 */
    private Float realFps() {
        try {
            RootShell.Result r = RootShell.exec(FPS_CMD, 8);
            if (!r.ok() || r.out == null) return null;
            String pkg = null, layer = null;
            long gfx = -1, newest = 0, lastTs = 0;
            int fresh = 0;
            boolean afterL = false;
            for (String l : r.out.split("\\n")) {
                String t = l.trim();
                if (t.startsWith("P:")) {
                    pkg = t.substring(2);
                    continue;
                }
                if (t.startsWith("L:")) {
                    layer = t.substring(2);
                    afterL = true;
                    continue;
                }
                if (t.isEmpty()) continue;
                if (!afterL) {
                    long v = parseNs(t);
                    if (v >= 0) gfx = v;
                    continue;
                }
                String[] col = t.split("\\s+");
                long ts = col.length >= 2 ? parseNs(col[1]) : parseNs(col[0]);
                if (ts < 1_000_000_000L || ts >= Long.MAX_VALUE / 2) continue;
                if (ts > newest) newest = ts;
                if (sfPrevNewest > 0 && ts > sfPrevNewest && ts != lastTs) {
                    fresh++;
                    lastTs = ts;
                }
            }
            if (pkg != null && pkg.isEmpty()) pkg = null;
            if (layer != null && layer.isEmpty()) layer = null;
            boolean changed = !eq(layer, fpsLayer) || !eq(pkg, fpsPkg);
            if (changed) {
                sfPrevNewest = -1;
                gfxFrames = -1;
            }
            fpsLayer = layer;
            fpsPkg = pkg;
            if (layer != null && newest > 0) {
                if (sfPrevNewest <= 0) {
                    sfPrevNewest = newest;
                } else if (newest <= sfPrevNewest) {
                    return 0f;
                } else {
                    double span = (newest - sfPrevNewest) / 1e9;
                    sfPrevNewest = newest;
                    if (span < 0.005 || span > 120) return 0f;
                    return Math.max(1f, Math.min(peakHz > 0 ? peakHz : 240f, (float) (fresh / span)));
                }
            }
            if (pkg != null && gfx >= 0) {
                long now = SystemClock.uptimeMillis();
                if (gfxFrames >= 0 && gfx >= gfxFrames) {
                    float f = 1000f * (gfx - gfxFrames) / Math.max(1, now - gfxAt);
                    gfxFrames = gfx;
                    gfxAt = now;
                    return Math.max(0f, Math.min(peakHz > 0 ? peakHz : 240f, f));
                }
                gfxFrames = gfx;
                gfxAt = now;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static long parseNs(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    // ==================== 进程采样（照搬 Kin ProcSampler：top 主路径 + /proc 差分回退） ====================

    private List<Proc> sampleProcs() {
        // top 一次性失败（如偶发超时）后每 20 拍自愈重试，避免永久降级到慢速 /proc 差分
        if (!topUsable && tick % 20 == 0) topUsable = true;
        if (topUsable) {
            try {
                RootShell.Result r = RootShell.exec(
                        "top -b -n 1 -o PID,%CPU,RES,CMDLINE -s 2 2>/dev/null | head -n 400", 10);
                String out = r.ok() && r.out != null ? r.out : "";
                List<Proc> rows = new ArrayList<>();
                for (String line : out.split("\\n")) {
                    String[] parts = line.trim().split("\\s+", 4);
                    if (parts.length < 4) continue;
                    int pid;
                    float cpu;
                    try {
                        pid = Integer.parseInt(parts[0]);
                        cpu = Float.parseFloat(parts[1]);
                    } catch (Exception e) {
                        continue;
                    }
                    rows.add(mkProc(pid, parts[3], cpu));
                }
                if (rows.size() >= 3) {
                    rows.sort((a, b) -> Float.compare(b.cpuPct, a.cpuPct));
                    return rows.size() > 48 ? rows.subList(0, 48) : rows;
                }
                topUsable = false;
            } catch (Exception ignored) {
            }
        }
        return procFallback();
    }

    private static Proc mkProc(int pid, String cmd, float cpu) {
        String first = cmd.trim().split(" ")[0];
        int sp = first.lastIndexOf('/');
        String b = sp >= 0 ? first.substring(sp + 1) : first;
        if (b.isEmpty()) b = cmd.trim();
        if (b.length() > 15) b = b.substring(0, 15);
        Proc p = new Proc();
        p.pid = pid;
        p.name = b;
        p.cpuPct = cpu;
        return p;
    }

    /** /proc 差分回退（top 不可用） */
    private List<Proc> procFallback() {
        int ncpu = Math.max(1, Runtime.getRuntime().availableProcessors());
        String dump = "";
        try {
            RootShell.Result r = RootShell.exec(
                    "for p in /proc/[0-9]*; do echo \"@@$(basename $p) $(cat $p/stat 2>/dev/null)\"; done", 10);
            if (r.ok() && r.out != null) dump = r.out;
        } catch (Exception ignored) {
        }
        long total = -1;
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/stat"))) {
            String l = br.readLine();
            if (l != null && l.startsWith("cpu ")) {
                String[] p = l.trim().split("\\s+");
                long sum = 0;
                for (int i = 1; i < p.length; i++) {
                    try {
                        sum += Long.parseLong(p[i]);
                    } catch (Exception ignored) {
                    }
                }
                total = sum;
            }
        } catch (Exception ignored) {
        }
        long prevTotal = lastTotalJiffies;
        lastTotalJiffies = total;
        Map<Integer, Long> cur = new HashMap<>();
        List<Proc> rows = new ArrayList<>();
        for (String raw : dump.split("\\n")) {
            if (!raw.startsWith("@@")) continue;
            String body = raw.substring(2);
            int sp = body.indexOf(' ');
            if (sp <= 0) continue;
            int pid;
            try {
                pid = Integer.parseInt(body.substring(0, sp));
            } catch (Exception e) {
                continue;
            }
            String stat = body.substring(sp + 1);
            int open = stat.indexOf('('), close = stat.lastIndexOf(')');
            if (open <= 0 || close <= open) continue;   // 非法 stat 行
            String name = stat.substring(open + 1, close);
            if (name.isEmpty()) name = String.valueOf(pid);
            if (name.length() > 15) name = name.substring(0, 15);
            String after = stat.substring(close + 1).trim();
            String[] f = after.split("\\s+");
            long j = -1;
            if (f.length > 12) {
                try {
                    j = Long.parseLong(f[11]) + Long.parseLong(f[12]);   // utime + stime
                } catch (Exception ignored) {
                }
            }
            if (j < 0) continue;
            cur.put(pid, j);
            Proc p = new Proc();
            p.pid = pid;
            p.name = name;
            p.cpuPct = -1;
            rows.add(p);
        }
        List<Proc> out = new ArrayList<>();
        if (prevTotal > 0 && total > prevTotal) {
            double dTotal = total - prevTotal;
            for (Proc p : rows) {
                Long prev = lastProcJiffies.get(p.pid);
                if (prev == null) continue;
                double dj = cur.get(p.pid) - prev;
                if (dj <= 0) continue;
                p.cpuPct = (float) Math.max(0.1f, Math.min(100f * ncpu, dj / dTotal * 100.0 * ncpu));
                out.add(p);
            }
        }
        lastProcJiffies.clear();
        lastProcJiffies.putAll(cur);
        out.sort((a, b) -> Float.compare(b.cpuPct, a.cpuPct));
        return out.size() > 48 ? out.subList(0, 48) : out;
    }

    // ==================== 通知 ====================

    private Notification notif(int n) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null && nm.getNotificationChannel("float_mgr") == null) {
            nm.createNotificationChannel(new NotificationChannel("float_mgr",
                    "悬浮监视器", NotificationManager.IMPORTANCE_MIN));
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, "float_mgr")
                .setContentTitle("悬浮监视器运行中")
                .setContentText(Math.max(1, n) + " 个监视悬浮窗")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    private void updateNotif() {
        int n = 0;
        for (boolean b : winOpen) if (b) n++;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(0x6CB1, notif(n));
    }

    // ==================== 供主页调用的开关入口（照搬 Kin FloatWindowService.show/hide） ====================

    public static void show(Context ctx, int type) {
        Intent it = new Intent(ctx, MonitorService.class).setAction("show")
                .putExtra("type", typeId(type));
        ctx.startForegroundService(it);
    }

    public static void hide(Context ctx, int type) {
        try {
            ctx.startService(new Intent(ctx, MonitorService.class).setAction("hide")
                    .putExtra("type", typeId(type)));
        } catch (Exception ignored) {
        }
    }

    static String typeId(int type) {
        switch (type) {
            case T_LOAD: return "load";
            case T_PROCESS: return "process";
            case T_MINI: return "mini";
            default: return "temp";
        }
    }

    // ============================================================
    // 悬浮窗窗体（1:1 照搬 Kin OverlayWindows：配色/尺寸/手势/动画）
    // ============================================================

    /** Metric 配色：深色玻璃 + 白字分层 */
    private static final int WHITE = Color.WHITE;
    private static final int WHITE92 = Color.argb(235, 255, 255, 255);
    private static final int WHITE90 = Color.argb(230, 255, 255, 255);
    private static final int WHITE80 = Color.argb(204, 255, 255, 255);
    private static final int WHITE74 = Color.argb(189, 255, 255, 255);
    private static final int GREY = Color.argb(158, 255, 255, 255);
    private static final int ORANGE = Color.parseColor("#FFB300");
    private static final int RED = Color.parseColor("#EF5350");
    private static final int GREEN = Color.parseColor("#00E676");
    private static final int GLASS_BASE = Color.rgb(0x26, 0x2A, 0x30);

    /** 窗体抽象基类：玻璃底 + monospace 文本（照搬 Kin OverlayWindow） */
    abstract class OverlayWindow {
        Runnable onCloseRequest;

        abstract View getRootView();

        abstract void onTick(TickData t);

        void onTap() {
        }

        void onLongPress() {
        }

        View getRoot() {
            return getRootView();
        }

        TextView mono(float sizeSp, int color) {
            return mono(sizeSp, color, true);
        }

        TextView mono(float sizeSp, int color, boolean bold) {
            TextView tv = new TextView(MonitorService.this);
            tv.setTextColor(color);
            tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, sizeSp);
            tv.setTypeface(bold ? Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                    : Typeface.MONOSPACE);
            tv.setIncludeFontPadding(false);
            tv.setMaxLines(1);
            return tv;
        }

        /** 液态玻璃底：竖向渐变 + 白色亮边 */
        GradientDrawable glassBg(int cornerDp) {
            int alpha = (int) (0.46f * 255);
            int top = Color.argb((int) (alpha * 0.92f),
                    Math.min(255, Color.red(GLASS_BASE) + 36),
                    Math.min(255, Color.green(GLASS_BASE) + 36),
                    Math.min(255, Color.blue(GLASS_BASE) + 36));
            int bottom = Color.argb(alpha,
                    Color.red(GLASS_BASE), Color.green(GLASS_BASE), Color.blue(GLASS_BASE));
            GradientDrawable g = new GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM, new int[]{top, bottom});
            g.setCornerRadius(dp(cornerDp));
            g.setStroke(Math.max(1, Math.round(0.8f * dp)), Color.argb(56, 255, 255, 255));
            return g;
        }

        /** 圆形图标钮：白 10% 圆底 + 白 74% 字符 */
        TextView circleBtn(String glyph, float sizeSp, Runnable onClick) {
            TextView tv = mono(sizeSp, WHITE74);
            tv.setText(glyph);
            tv.setGravity(Gravity.CENTER);
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(Color.argb(26, 255, 255, 255));
            tv.setBackground(bg);
            tv.setOnClickListener(v -> {
                try {
                    onClick.run();
                } catch (Exception ignored) {
                }
            });
            return tv;
        }

        String fmtMhz(int mhz) {
            return mhz <= 0 ? "-- MHz" : mhz + "MHz";
        }
    }

    /** 限高滚动容器（照搬 Kin MaxHeightScrollView） */
    static class MaxHeightScrollView extends android.widget.ScrollView {
        private final int maxHPx;
        private final boolean exact;

        MaxHeightScrollView(android.content.Context ctx, int maxHPx, boolean exact) {
            super(ctx);
            this.maxHPx = maxHPx;
            this.exact = exact;
            setVerticalScrollBarEnabled(false);
            setOverScrollMode(View.OVER_SCROLL_NEVER);
        }

        @Override
        protected void onMeasure(int wms, int hms) {
            int mode = exact ? View.MeasureSpec.EXACTLY : View.MeasureSpec.AT_MOST;
            super.onMeasure(wms, View.MeasureSpec.makeMeasureSpec(maxHPx, mode));
        }
    }

    /** 环形进度表（照搬 Kin RingView：白@0.2 轨道 + 阈值色进度弧，420ms 补间） */
    class RingView extends View {
        private float shownPct = 0f, targetPct = 0f;
        private android.animation.ValueAnimator anim;
        private final float inset;
        private final RectF bounds = new RectF();
        private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private Runnable onShown;

        RingView(float strokePx) {
            super(MonitorService.this);
            inset = strokePx / 2f + 1f;
            trackPaint.setStyle(Paint.Style.STROKE);
            trackPaint.setStrokeWidth(strokePx);
            trackPaint.setColor(Color.argb(51, 255, 255, 255));
            arcPaint.setStyle(Paint.Style.STROKE);
            arcPaint.setStrokeWidth(strokePx);
            arcPaint.setStrokeCap(Paint.Cap.ROUND);
            arcPaint.setColor(GREEN);
        }

        void set(int pct, int arcColor) {
            arcPaint.setColor(arcColor);
            float to = Math.max(0, Math.min(100, pct));
            if (to == targetPct) {
                invalidate();
                return;
            }
            targetPct = to;
            if (anim != null) anim.cancel();
            anim = android.animation.ValueAnimator.ofFloat(shownPct, to);
            anim.setDuration(420);
            anim.setInterpolator(new android.view.animation.PathInterpolator(0.15f, 1f, 0.3f, 1f));
            anim.addUpdateListener(a -> {
                shownPct = (float) a.getAnimatedValue();
                if (onShown != null) onShown.run();
                invalidate();
            });
            anim.start();
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            if (anim != null) anim.cancel();
            anim = null;
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            bounds.set(inset, inset, w - inset, h - inset);
        }

        @Override
        protected void onDraw(Canvas c) {
            if (bounds.isEmpty()) return;
            c.drawArc(bounds, 0f, 360f, false, trackPaint);
            if (shownPct > 0f) c.drawArc(bounds, -90f, shownPct / 100f * 360f, false, arcPaint);
        }
    }

    /**
     * 负载监视器（照搬 Kin LoadOverlayWindow）：
     * 收起 = 124×54dp 三环胶囊（CPU/GPU/电量）；展开 = 参数网格（#RAM/#CPU/各簇各核/#FPS/#PWR）
     * 单击展开/收起（420/360ms 形变），长按关闭
     */
    class LoadOverlayWindow extends OverlayWindow {
        private class RingCol {
            final LinearLayout col;
            final RingView ring;
            final TextView center, below;

            RingCol(String centerText) {
                ring = new RingView(dp(4));
                center = mono(7, WHITE);
                center.setText(centerText);
                center.setGravity(Gravity.CENTER);
                FrameLayout frame = new FrameLayout(MonitorService.this);
                frame.addView(ring, new FrameLayout.LayoutParams(dp(32), dp(32)));
                frame.addView(center, new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
                below = mono(6, WHITE);
                below.setText("-- MHz");
                below.setGravity(Gravity.CENTER);
                col = new LinearLayout(MonitorService.this);
                col.setOrientation(LinearLayout.VERTICAL);
                col.setGravity(Gravity.CENTER_HORIZONTAL);
                col.addView(frame, new LinearLayout.LayoutParams(dp(32), dp(32)));
                LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(32), dp(8));
                blp.topMargin = dp(2);
                col.addView(below, blp);
            }
        }

        private final int ringSize = dp(32);
        private final RingCol hCpu = new RingCol("CPU"), hGpu = new RingCol("GPU"), hBat = new RingCol("--");
        private final RingCol vCpu = new RingCol("CPU"), vGpu = new RingCol("GPU"), vBat = new RingCol("--");

        private final LinearLayout collapsedRow = new LinearLayout(MonitorService.this);
        private final LinearLayout grid = new LinearLayout(MonitorService.this);
        private final List<TextView[]> gridRows = new ArrayList<>();
        private final LinearLayout expandedBox = new LinearLayout(MonitorService.this);
        private final FrameLayout shell = new FrameLayout(MonitorService.this);
        private final FrameLayout root = new FrameLayout(MonitorService.this);
        private boolean expanded = false;
        private TickData last;
        private android.animation.ValueAnimator morph;

        LoadOverlayWindow(int accent) {
            collapsedRow.setOrientation(LinearLayout.HORIZONTAL);
            collapsedRow.setGravity(Gravity.CENTER);
            collapsedRow.setPadding(dp(6), dp(6), dp(6), dp(6));
            collapsedRow.addView(hCpu.col);
            LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            glp.leftMargin = dp(8);
            glp.rightMargin = dp(8);
            collapsedRow.addView(hGpu.col, glp);
            collapsedRow.addView(hBat.col);

            grid.setOrientation(LinearLayout.VERTICAL);
            LinearLayout ringsColV = new LinearLayout(MonitorService.this);
            ringsColV.setOrientation(LinearLayout.VERTICAL);
            ringsColV.setGravity(Gravity.CENTER_HORIZONTAL);
            ringsColV.addView(vCpu.col);
            LinearLayout.LayoutParams r2 = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            r2.topMargin = dp(5);
            ringsColV.addView(vGpu.col, r2);
            ringsColV.addView(vBat.col, r2);
            expandedBox.setOrientation(LinearLayout.HORIZONTAL);
            expandedBox.setGravity(Gravity.CENTER_VERTICAL);
            expandedBox.setPadding(dp(6), dp(6), dp(6), dp(6));
            expandedBox.addView(ringsColV, new LinearLayout.LayoutParams(dp(44),
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            plp.leftMargin = Math.round(1.5f * dp);
            expandedBox.addView(grid, plp);
            expandedBox.setVisibility(View.GONE);
            expandedBox.setAlpha(0f);

            shell.setClipChildren(true);
            shell.setClipToPadding(true);
            shell.setBackground(glassBg(16));
            shell.addView(collapsedRow, new FrameLayout.LayoutParams(dp(124), dp(54)));
            shell.addView(expandedBox, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT));
            root.setClipChildren(true);
            root.addView(shell, new FrameLayout.LayoutParams(dp(124), dp(54)));

            vBat.ring.onShown = () -> vBat.center.setText(Math.round(vBat.ring.shownPct) + "%");
            hBat.ring.onShown = () -> hBat.center.setText(Math.round(hBat.ring.shownPct) + "%");
        }

        @Override
        View getRootView() {
            return root;
        }

        @Override
        void onTap() {
            setExpanded(!expanded);
        }

        @Override
        void onLongPress() {
            if (onCloseRequest != null) onCloseRequest.run();
        }

        /** 弧色阈值（Metric）：<70 绿 / <90 琥珀 / ≥90 红 */
        private int loadColor(int pct) {
            if (pct < 70) return GREEN;
            if (pct < 90) return ORANGE;
            return RED;
        }

        /** 展开尺寸公式：行数 = 5 + Σ(每簇核数+1)；高 = max(136, 8.5×行+2)+12；宽 134（无 IPC） */
        private int[] expandedSize() {
            List<Cluster> cls = last != null && last.snap.clusters != null
                    ? last.snap.clusters : Collections.emptyList();
            int coreRows = 0;
            for (Cluster c : cls) coreRows += c.cpus.length + 1;
            if (coreRows == 0) coreRows = Math.max(
                    last != null && last.snap.coreLoadPct != null ? last.snap.coreLoadPct.length : 0,
                    last != null && last.snap.coreMhz != null ? last.snap.coreMhz.length : 0);
            int rows = 5 + coreRows;
            int w = dp(134);
            int h = Math.round((Math.max(136f, 8.5f * rows + 2f) + 12f) * dp);
            return new int[]{w, h};
        }

        private void setExpanded(boolean want) {
            if (expanded == want) return;
            expanded = want;
            if (morph != null) morph.cancel();
            if (want) renderGrid(last);
            View show = want ? expandedBox : collapsedRow;
            View hide = want ? collapsedRow : expandedBox;
            show.setVisibility(View.VISIBLE);
            int[] to = want ? expandedSize() : new int[]{dp(124), dp(54)};
            if (want) {
                FrameLayout.LayoutParams lpE = (FrameLayout.LayoutParams) expandedBox.getLayoutParams();
                lpE.width = to[0];
                lpE.height = to[1];
                expandedBox.setLayoutParams(lpE);
            }
            int fromW = shell.getWidth() > 0 ? shell.getWidth() : dp(124);
            int fromH = shell.getHeight() > 0 ? shell.getHeight() : dp(54);
            FrameLayout.LayoutParams lpS = (FrameLayout.LayoutParams) shell.getLayoutParams();
            morph = android.animation.ValueAnimator.ofFloat(0f, 1f);
            morph.setDuration(want ? 420 : 360);
            morph.setInterpolator(new android.view.animation.PathInterpolator(0.15f, 1f, 0.3f, 1f));
            final boolean w = want;
            morph.addUpdateListener(a -> {
                float f = a.getAnimatedFraction();
                lpS.width = (int) (fromW + (to[0] - fromW) * f);
                lpS.height = (int) (fromH + (to[1] - fromH) * f);
                shell.setLayoutParams(lpS);
                show.setAlpha(f);
                hide.setAlpha(1f - f);
            });
            morph.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator a) {
                    if (w) {
                        hide.setVisibility(View.GONE);
                        hide.setAlpha(1f);
                        lpS.width = to[0];
                        lpS.height = to[1];
                        shell.setLayoutParams(lpS);
                    }
                }
            });
            morph.start();
        }

        @Override
        void onTick(TickData t) {
            last = t;
            Snap s = t.snap;
            int cpu = Math.max(0, s.cpuLoadPct);
            int topMhz = -1;
            if (s.clusters != null) for (Cluster c : s.clusters) topMhz = Math.max(topMhz, c.mhz);
            String cpuBelow = fmtMhz(topMhz);
            int gpu = Math.max(0, s.gpuLoadPct);
            String gpuBelow = fmtMhz(s.gpuMhz);
            int batt = s.battPct >= 0 ? (int) s.battPct : -1;
            int battArc = Math.max(0, batt);
            int battColor = loadColor(batt >= 0 ? 100 - batt : 0);
            String battBelow = s.battTempC > 0
                    ? String.format(Locale.US, "%.0f℃", s.battTempC) : "--℃";

            RingCol[][] pairs = {{hCpu, vCpu}, {hGpu, vGpu}, {hBat, vBat}};
            for (int i = 0; i < pairs.length; i++) {
                int arc, color;
                String below;
                if (i == 0) {
                    arc = cpu;
                    color = loadColor(cpu);
                    below = cpuBelow;
                } else if (i == 1) {
                    arc = gpu;
                    color = loadColor(gpu);
                    below = gpuBelow;
                } else {
                    arc = battArc;
                    color = battColor;
                    below = battBelow;
                }
                for (RingCol rc : pairs[i]) {
                    rc.ring.set(arc, color);
                    rc.below.setText(below);
                }
            }
            if (expanded && (morph == null || !morph.isRunning())) {
                renderGrid(t);
                int[] wh = expandedSize();
                FrameLayout.LayoutParams lpE = (FrameLayout.LayoutParams) expandedBox.getLayoutParams();
                if (lpE.width != wh[0] || lpE.height != wh[1]) {
                    lpE.width = wh[0];
                    lpE.height = wh[1];
                    expandedBox.setLayoutParams(lpE);
                    FrameLayout.LayoutParams lpS = (FrameLayout.LayoutParams) shell.getLayoutParams();
                    lpS.width = wh[0];
                    lpS.height = wh[1];
                    shell.setLayoutParams(lpS);
                }
            }
        }

        /** 展开参数网格：#RAM / DDR / #CPU / 各簇 #0-3 MHz + 每核 负载% 频率M / #FPS / #PWR */
        private void renderGrid(TickData t) {
            Snap s = t != null ? t.snap : null;
            List<String[]> rows = new ArrayList<>();
            rows.add(new String[]{"#RAM", s != null && s.ramUsedPct >= 0
                    ? Math.round(s.ramUsedPct) + "%" : "--"});
            rows.add(new String[]{"DDR", s != null && s.ddrMhz > 0 ? String.valueOf(s.ddrMhz) : "--"});
            rows.add(new String[]{"#CPU", s != null && s.cpuTempC > 0
                    ? String.format(Locale.US, "%.0f℃", s.cpuTempC) : "--℃"});
            int[] loads = s != null && s.coreLoadPct != null ? s.coreLoadPct : new int[0];
            int[] freqs = s != null && s.coreMhz != null ? s.coreMhz : new int[0];
            List<Cluster> cls = s != null && s.clusters != null ? s.clusters : Collections.emptyList();
            if (!cls.isEmpty()) {
                for (Cluster c : cls) {
                    List<Integer> cpus = new ArrayList<>();
                    for (int x : c.cpus) cpus.add(x);
                    Collections.sort(cpus);
                    String range = cpus.size() == 1 ? String.valueOf(cpus.get(0))
                            : cpus.get(0) + "-" + cpus.get(cpus.size() - 1);
                    rows.add(new String[]{"#" + range, c.mhz > 0 ? c.mhz + "MHz" : "-- MHz"});
                    for (int cpu : cpus) {
                        String ld = cpu < loads.length && loads[cpu] >= 0 ? loads[cpu] + "%" : "--";
                        String f = cpu < freqs.length && freqs[cpu] > 0 ? freqs[cpu] + "M" : "--";
                        rows.add(new String[]{ld, f});
                    }
                }
            } else if (loads.length > 0 || freqs.length > 0) {
                int n = Math.max(loads.length, freqs.length);
                for (int cpu = 0; cpu < n; cpu++) {
                    String ld = cpu < loads.length ? loads[cpu] + "%" : "--";
                    String f = cpu < freqs.length && freqs[cpu] > 0 ? freqs[cpu] + "M" : "--";
                    rows.add(new String[]{ld, f});
                }
            }
            float fps = s != null ? s.fps : -1;
            rows.add(new String[]{"#FPS", fps >= 0
                    ? String.format(Locale.US, "%.1f", fps) : "0.0"});
            float w = s != null ? s.watt : 0;
            rows.add(new String[]{"#PWR", w != 0 && !Float.isInfinite(w) && !Float.isNaN(w)
                    ? String.format(Locale.US, "%+.2fW", w) : "--W"});

            if (gridRows.size() != rows.size()) {
                grid.removeAllViews();
                gridRows.clear();
                for (int i = 0; i < rows.size(); i++) {
                    TextView label = mono(7, WHITE90);
                    label.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
                    TextView value = mono(7, WHITE80);
                    value.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                    LinearLayout box = new LinearLayout(MonitorService.this);
                    box.setOrientation(LinearLayout.HORIZONTAL);
                    box.setGravity(Gravity.CENTER_VERTICAL);
                    box.addView(label, new LinearLayout.LayoutParams(dp(20),
                            LinearLayout.LayoutParams.MATCH_PARENT));
                    LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(dp(46),
                            LinearLayout.LayoutParams.MATCH_PARENT);
                    vlp.leftMargin = dp(10);
                    box.addView(value, vlp);
                    gridRows.add(new TextView[]{label, value});
                    grid.addView(box, new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT, Math.round(8.5f * dp)));
                }
            }
            for (int i = 0; i < rows.size(); i++) {
                gridRows.get(i)[0].setText(rows.get(i)[0]);
                gridRows.get(i)[1].setText(rows.get(i)[1]);
            }
        }
    }

    /**
     * 迷你监视器（照搬 Kin MiniOverlayWindow）：顶部居中细条（点击穿透），
     * 四段：CPU（绿标+柱状图+最高簇频率）/ GPU（蓝标+频率）/ FPS（金标）/ PWR（粉标，功耗温度 9s 轮换）
     */
    class MiniOverlayWindow extends OverlayWindow {
        private final int segGreen = Color.parseColor("#8DEB9A");
        private final int segBlue = Color.parseColor("#8FB9FF");
        private final int segGold = Color.parseColor("#FFD166");
        private final int segPink = Color.parseColor("#FF8EA1");
        private final MiniCoreBars bars = new MiniCoreBars(segGreen);
        private final TextView vCpu = cell(WHITE), vGpu = cell(WHITE),
                vFps = cell(WHITE), vPwr = cell(WHITE);
        private final LinearLayout bar = new LinearLayout(MonitorService.this);
        private long tick;

        MiniOverlayWindow(int accent) {
            bar.setOrientation(LinearLayout.HORIZONTAL);
            bar.setGravity(Gravity.CENTER_VERTICAL);
            bar.setPadding(dp(3), dp(1), dp(3), dp(1));
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(8));
            bg.setColor(Color.argb(217, 27, 29, 34));
            bar.setBackground(bg);
            seg(54, label("CPU", segGreen), bars, vCpu);
            seg(32, label("GPU", segBlue), vGpu);
            seg(36, label("FPS", segGold), vFps);
            seg(46, label("PWR", segPink), vPwr);
        }

        private TextView label(String s, int color) {
            TextView tv = cell(color);
            tv.setText(s);
            return tv;
        }

        private TextView cell(int color) {
            TextView tv = new TextView(MonitorService.this);
            tv.setTextColor(color);
            tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 7);
            tv.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            tv.setIncludeFontPadding(false);
            tv.setMaxLines(1);
            return tv;
        }

        private void seg(int widthDp, View... views) {
            LinearLayout segBox = new LinearLayout(MonitorService.this);
            segBox.setOrientation(LinearLayout.HORIZONTAL);
            segBox.setGravity(Gravity.CENTER_VERTICAL);
            for (int i = 0; i < views.length; i++) {
                LinearLayout.LayoutParams lp;
                if (views[i] instanceof MiniCoreBars) {
                    lp = new LinearLayout.LayoutParams(dp(18), dp(8));
                } else {
                    lp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                }
                if (i > 0) lp.leftMargin = dp(1);
                segBox.addView(views[i], lp);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(widthDp),
                    LinearLayout.LayoutParams.MATCH_PARENT);
            if (bar.getChildCount() > 0) lp.leftMargin = dp(1);
            bar.addView(segBox, lp);
        }

        @Override
        View getRootView() {
            return bar;
        }

        @Override
        void onTick(TickData t) {
            tick++;
            Snap s = t.snap;
            int topMhz = -1;
            if (s.clusters != null) for (Cluster c : s.clusters) topMhz = Math.max(topMhz, c.mhz);
            vCpu.setText(topMhz > 0 ? String.valueOf(topMhz) : "--");
            vGpu.setText(s.gpuMhz > 0 ? String.valueOf(s.gpuMhz) : "--");
            if (s.coreLoadPct != null && s.coreLoadPct.length > 0) bars.set(s.coreLoadPct);
            vFps.setText(s.fps >= 0 ? String.format(Locale.US, "%.1f", s.fps) : "0.0");
            if (tick % 9 < 6) {
                vPwr.setText(s.watt != 0 && !Float.isInfinite(s.watt) && !Float.isNaN(s.watt)
                        ? String.format(Locale.US, "%+.2fW", s.watt) : "--W");
            } else {
                vPwr.setText(s.cpuTempC > 0 ? String.format(Locale.US, "%.1f℃", s.cpuTempC) : "--℃");
            }
        }
    }

    /** 每核负载柱状图（照搬 Kin MiniCoreBars） */
    class MiniCoreBars extends View {
        private int[] loads = new int[0];
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        MiniCoreBars(int color) {
            super(MonitorService.this);
            paint.setColor(color);
        }

        void set(int[] coreLoads) {
            loads = new int[coreLoads.length];
            for (int i = 0; i < coreLoads.length; i++) {
                loads[i] = Math.max(0, Math.min(100, coreLoads[i]));
            }
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            int n = loads.length;
            if (n == 0) return;
            float w = getWidth(), h = getHeight();
            float step = w / n;
            float barW = Math.max(0.72f * step, dp);
            float usable = h - 1.5f * dp;
            for (int i = 0; i < n; i++) {
                float bh = Math.max(loads[i] / 100f * usable, dp);
                float x = i * step + (step - barW) / 2f;
                float top = h - dp - bh;
                rect.set(x, top, x + barW, h - dp);
                c.drawRoundRect(rect, dp, dp, paint);
            }
        }
    }

    /**
     * 温度监视器（照搬 Kin TempOverlayWindow）：83×54dp，BAT/CPU/GPU/DDR 每行 "%.1f℃"，
     * temp≤0 整行隐藏；长按关闭
     */
    class TempOverlayWindow extends OverlayWindow {
        private final TextView tBat, tCpu, tGpu, tDdr;
        private final FrameLayout root = new FrameLayout(MonitorService.this);

        TempOverlayWindow(int accent) {
            LinearLayout card = new LinearLayout(MonitorService.this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(12), dp(7), dp(12), dp(7));
            card.setGravity(Gravity.CENTER);
            card.setBackground(glassBg(18));
            tBat = tempRow();
            tCpu = tempRow();
            tGpu = tempRow();
            tDdr = tempRow();
            TextView[] all = {tBat, tCpu, tGpu, tDdr};
            for (TextView tv : all) {
                card.addView(tv, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, dp(10)));
            }
            root.addView(card, new FrameLayout.LayoutParams(dp(83), dp(54)));
        }

        private TextView tempRow() {
            TextView tv = mono(8.5f, WHITE);
            tv.setGravity(Gravity.CENTER_VERTICAL);
            return tv;
        }

        @Override
        View getRootView() {
            return root;
        }

        @Override
        void onLongPress() {
            if (onCloseRequest != null) onCloseRequest.run();
        }

        private void line(TextView tv, String label, double c) {
            if (c <= 0.0) {
                tv.setVisibility(View.GONE);
                return;
            }
            tv.setVisibility(View.VISIBLE);
            tv.setText(String.format(Locale.US, "%s %.1f℃", label, c));
        }

        @Override
        void onTick(TickData t) {
            line(tBat, "BAT", t.snap.battTempC);
            line(tCpu, "CPU", t.snap.cpuTempC);
            line(tGpu, "GPU", t.snap.gpuTempC);
            line(tDdr, "DDR", t.snap.ddrTempC);
        }
    }

    /**
     * 进程监视器（照搬 Kin ProcessOverlayWindow）：标题 + 全部/应用分段筛选 + ✕ 关闭；
     * 48 行列表（图标 + 名称 + CPU%），行双击 root 结束进程；空数据居中空态
     */
    class ProcessOverlayWindow extends OverlayWindow {
        private class Line {
            final LinearLayout box;
            final TextView icon, name, pct;

            Line(LinearLayout box, TextView icon, TextView name, TextView pct) {
                this.box = box;
                this.icon = icon;
                this.name = name;
                this.pct = pct;
            }
        }

        private boolean appsOnly = false;
        private List<Proc> lastProcs;
        private final TextView tabAll, tabApp;
        private final View indicator;
        private final List<Line> lines = new ArrayList<>();
        private final String[] linePkgs = new String[48];
        private final LinearLayout listBox = new LinearLayout(MonitorService.this);
        private View listScroll;
        private final TextView emptyView;
        private final LinearLayout card = new LinearLayout(MonitorService.this);
        private final View root = card;

        ProcessOverlayWindow(int accent) {
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(10), dp(10), dp(10), dp(10));
            card.setBackground(glassBg(18));

            TextView tHead = mono(11, WHITE);
            tHead.setText("进程");
            tabAll = tab("全部", () -> setMode(false));
            tabApp = tab("应用", () -> setMode(true));
            indicator = new View(MonitorService.this);
            GradientDrawable ind = new GradientDrawable();
            ind.setCornerRadius(dp(10));
            ind.setColor(Color.argb(46, 255, 255, 255));
            indicator.setBackground(ind);
            FrameLayout segTrack = new FrameLayout(MonitorService.this);
            GradientDrawable track = new GradientDrawable();
            track.setCornerRadius(dp(12));
            track.setColor(Color.argb(26, 255, 255, 255));
            segTrack.setBackground(track);
            segTrack.setPadding(dp(2), dp(2), dp(2), dp(2));
            segTrack.addView(indicator, new FrameLayout.LayoutParams(dp(34), dp(20)));
            LinearLayout tabs = new LinearLayout(MonitorService.this);
            tabs.setOrientation(LinearLayout.HORIZONTAL);
            tabs.addView(tabAll, new LinearLayout.LayoutParams(dp(34), dp(20)));
            tabs.addView(tabApp, new LinearLayout.LayoutParams(dp(34), dp(20)));
            segTrack.addView(tabs);

            LinearLayout header = new LinearLayout(MonitorService.this);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);
            header.addView(tHead, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            header.addView(segTrack, new LinearLayout.LayoutParams(dp(72), dp(24)));
            LinearLayout.LayoutParams xlp = new LinearLayout.LayoutParams(dp(24), dp(24));
            xlp.leftMargin = dp(4);
            header.addView(circleBtn("✕", 10, () -> {
                if (onCloseRequest != null) onCloseRequest.run();
            }), xlp);
            card.addView(header, new LinearLayout.LayoutParams(dp(168),
                    LinearLayout.LayoutParams.WRAP_CONTENT));

            for (int i = 0; i < 48; i++) {
                TextView icon = new TextView(MonitorService.this);
                icon.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 8);
                icon.setGravity(Gravity.CENTER);
                icon.setIncludeFontPadding(false);
                TextView name = mono(9, WHITE);
                name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                TextView pct = mono(10, WHITE74);
                LinearLayout box = new LinearLayout(MonitorService.this);
                box.setOrientation(LinearLayout.HORIZONTAL);
                box.setGravity(Gravity.CENTER_VERTICAL);
                box.addView(icon, new LinearLayout.LayoutParams(dp(16), dp(16)));
                LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                nlp.leftMargin = dp(4);
                box.addView(name, nlp);
                LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                plp.leftMargin = dp(6);
                box.addView(pct, plp);
                final int idx = i;
                android.view.GestureDetector gd = new android.view.GestureDetector(MonitorService.this,
                        new android.view.GestureDetector.SimpleOnGestureListener() {
                            @Override
                            public boolean onDown(MotionEvent e) {
                                return true;
                            }

                            @Override
                            public boolean onDoubleTap(MotionEvent e) {
                                killRow(idx);
                                return true;
                            }
                        });
                box.setOnTouchListener((v, ev) -> gd.onTouchEvent(ev));
                lines.add(new Line(box, icon, name, pct));
                LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(21));
                if (i > 0) blp.topMargin = dp(3);
                box.setVisibility(View.GONE);   // 首次采样前隐藏，避免整屏空白行
                listBox.addView(box, blp);
            }
            listBox.setOrientation(LinearLayout.VERTICAL);
            MaxHeightScrollView scroll = new MaxHeightScrollView(MonitorService.this, dp(165), true);
            scroll.addView(listBox);
            listScroll = scroll;
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(168),
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            slp.topMargin = dp(5);
            card.addView(scroll, slp);
            emptyView = mono(9, GREY);
            emptyView.setText("无进程数据");
            emptyView.setGravity(Gravity.CENTER);
            emptyView.setMinHeight(dp(96));
            emptyView.setPadding(dp(12), 0, dp(12), 0);
            emptyView.setVisibility(View.GONE);
            card.addView(emptyView, new LinearLayout.LayoutParams(dp(168),
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            styleTabs();
        }

        private TextView tab(String label, Runnable onClick) {
            TextView tv = mono(8, GREY, false);
            tv.setText(label);
            tv.setGravity(Gravity.CENTER);
            tv.setOnClickListener(v -> onClick.run());
            return tv;
        }

        @Override
        View getRootView() {
            return root;
        }

        private void killRow(int i) {
            String pkg = i < linePkgs.length ? linePkgs[i] : null;
            if (pkg == null || pkg.isEmpty()) return;
            new Thread(() -> {
                try {
                    RootShell.exec("am force-stop " + pkg);
                } catch (Exception ignored) {
                }
            }).start();
            Toast.makeText(MonitorService.this, "已请求结束: " + pkg, Toast.LENGTH_SHORT).show();
        }

        private void setMode(boolean only) {
            if (appsOnly == only) return;
            appsOnly = only;
            indicator.animate().translationX(only ? dp(34) : 0f)
                    .setDuration(180)
                    .setInterpolator(new android.view.animation.PathInterpolator(0.15f, 1f, 0.3f, 1f))
                    .start();
            styleTabs();
            render();
        }

        private void styleTabs() {
            tabAll.setTextColor(appsOnly ? GREY : WHITE92);
            tabAll.setTypeface(Typeface.create(Typeface.MONOSPACE,
                    appsOnly ? Typeface.NORMAL : Typeface.BOLD));
            tabApp.setTextColor(appsOnly ? WHITE92 : GREY);
            tabApp.setTypeface(Typeface.create(Typeface.MONOSPACE,
                    appsOnly ? Typeface.BOLD : Typeface.NORMAL));
        }

        private String pkgOf(String name) {
            int c = name.indexOf(':');
            return (c > 0 ? name.substring(0, c) : name).trim();
        }

        // 图标 / 应用名缓存（进程名 → 字符/显示名）
        private final Map<String, String> labelCache = new HashMap<>();

        private String labelFor(String name) {
            String pkg = pkgOf(name);
            if (!pkg.contains(".")) return null;
            if (labelCache.containsKey(name)) return labelCache.get(name);
            String label = null;
            try {
                PackageManager pm = getPackageManager();
                label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
            } catch (Exception ignored) {
            }
            if (labelCache.size() > 512) labelCache.clear();
            labelCache.put(name, label);
            return label;
        }

        private String iconFor(String name) {
            String label = labelFor(name);
            if (label != null) {
                // 有应用：首字符当图标（无矢量图标资源，文本替代 Metric 的圆角图标）
                String s = label.trim();
                return s.isEmpty() ? "?" : s.substring(0, 1);
            }
            return "🐧";   // 原生/系统进程统一企鹅标示（照搬 Kin）
        }

        @Override
        void onTick(TickData t) {
            if (t.procs == null) return;
            lastProcs = t.procs;
            render();
        }

        private void render() {
            List<Proc> all = lastProcs;
            List<Proc> ps;
            if (appsOnly) {
                ps = new ArrayList<>();
                List<String> seen = new ArrayList<>();
                for (Proc p : all != null ? all : Collections.<Proc>emptyList()) {
                    if (labelFor(p.name) == null) continue;
                    String pkg = pkgOf(p.name);
                    if (seen.contains(pkg)) continue;
                    seen.add(pkg);
                    ps.add(p);
                }
            } else {
                ps = all != null ? all : Collections.<Proc>emptyList();
            }
            boolean empty = ps.isEmpty();
            emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
            listScroll.setVisibility(empty ? View.GONE : View.VISIBLE);
            for (int i = 0; i < lines.size(); i++) {
                Line l = lines.get(i);
                Proc p = i < ps.size() ? ps.get(i) : null;
                linePkgs[i] = p != null ? pkgOf(p.name) : null;
                if (p == null) {
                    l.box.setVisibility(View.GONE);
                } else {
                    l.box.setVisibility(View.VISIBLE);
                    l.icon.setText(iconFor(p.name));
                    String label = labelFor(p.name);
                    l.name.setText(label != null ? label : p.name);
                    l.pct.setText(String.format(Locale.US, "%.1f%%", p.cpuPct));
                }
            }
        }
    }
}
