package Color.fc;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
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
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Scene 款多监视器悬浮窗架构：
 * - 状态栏药丸（置顶、可拖动、长按锁定）：点击展开"监视器功能"列表，收纳全部监视器开关
 *   （负载监视器 / 帧率记录器 / 线程监视器 / 温度监视器 + 关闭监视器）
 * - 负载监视器：三圆环（CPU/GPU/电池）+ 密集数据行 + 底部 #PWR 条；
 *   单击 展开/折叠，双击 关闭窗口，长按 锁定/解锁位置
 * - 帧率记录器：独立 FPS 窗口（不并入负载监视器），点击开始/停止录制并保存
 * - 线程监视器：前台应用 CPU 占用最高的 6 条线程，点击关闭窗口
 * - 温度监视器：CPU/SOC/BAT 温度，点击关闭窗口
 * - 全部窗口可拖动（含状态栏上方，FLAG_LAYOUT_IN_SCREEN），长按锁定后不可拖
 * - 帧率检测：单次 shell 合并调用 + 增量差分 + 瞬时失败保持上一有效值
 * - 功耗 500ms 采样（与主页电芯模式同步），GPU/集群/温度 2s 扫描，线程 1s 采样
 */
public class MonitorService extends Service {

    /** 主页开关状态同步用 */
    public static volatile boolean running = false;
    /** 录制最短时长 */
    private static final long MIN_REC_MS = 3000;

    // 监视器索引
    private static final int M_LOAD = 0, M_FPS = 1, M_THR = 2, M_TEMP = 3, M_PWR = 4;
    private static final String[] MON_KEYS = {"mon_load", "mon_fps", "mon_thr", "mon_temp", "mon_pwr"};
    private static final String[] MON_LABELS = {"负载监视器", "帧率记录器", "线程监视器", "温度监视器", "功耗监视器"};

    private WindowManager wm;
    private final Handler ui = new Handler(Looper.getMainLooper());
    /** 电芯模式（与主页同步）：0=自动校准 1=强制单电芯 2=强制双电芯 */
    private int cellMode = 0;

    // ===== 药丸 + 功能列表 =====
    private TextView pill;
    /** 菜单胶囊隐藏状态（长按胶囊隐藏，通知栏「显示菜单」恢复） */
    private boolean pillHidden = false;
    private LinearLayout listPanel;
    private TextView listTitle, listQuit;
    private final TextView[] listRows = new TextView[5];
    private boolean listOpen = false;

    // ===== 各监视器窗口 =====
    private final View[] win = new View[5];
    private final WindowManager.LayoutParams[] wlp = new WindowManager.LayoutParams[5];
    private final boolean[] winOpen = new boolean[5];
    // 负载监视器
    private RingsView rings;
    private LinearLayout detailBox, clusterBox;
    private TextView rowRam, rowCpu, rowGpu, rowBat;
    private boolean expanded = false;
    // 帧率记录器
    private TextView fpsText;
    // 功耗监视器
    private TextView pwrBig;
    // 线程监视器
    private final TextView[] thrRows = new TextView[6];
    // 温度监视器
    private TextView tempText;

    /** 长按锁定位置（全部窗口包括药丸共用；每次启动服务恢复可拖动） */
    private boolean locked = false;
    private long lastTapAt = 0;
    private Runnable pendingTap;

    // ===== 显示缓存（工作线程写 / UI 读） =====
    private volatile double cW = -1;       // 功耗 W（已按电芯模式修正）
    private volatile double cV = -1, cA = -1;   // 电压 V / 电流 A
    private volatile double cBusy = -1;    // CPU 总占用 %
    private volatile double cCpuM = 0;      // CPU 最高频 MHz
    private volatile double cGpuM = 0;      // GPU 频率 MHz
    private volatile double cCpuT = 0, cSocT = 0, cBatT = 0;
    private volatile float cHz = 0;         // 实时帧率
    private volatile float batPct = -1;     // 电量 %
    private volatile float ramPct = -1, ramUsedG = 0;
    private volatile float gpuLoad = -1;    // GPU 负载 %（不可读时 -1）
    private double gpuMax = 0;              // GPU 最大频率 MHz
    private volatile long recShown = 0;    // 录制已进行时长 ms

    // CPU 集群（policy）
    private static final int MAX_CL = 3;
    private volatile int nCluster = 0;
    private final double[] clFreq = new double[MAX_CL];
    private final float[] clBusy = new float[MAX_CL];
    private final String[] clLbl = new String[MAX_CL];
    private int[][] clMap;                  // 集群 → 核心编号
    private int rowsBuilt = 0;              // 已创建的集群行数

    // CPU 占用差分基准（快速循环）
    private long lastIdle = -1, lastTotal = -1;
    // 录制 500ms 循环基准
    private long recLastIdle = -1, recLastTotal = -1;
    private long[] lastCoreIdle, lastCoreTotal;
    private int coreCount = 0;

    // ===== 实时帧率 =====
    private String fpsLayer, fpsPkg;   // 前台应用图层
    private long sfPrevNewest = -1;     // SF latency 增量基准（最近帧时间戳 ns）
    private long gfxFrames = -1;       // gfxinfo 总渲染帧数差分基准
    private long gfxAt;
    // 瞬时检测失败时保持上一有效值，防止 "--fps" 闪烁
    private float lastGoodHz = 0;
    private long lastGoodAt = 0;
    // 本机面板最高刷新率（帧率显示硬顶，检测失败时回退 240）
    private float peakHz = 0;

    // ===== 线程监视器 =====
    private Map<Integer, long[]> thrPrev;   // tid → [utime]
    private long thrLastAt = 0;
    private double thrTck = 100;
    private int thrPid = 0;          // 前台应用 pid 缓存
    private long thrPidAt = 0;       // 上次解析 pid 的时间

    // ===== 帧率录制 =====
    private boolean recording = false;
    private long recStart;
    private final ArrayList<Long> ts = new ArrayList<>();
    private final ArrayList<Float> fps = new ArrayList<>();
    private final ArrayList<Float> cpuTot = new ArrayList<>();
    private final ArrayList<float[]> cores = new ArrayList<>();

    private static final int[] CORE_COLORS = {
            0xFF00E5FF, 0xFF22D3EE, 0xFF10B981, 0xFFF59E0B,
            0xFFEF4444, 0xFF8B5CF6, 0xFFEC4899, 0xFF84CC16
    };

    // Scene 款配色：标签浅灰 / 数值纯白 / 次要信息暗灰
    private static final int COL_LABEL = 0xFF98A2B3;
    private static final int COL_VALUE = 0xFFF2F4F8;
    private static final int COL_DIM = 0xFF7D8696;
    private static final int COL_GREEN = 0xFF34C759;
    private static final int COL_ORANGE = 0xFFFF9500;
    private static final int COL_RED = 0xFFEF4444;

    /** 慢速扫描：GPU 频率/负载/最高频、CPU 各集群频率、集群拓扑、温区 */
    private static final String SCAN =
            "g=$(cat /sys/class/kgsl/kgsl-3d0/gpuclk 2>/dev/null);"
                    + "[ -n \"$g\" ] || g=$(cat /sys/class/kgsl/kgsl-3d0/devfreq/cur_freq 2>/dev/null);"
                    + "[ -n \"$g\" ] || g=$(cat /sys/class/devfreq/*qcom,gpu*/cur_freq 2>/dev/null);"
                    + "[ -n \"$g\" ] || g=$(cat /sys/class/devfreq/*gpu*/cur_freq 2>/dev/null);"
                    + "echo \"G:$g\";"
                    + "gl=$(cat /sys/class/kgsl/kgsl-3d0/devfreq/gpu_load 2>/dev/null);"
                    + "[ -n \"$gl\" ] || gl=$(cat /sys/class/kgsl/kgsl-3d0/gpu_busy_percentage 2>/dev/null);"
                    + "[ -n \"$gl\" ] || gl=$(cat /sys/class/kgsl/kgsl-3d0/gpuload 2>/dev/null);"
                    + "echo \"GL:$gl\";"
                    + "gm=$(cat /sys/class/kgsl/kgsl-3d0/max_gpuclk 2>/dev/null);"
                    + "[ -n \"$gm\" ] || gm=$(cat /sys/class/kgsl/kgsl-3d0/devfreq/max_freq 2>/dev/null);"
                    + "[ -n \"$gm\" ] || gm=$(cat /sys/class/kgsl/kgsl-3d0/gpu_available_frequencies 2>/dev/null | awk '{print $NF}');"
                    + "echo \"GM:$gm\";"
                    + "for q in /sys/devices/system/cpu/cpufreq/policy*; do "
                    + "echo \"Q:$(cat $q/scaling_cur_freq 2>/dev/null)\"; done;"
                    + "r=\"\"; for q in /sys/devices/system/cpu/cpufreq/policy*; do "
                    + "r=\"$r$(cat $q/related_cpu 2>/dev/null);\"; done; echo \"R:$r\";"
                    + "for z in /sys/class/thermal/thermal_zone*; do "
                    + "[ -f \"$z/temp\" ] || continue; "
                    + "echo \"T:$(cat \"$z/type\" 2>/dev/null):$(cat \"$z/temp\" 2>/dev/null)\"; done";

    /**
     * 帧率检测合并为单次 shell 调用（降低 su 往返延迟与偶发失败）：
     * P: 前台包名 → gfxinfo 总渲染帧数（纯数字行）→ L: 择优图层 → SF --latency 数据
     */
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
        if (intent != null && "show_pill".equals(intent.getAction())) {
            showPill();   // 通知栏「显示菜单」：恢复监视器菜单胶囊
            return START_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        SharedPreferences p = getSharedPreferences("colorfc", MODE_PRIVATE);
        cellMode = p.getInt("cellMode", 0);
        expanded = p.getBoolean("mon_expanded", false);
        pillHidden = p.getBoolean("mon_pill_hidden", false);
        locked = false;   // 不再自动锁定位置：每次启动均为可拖动状态（长按锁定仅本次会话内生效）
        for (int i = 0; i < 5; i++) winOpen[i] = p.getBoolean(MON_KEYS[i], i == M_LOAD);
        startForeground(1, notif());
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        try {   // 本机最高刷新率：所有显示设备支持模式的峰值
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
        buildListPanel();
        buildPill();
        buildLoadWin();
        buildFpsWin();
        buildThrWin();
        buildTempWin();
        buildPwrWin();
        for (int i = 0; i < 5; i++) if (winOpen[i]) addWin(i);
        ui.postDelayed(this::fastLoop, 200);
        ui.postDelayed(this::slowLoop, 800);
        ui.postDelayed(this::threadLoop, 600);
    }

    @Override
    public void onDestroy() {
        running = false;
        ui.removeCallbacksAndMessages(null);
        if (recording) stopRec(false);
        for (int i = 0; i < 5; i++) {
            if (win[i] != null && win[i].getParent() != null) {
                try {
                    wm.removeView(win[i]);
                } catch (Exception ignored) {
                }
            }
        }
        if (listPanel != null && listPanel.getParent() != null) {
            try {
                wm.removeView(listPanel);
            } catch (Exception ignored) {
            }
        }
        if (pill != null) {
            try {
                wm.removeView(pill);
            } catch (Exception ignored) {
            }
        }
        super.onDestroy();
    }

    // ==================== 通用 ====================

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** Scene 式悬浮窗背景：深灰半透明（≈83%），无描边无阴影 */
    private GradientDrawable winBg(int radius) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xD425272C);
        bg.setCornerRadius(radius);
        return bg;
    }

    private WindowManager.LayoutParams overlayLp(int gravity) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,   // 可拖到状态栏上
                PixelFormat.TRANSLUCENT);
        lp.gravity = gravity;
        return lp;
    }

    private TextView mkText(String s, int color, float sizeSp) {
        TextView tv = new TextView(this);
        tv.setTextColor(color);
        tv.setTextSize(sizeSp);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setText(s);
        return tv;
    }

    private LinearLayout mkBox(int radius, int padH, int padV) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setBackground(winBg(radius));
        b.setPadding(dp(padH), dp(padV), dp(padH), dp(padV));
        return b;
    }

    private boolean hit(View v, MotionEvent e) {
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        return e.getRawX() >= loc[0] && e.getRawX() <= loc[0] + v.getWidth()
                && e.getRawY() >= loc[1] && e.getRawY() <= loc[1] + v.getHeight();
    }

    // ==================== 窗口显隐 ====================

    private void addWin(int idx) {
        try {
            wm.addView(win[idx], wlp[idx]);
        } catch (Exception ignored) {
        }
    }

    private void showWin(int idx) {
        if (winOpen[idx]) return;
        winOpen[idx] = true;
        getSharedPreferences("colorfc", MODE_PRIVATE).edit()
                .putBoolean(MON_KEYS[idx], true).apply();
        addWin(idx);
        if (idx == M_THR) thrPrev = null;   // 线程基准重建
        if (idx == M_LOAD) updateUi();
        syncList();
    }

    private void hideWin(int idx) {
        if (!winOpen[idx]) return;
        winOpen[idx] = false;
        getSharedPreferences("colorfc", MODE_PRIVATE).edit()
                .putBoolean(MON_KEYS[idx], false).apply();
        try {
            wm.removeView(win[idx]);
        } catch (Exception ignored) {
        }
        if (idx == M_FPS && recording) stopRec(true);   // 窗口关闭时保存录制
        if (idx == M_LOAD) {
            // 负载监视器关闭：CPU 差分基准失效，避免重开后跨周期误算
            lastIdle = -1;
            lastTotal = -1;
            lastCoreIdle = null;
        }
        syncList();
    }

    // ==================== 状态栏药丸（置顶、可拖动、长按锁定） + 功能列表 ====================

    private void buildPill() {
        pill = new TextView(this);
        pill.setTypeface(Typeface.MONOSPACE);
        pill.setTextSize(10);
        pill.setTextColor(COL_VALUE);
        pill.setText("监视器 ▾");
        pill.setBackground(winBg(dp(12)));
        pill.setPadding(dp(10), dp(3), dp(10), dp(3));
        // 长按隐藏过的胶囊启动时不再显示（通知栏「显示菜单」恢复）
        if (!pillHidden) {
            wm.addView(pill, overlayLp(Gravity.TOP | Gravity.START));
            pill.post(() -> {
                try {
                    WindowManager.LayoutParams p = (WindowManager.LayoutParams) pill.getLayoutParams();
                    p.x = Math.max(0, (getResources().getDisplayMetrics().widthPixels - pill.getWidth()) / 2);
                    p.y = dp(2);
                    wm.updateViewLayout(pill, p);
                } catch (Exception ignored) {
                }
            });
        }
        pill.setOnTouchListener(pillTouch);
    }

    /** 长按胶囊：隐藏监视器菜单（通知栏「显示菜单」恢复） */
    private void hidePill() {
        try {
            wm.removeView(pill);
        } catch (Exception ignored) {
        }
        pillHidden = true;
        if (listOpen) toggleList();   // 同步收起功能列表
        getSharedPreferences("colorfc", MODE_PRIVATE).edit()
                .putBoolean("mon_pill_hidden", true).apply();
    }

    /** 通知栏「显示菜单」：恢复胶囊（置顶状态栏居中） */
    private void showPill() {
        pillHidden = false;
        getSharedPreferences("colorfc", MODE_PRIVATE).edit()
                .putBoolean("mon_pill_hidden", false).apply();
        try {
            if (pill.getParent() == null) {
                pill.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                WindowManager.LayoutParams lp = overlayLp(Gravity.TOP | Gravity.START);
                lp.x = Math.max(0, (getResources().getDisplayMetrics().widthPixels - pill.getMeasuredWidth()) / 2);
                lp.y = dp(2);
                wm.addView(pill, lp);
            }
        } catch (Exception ignored) {
        }
        Toast.makeText(this, "监视器菜单已恢复", Toast.LENGTH_SHORT).show();
    }

    /** 药丸触摸：可拖动 + 长按隐藏菜单 + 单击展开列表（与其他窗口手势一致） */
    private final View.OnTouchListener pillTouch = new View.OnTouchListener() {
        private float sx, sy, dx, dy;
        private long downAt;
        private boolean moved, longFired;
        private final Runnable longRun = new Runnable() {
            @Override
            public void run() {
                if (!moved) {
                    longFired = true;
                    hidePill();   // 长按胶囊：隐藏监视器菜单
                }
            }
        };

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            WindowManager.LayoutParams lp = (WindowManager.LayoutParams) v.getLayoutParams();
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    sx = e.getRawX();
                    sy = e.getRawY();
                    dx = sx - lp.x;
                    dy = sy - lp.y;
                    downAt = System.currentTimeMillis();
                    moved = false;
                    longFired = false;
                    ui.postDelayed(longRun, 500);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (Math.abs(e.getRawX() - sx) > dp(12) || Math.abs(e.getRawY() - sy) > dp(12)) {
                        moved = true;
                        ui.removeCallbacks(longRun);
                    }
                    if (!locked && !longFired) {
                        lp.x = (int) (e.getRawX() - dx);
                        lp.y = (int) (e.getRawY() - dy);
                        try {
                            wm.updateViewLayout(v, lp);
                            if (listOpen) positionList();   // 列表跟随药丸移动
                        } catch (Exception ignored) {
                        }
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    ui.removeCallbacks(longRun);
                    if (longFired || moved) return true;
                    if (System.currentTimeMillis() - downAt < 350) toggleList();
                    return true;
            }
            return false;
        }
    };

    private void toggleList() {
        listOpen = !listOpen;
        if (listOpen) {
            syncList();
            WindowManager.LayoutParams lp = overlayLp(Gravity.TOP | Gravity.START);
            lp.x = dp(2);
            lp.y = dp(2);
            wm.addView(listPanel, lp);
            positionList();   // 展开在药丸所在位置正下方
        } else {
            try {
                wm.removeView(listPanel);
            } catch (Exception ignored) {
            }
        }
        pill.setText(listOpen ? "监视器 ▴" : "监视器 ▾");
    }

    /** 列表位置：药丸正下方（水平居中对齐药丸），越界自动夹回屏幕内 */
    private void positionList() {
        try {
            WindowManager.LayoutParams pl = (WindowManager.LayoutParams) pill.getLayoutParams();
            listPanel.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            int lw = listPanel.getMeasuredWidth(), lh = listPanel.getMeasuredHeight();
            int sw = getResources().getDisplayMetrics().widthPixels;
            int sh = getResources().getDisplayMetrics().heightPixels;
            int x = pl.x + pill.getWidth() / 2 - lw / 2;
            if (x + lw > sw - dp(2)) x = sw - dp(2) - lw;
            if (x < dp(2)) x = dp(2);
            int y = pl.y + pill.getHeight() + dp(6);
            if (y + lh > sh - dp(2)) y = Math.max(dp(2), sh - dp(2) - lh);
            WindowManager.LayoutParams ll = (WindowManager.LayoutParams) listPanel.getLayoutParams();
            ll.gravity = Gravity.TOP | Gravity.START;
            ll.x = x;
            ll.y = y;
            wm.updateViewLayout(listPanel, ll);
        } catch (Exception ignored) {
        }
    }

    private void buildListPanel() {
        listPanel = mkBox(dp(10), 10, 8);
        listTitle = mkText("监视器功能", COL_VALUE, 10);
        listTitle.setPadding(0, 0, 0, dp(2));
        listPanel.addView(listTitle);
        for (int i = 0; i < 5; i++) {
            listRows[i] = mkText("", COL_VALUE, 10);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            p.topMargin = dp(3);
            listRows[i].setLayoutParams(p);
            listPanel.addView(listRows[i]);
        }
        listQuit = mkText("关闭监视器", COL_RED, 10);
        LinearLayout.LayoutParams qp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        qp.topMargin = dp(5);
        listQuit.setLayoutParams(qp);
        listPanel.addView(listQuit);

        listPanel.setOnTouchListener((v, e) -> {
            if (e.getAction() != MotionEvent.ACTION_UP) return true;
            if (hit(listTitle, e)) {
                toggleList();
                return true;
            }
            for (int i = 0; i < 5; i++) {
                if (hit(listRows[i], e)) {
                    if (winOpen[i]) hideWin(i);
                    else showWin(i);
                    return true;
                }
            }
            if (hit(listQuit, e)) stopSelf();
            return true;
        });
        syncList();
    }

    /** 功能列表状态同步：✓ 开启（纯白）/ ✗ 关闭（暗灰） */
    private void syncList() {
        for (int i = 0; i < 5; i++) {
            String mark = winOpen[i] ? "✓ " : "✗ ";
            SpannableStringBuilder b = new SpannableStringBuilder();
            int s = b.length();
            b.append(mark);
            b.setSpan(new ForegroundColorSpan(winOpen[i] ? COL_GREEN : 0xFF566373),
                    s, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            s = b.length();
            b.append(MON_LABELS[i]);
            b.setSpan(new ForegroundColorSpan(winOpen[i] ? COL_VALUE : COL_DIM),
                    s, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            listRows[i].setText(b);
        }
    }

    // ==================== 各监视器窗口构建 ====================

    /** 负载监视器：三圆环 + 密集数据行 + 底部 #PWR 条（FPS 独立，不合并） */
    private void buildLoadWin() {
        LinearLayout root = mkBox(dp(14), 10, 8);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        rings = new RingsView();
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rp.rightMargin = dp(12);
        body.addView(rings, rp);

        detailBox = new LinearLayout(this);
        detailBox.setOrientation(LinearLayout.VERTICAL);
        rowRam = mkText("", COL_VALUE, 10);
        rowCpu = mkText("", COL_VALUE, 10);
        clusterBox = new LinearLayout(this);
        clusterBox.setOrientation(LinearLayout.VERTICAL);
        rowGpu = mkText("", COL_VALUE, 10);
        rowBat = mkText("", COL_VALUE, 10);
        detailBox.addView(rowRam);
        detailBox.addView(rowCpu);
        detailBox.addView(clusterBox);
        detailBox.addView(rowGpu);
        detailBox.addView(rowBat);
        body.addView(detailBox);
        root.addView(body);

        applyExpanded();
        win[M_LOAD] = root;
        wlp[M_LOAD] = overlayLp(Gravity.TOP | Gravity.START);
        wlp[M_LOAD].x = dp(14);
        wlp[M_LOAD].y = dp(110);
        root.setOnTouchListener(new WinTouch(M_LOAD));
    }

    private void applyExpanded() {
        rings.setVertical(expanded);
        detailBox.setVisibility(expanded ? View.VISIBLE : View.GONE);
    }

    private void setExpanded(boolean v) {
        if (expanded == v) return;
        expanded = v;
        getSharedPreferences("colorfc", MODE_PRIVATE).edit()
                .putBoolean("mon_expanded", v).apply();
        applyExpanded();
        updateUi();
    }

    /** 帧率记录器：独立窗口，点击开始/停止录制 */
    private void buildFpsWin() {
        LinearLayout root = mkBox(dp(12), 10, 6);
        fpsText = mkText("#FPS --", COL_VALUE, 11);
        root.addView(fpsText);
        win[M_FPS] = root;
        wlp[M_FPS] = overlayLp(Gravity.TOP | Gravity.START);
        wlp[M_FPS].x = dp(14);
        wlp[M_FPS].y = dp(260);
        root.setOnTouchListener(new WinTouch(M_FPS));
    }

    /** 线程监视器：前台应用 CPU 占用 TOP6 线程 */
    private void buildThrWin() {
        LinearLayout root = mkBox(dp(12), 10, 6);
        TextView title = mkText("线程 TOP6", COL_LABEL, 9);
        root.addView(title);
        for (int i = 0; i < 6; i++) {
            thrRows[i] = mkText("--", COL_VALUE, 10);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            p.topMargin = dp(2);
            thrRows[i].setLayoutParams(p);
            root.addView(thrRows[i]);
        }
        win[M_THR] = root;
        wlp[M_THR] = overlayLp(Gravity.TOP | Gravity.START);
        wlp[M_THR].x = dp(14);
        wlp[M_THR].y = dp(340);
        root.setOnTouchListener(new WinTouch(M_THR));
    }

    /** 温度监视器：CPU/SOC/BAT 温度 */
    private void buildTempWin() {
        LinearLayout root = mkBox(dp(12), 10, 6);
        tempText = mkText("--", COL_VALUE, 10);
        root.addView(tempText);
        win[M_TEMP] = root;
        wlp[M_TEMP] = overlayLp(Gravity.TOP | Gravity.START);
        wlp[M_TEMP].x = dp(14);
        wlp[M_TEMP].y = dp(420);
        root.setOnTouchListener(new WinTouch(M_TEMP));
    }

    /** 功耗监视器：仅显示 数字+W */
    private void buildPwrWin() {
        LinearLayout root = mkBox(dp(12), 10, 6);
        pwrBig = mkText("--W", COL_VALUE, 11);
        root.addView(pwrBig);
        win[M_PWR] = root;
        wlp[M_PWR] = overlayLp(Gravity.TOP | Gravity.START);
        wlp[M_PWR].x = dp(14);
        wlp[M_PWR].y = dp(500);
        root.setOnTouchListener(new WinTouch(M_PWR));
    }

    // ==================== 窗口触摸：拖动 + 长按锁定 + 各自点击行为 ====================

    /**
     * 负载监视器：单击 展开/折叠，双击 关闭窗口
     * 帧率记录器：单击 开始/停止录制
     * 线程/温度/功耗监视器：单击无动作（关闭走功能列表）
     * 所有窗口：可拖动（未锁定时），长按 锁定/解锁位置
     */
    private class WinTouch implements View.OnTouchListener {
        private final int idx;
        private float sx, sy, dx, dy;
        private long downAt;
        private boolean moved, longFired;
        private final Runnable longRun = new Runnable() {
            @Override
            public void run() {
                if (!moved) {
                    longFired = true;
                    toggleLock();
                }
            }
        };

        WinTouch(int idx) {
            this.idx = idx;
        }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            WindowManager.LayoutParams lp = (WindowManager.LayoutParams) v.getLayoutParams();
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    sx = e.getRawX();
                    sy = e.getRawY();
                    dx = sx - lp.x;
                    dy = sy - lp.y;
                    downAt = System.currentTimeMillis();
                    moved = false;
                    longFired = false;
                    ui.postDelayed(longRun, 500);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (Math.abs(e.getRawX() - sx) > dp(12) || Math.abs(e.getRawY() - sy) > dp(12)) {
                        moved = true;
                        ui.removeCallbacks(longRun);
                    }
                    if (!locked && !longFired) {
                        lp.x = (int) (e.getRawX() - dx);
                        lp.y = (int) (e.getRawY() - dy);
                        try {
                            wm.updateViewLayout(v, lp);
                        } catch (Exception ignored) {
                        }
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    ui.removeCallbacks(longRun);
                    if (longFired || moved) return true;
                    if (System.currentTimeMillis() - downAt < 350) onTap(e);
                    return true;
            }
            return false;
        }

        private void onTap(MotionEvent e) {
            // 所有窗口统一：双击关闭，单击延迟 300ms 执行各自主行为（防止双击误触发）
            long now = System.currentTimeMillis();
            if (now - lastTapAt < 300) {
                lastTapAt = 0;
                if (pendingTap != null) {
                    ui.removeCallbacks(pendingTap);
                    pendingTap = null;
                }
                hideWin(idx);
                return;
            }
            lastTapAt = now;
            pendingTap = () -> {
                pendingTap = null;
                switch (idx) {
                    case M_LOAD:   // 单击展开/折叠
                        setExpanded(!expanded);
                        break;
                    case M_FPS:    // 单击开始/停止录制
                        toggleRec();
                        break;
                    // M_THR / M_TEMP / M_PWR：单击无动作
                }
            };
            ui.postDelayed(pendingTap, 300);
        }
    }

    /** 长按切换位置锁定（仅当前会话内生效，重启服务后恢复可拖动） */
    private void toggleLock() {
        locked = !locked;
        Toast.makeText(this, locked ? "位置已锁定 · 再次长按解锁" : "位置已解锁，可自由拖动",
                Toast.LENGTH_SHORT).show();
        // 解锁任意窗口时，若菜单胶囊处于隐藏状态则自动恢复
        if (!locked && pillHidden) {
            showPill();
        }
    }

    // ==================== Scene 款三圆环（CPU/GPU/电池） ====================

    private class RingsView extends View {
        RingsView() {
            super(MonitorService.this);
        }

        private boolean vertical = false;
        private float pC, pG, pB;
        private String iC = "--", iG = "--", iB = "--";
        private String sC = "--", sG = "--", sB = "--";
        private final RectF rect = new RectF();

        void setVertical(boolean v) {
            vertical = v;
            requestLayout();
        }

        void data(float c, String ic, String sc,
                  float g, String ig, String sg,
                  float b, String ib, String sb) {
            pC = c; iC = ic; sC = sc;
            pG = g; iG = ig; sG = sg;
            pB = b; iB = ib; sB = sb;
            invalidate();
        }

        @Override
        protected void onMeasure(int wms, int hms) {
            int d = dp(34), gap = dp(12), lab = dp(13);
            if (vertical) {
                setMeasuredDimension(d, 3 * (d + lab) + 2 * dp(8));
            } else {
                setMeasuredDimension(3 * d + 2 * gap, d + lab);
            }
        }

        @Override
        protected void onDraw(Canvas c) {
            int d = dp(34), lab = dp(13), gap = dp(12);
            float stroke = dp(3);
            float r = d / 2f - stroke / 2;

            Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
            track.setStyle(Paint.Style.STROKE);
            track.setStrokeWidth(stroke);
            track.setStrokeCap(Paint.Cap.ROUND);
            track.setColor(0x26FFFFFF);

            Paint prog = new Paint(track);

            Paint inside = new Paint(Paint.ANTI_ALIAS_FLAG);
            inside.setColor(COL_VALUE);
            inside.setTextSize(dp(8));
            inside.setTextAlign(Paint.Align.CENTER);

            Paint sub = new Paint(Paint.ANTI_ALIAS_FLAG);
            sub.setColor(0xFFB0B8C4);
            sub.setTextSize(dp(8.5f));
            sub.setTextAlign(Paint.Align.CENTER);

            float[] pcts = {pC, pG, pB};
            int[] cols = {COL_GREEN, COL_GREEN, pB <= 25 ? COL_ORANGE : COL_GREEN};
            String[] ins = {iC, iG, iB};
            String[] subs = {sC, sG, sB};

            for (int i = 0; i < 3; i++) {
                float cx = vertical ? d / 2f : d / 2f + i * (d + gap);
                float cy = vertical ? d / 2f + i * (d + lab + dp(8)) : d / 2f;
                float top = vertical ? i * (d + lab + dp(8)) : 0;
                rect.set(cx - r, cy - r, cx + r, cy + r);
                c.drawArc(rect, 0, 360, false, track);
                prog.setColor(cols[i]);
                float p = Math.max(0, Math.min(100, pcts[i]));
                if (p > 0.5f) c.drawArc(rect, -90, 3.6f * p, false, prog);
                c.drawText(ins[i], cx, cy + dp(3), inside);
                c.drawText(subs[i], cx, top + d + dp(10), sub);
            }
        }
    }

    // ==================== 界面刷新 ====================

    /** "标签 数值" 两段式行：浅灰标签 + 着色数值 */
    private void setRow(TextView tv, String label, String value, int valueColor) {
        SpannableStringBuilder b = new SpannableStringBuilder();
        int s = b.length();
        b.append(label);
        b.setSpan(new ForegroundColorSpan(COL_LABEL), s, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        b.append(' ');
        s = b.length();
        b.append(value);
        b.setSpan(new ForegroundColorSpan(valueColor), s, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        tv.setText(b);
    }

    private void updateUi() {
        // 负载监视器
        if (winOpen[M_LOAD]) {
            float cpuP = cBusy >= 0 ? (float) cBusy : 0;
            float gpuP = gpuLoad >= 0 ? gpuLoad
                    : (gpuMax > 0 && cGpuM > 0 ? (float) Math.min(100, 100 * cGpuM / gpuMax) : 0);
            float batP = batPct >= 0 ? batPct : 0;
            rings.data(
                    cpuP, String.format(Locale.US, "%.0f", cpuP),
                    cCpuM > 0 ? String.format(Locale.US, "%.0fMHz", cCpuM) : "--",
                    gpuP, String.format(Locale.US, "%.0f", gpuP),
                    cGpuM > 0 ? String.format(Locale.US, "%.0fMHz", cGpuM) : "--",
                    batP, String.format(Locale.US, "%.0f%%", batP),
                    cBatT > 0 ? String.format(Locale.US, "%.1f℃", cBatT) : "--");
            if (expanded) {
                ensureClusterRows();
                setRow(rowRam, "#RAM", ramPct >= 0
                        ? String.format(Locale.US, "%.0f%% · %.1fG", ramPct, ramUsedG) : "--", COL_VALUE);
                setRow(rowCpu, "#CPU", cCpuT > 0
                        ? String.format(Locale.US, "%.1f℃", cCpuT) : "--", tColor(cCpuT));
                TextView[] rows = clusterRows();
                for (int i = 0; i < nCluster && i < rows.length; i++) {
                    setRow(rows[i], "#" + clLbl[i],
                            String.format(Locale.US, "%.0fMHz · %.0f%%", clFreq[i], clBusy[i]), COL_VALUE);
                }
                setRow(rowGpu, "#GPU", gpuLoad >= 0
                        ? String.format(Locale.US, "%.0fMHz · %.0f%%", cGpuM, gpuLoad)
                        : String.format(Locale.US, "%.0fMHz", cGpuM), COL_VALUE);
                setRow(rowBat, "#BAT", batPct >= 0
                        ? String.format(Locale.US, "%.0f%% · %.1f℃", batPct, cBatT) : "--", tColor(cBatT));
            }
        }

        // 功耗监视器：仅 数字+W
        if (winOpen[M_PWR]) {
            pwrBig.setText(cW >= 0 ? String.format(Locale.US, "%.2fW", cW) : "--W");
        }

        // 帧率记录器
        if (winOpen[M_FPS]) {
            SpannableStringBuilder b = new SpannableStringBuilder();
            if (recording) {
                int s = b.length();
                b.append(String.format(Locale.US, "● %02d:%02d",
                        recShown / 60000, (recShown / 1000) % 60));
                b.setSpan(new ForegroundColorSpan(COL_RED), s, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                s = b.length();
                b.append(String.format(Locale.US, " %.1ffps", cHz > 0 ? cHz : 0));
                b.setSpan(new ForegroundColorSpan(COL_VALUE), s, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                int s = b.length();
                b.append("#FPS");
                b.setSpan(new ForegroundColorSpan(COL_LABEL), s, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                b.append(' ');
                s = b.length();
                b.append(cHz > 0 ? String.format(Locale.US, "%.1f", cHz) : "--");
                b.setSpan(new ForegroundColorSpan(COL_VALUE), s, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            fpsText.setText(b);
        }

        // 温度监视器
        if (winOpen[M_TEMP]) {
            SpannableStringBuilder b = new SpannableStringBuilder();
            appendTemp(b, "CPU", cCpuT);
            b.append("  ");
            appendTemp(b, "SOC", cSocT);
            b.append("  ");
            appendTemp(b, "BAT", cBatT);
            tempText.setText(b);
        }
    }

    private void appendTemp(SpannableStringBuilder b, String label, double v) {
        int s = b.length();
        b.append(label);
        b.setSpan(new ForegroundColorSpan(COL_LABEL), s, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        b.append(' ');
        s = b.length();
        b.append(v > 0 ? String.format(Locale.US, "%.1f℃", v) : "--");
        b.setSpan(new ForegroundColorSpan(tColor(v)), s, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /** 温度着色：≥75 红 ≥60 橙 其余 Scene 纯白 */
    private static int tColor(double t) {
        return t >= 75 ? COL_RED : t >= 60 ? 0xFFF59E0B : COL_VALUE;
    }

    private TextView[] clusterRows() {
        int n = clusterBox.getChildCount();
        TextView[] out = new TextView[n];
        for (int i = 0; i < n; i++) out[i] = (TextView) clusterBox.getChildAt(i);
        return out;
    }

    /** 集群行按需创建（集群数量在首次慢速扫描后确定） */
    private void ensureClusterRows() {
        if (rowsBuilt == nCluster || nCluster <= 0) return;
        clusterBox.removeAllViews();
        for (int i = 0; i < nCluster && i < MAX_CL; i++) {
            TextView tv = mkText("--", COL_VALUE, 10);
            clusterBox.addView(tv);
        }
        rowsBuilt = nCluster;
    }

    // ==================== 快速循环（500ms：功耗/电量/CPU/帧率） ====================

    private void fastLoop() {
        new Thread(() -> {
            try {
                PowerMonitor.BatteryStat st = PowerMonitor.readOnce();
                if (st != null) {
                    // 与主页一致的电芯模式修正（显示与记录同步）
                    cW = PowerMonitor.applyCellMode(st, cellMode);
                    cV = st.volts;
                    cA = st.amps;
                    cBatT = st.tempC;
                    st.watts = cW;
                    PowerHistoryManager.record(this, st);
                } else {
                    cW = -1;
                    cV = -1;
                    cA = -1;
                }
            } catch (Exception ignored) {
            }
            readBattery();
            if (winOpen[M_LOAD]) {
                cBusy = readCpuBusy();
                aggClusters(readCores());
            }
            if (winOpen[M_FPS] || recording) cHz = currentFps();
            ui.post(this::updateUi);
            ui.postDelayed(this::fastLoop, 500);
        }).start();
    }

    /** 电量 %：ACTION_BATTERY_CHANGED 粘性广播（免 root） */
    private void readBattery() {
        try {
            Intent b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (b != null) {
                int lvl = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int sc = b.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                if (lvl >= 0 && sc > 0) batPct = 100f * lvl / sc;
            }
        } catch (Exception ignored) {
        }
    }

    /** 按集群聚合每核心占用 % */
    private void aggClusters(float[] coreBusy) {
        if (coreBusy == null) return;
        if (clMap == null || nCluster <= 0) {
            float sum = 0;
            for (float v : coreBusy) sum += v;
            clBusy[0] = coreBusy.length > 0 ? sum / coreBusy.length : 0;
            return;
        }
        for (int c = 0; c < nCluster && c < clMap.length; c++) {
            float sum = 0;
            int n = 0;
            for (int core : clMap[c]) {
                if (core >= 0 && core < coreBusy.length) {
                    sum += coreBusy[core];
                    n++;
                }
            }
            clBusy[c] = n > 0 ? sum / n : 0;
        }
    }

    // ==================== 慢速循环（2s：GPU/CPU 集群/温度/RAM） ====================

    private void slowLoop() {
        new Thread(() -> {
            if (winOpen[M_LOAD] || winOpen[M_TEMP]) {
                String out = "";
                try {
                    RootShell.Result r = RootShell.exec(SCAN);
                    if (r.ok() && r.out != null) out = r.out;
                } catch (Exception ignored) {
                }
                int q = 0;
                for (String line : out.split("\\n")) {
                    if (line.startsWith("G:")) {
                        double g = parseGpu(line);
                        cGpuM = g > 0 ? g / 1e6 : 0;
                    } else if (line.startsWith("GL:")) {
                        gpuLoad = parseGpuLoad(line);
                    } else if (line.startsWith("GM:")) {
                        double m = parseGpuMax(line);
                        if (m > 0) gpuMax = m;
                    } else if (line.startsWith("Q:")) {
                        try {
                            double f = Double.parseDouble(line.substring(2).trim());
                            if (q < MAX_CL) clFreq[q] = f / 1000;   // kHz → MHz
                        } catch (Exception ignored) {
                        }
                        q++;
                    } else if (line.startsWith("R:") && clMap == null) {
                        buildClusterMap(line.substring(2));
                    }
                }
                if (nCluster <= 0) nCluster = Math.min(MAX_CL, Math.max(1, q));
                double max = 0;
                for (int i = 0; i < nCluster; i++) max = Math.max(max, clFreq[i]);
                cCpuM = max;
                double cpuT = parseZoneTemp(out, "cpu", cBatT);
                cCpuT = cpuT;
                cSocT = parseZoneTemp(out, "soc", cpuT);
                readMem();
            }
            ui.post(this::updateUi);
            ui.postDelayed(this::slowLoop, 2000);
        }).start();
    }

    /** R 行解析：related_cpu 拓扑 → 集群映射与标签（如 0-3 / 4-7） */
    private void buildClusterMap(String r) {
        try {
            List<int[]> map = new ArrayList<>();
            List<String> lbl = new ArrayList<>();
            for (String seg : r.split(";")) {
                seg = seg.trim();
                if (seg.isEmpty()) continue;
                String[] cs = seg.split("\\s+");
                int[] cores = new int[cs.length];
                for (int i = 0; i < cs.length; i++) cores[i] = Integer.parseInt(cs[i].trim());
                if (cores.length == 0) continue;
                map.add(cores);
                lbl.add(cores[0] + "-" + cores[cores.length - 1]);
                if (map.size() >= MAX_CL) break;
            }
            if (!map.isEmpty()) {
                clMap = map.toArray(new int[0][]);
                for (int i = 0; i < clMap.length; i++) clLbl[i] = lbl.get(i);
                nCluster = clMap.length;
            }
        } catch (Exception ignored) {
        }
    }

    /** /proc/meminfo → 已用百分比 + 已用 GB */
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
            if (total > 0 && avail >= 0) {
                ramPct = 100f * (total - avail) / total;
                ramUsedG = (total - avail) / 1048576f;
            }
        } catch (Exception ignored) {
        }
    }

    // ==================== 线程监视器循环（250ms 实时） ====================

    private void threadLoop() {
        new Thread(() -> {
            if (winOpen[M_THR]) sampleThreads();
            ui.postDelayed(this::threadLoop, 250);
        }).start();
    }

    /** 前台应用线程 CPU 占用：pid 缓存 + 单次 awk 全量读取（250ms 实时差分） */
    private void sampleThreads() {
        try {
            long now = SystemClock.elapsedRealtime();
            if (thrPid <= 0 || now - thrPidAt > 4000) {   // 前台 pid 每 4 秒解析一次（切换应用自动跟随）
                String rs = "f=$(dumpsys window 2>/dev/null | grep -m1 -E 'mCurrentFocus|mFocusedApp');"
                        + "p=$(echo \"$f\" | grep -oE '[A-Za-z0-9_.]+/' | head -1); p=${p%/};"
                        + "echo \"K:$(getconf CLK_TCK 2>/dev/null)\";"
                        + "pid=$(pidof \"$p\" 2>/dev/null | cut -d' ' -f1); echo \"I:$pid\";";
                RootShell.Result r0 = RootShell.exec(rs, 8);
                if (r0.ok() && r0.out != null) {
                    for (String line : r0.out.split("\\n")) {
                        String t = line.trim();
                        if (t.startsWith("K:")) {
                            try {
                                double k = Double.parseDouble(t.substring(2).trim());
                                if (k > 0) thrTck = k;
                            } catch (Exception ignored) {
                            }
                        } else if (t.startsWith("I:")) {
                            try {
                                thrPid = Integer.parseInt(t.substring(2).trim());
                            } catch (Exception ignored) {
                                thrPid = 0;
                            }
                        }
                    }
                }
                thrPidAt = now;
                if (thrPid <= 0) return;   // 拿不到 pid：保持上一次显示
            }
            String cmd = "if [ -d /proc/" + thrPid + " ]; then echo TZQ7_;"
                    + " awk '{n=split(FILENAME,a,\"/\"); print a[n-1] \" \" $14+$15}' /proc/" + thrPid + "/task/*/stat 2>/dev/null;"
                    + " echo NZQ7_; cat /proc/" + thrPid + "/task/*/comm 2>/dev/null; echo EZQ7_;"
                    + " else echo DEADZQ7_; fi";
            RootShell.Result r = RootShell.exec(cmd, 4);
            if (!r.ok() || r.out == null) return;
            long now2 = SystemClock.elapsedRealtime();
            Map<Integer, long[]> cur = new HashMap<>();
            List<Integer> order = new ArrayList<>();   // stat 输出顺序（升序 tid）
            List<String> names = new ArrayList<>();    // comm 输出顺序（与 stat 同 glob 序）
            int mode = 0;   // 0 头 / 1 stat / 2 comm
            for (String line : r.out.split("\\n")) {
                if (line.equals("TZQ7_")) { mode = 1; continue; }
                if (line.equals("NZQ7_")) { mode = 2; continue; }
                if (line.equals("EZQ7_")) break;
                if (line.equals("DEADZQ7_")) { thrPid = 0; return; }
                if (mode == 1) {
                    int sp = line.indexOf(' ');
                    if (sp <= 0) continue;
                    try {
                        int tid = Integer.parseInt(line.substring(0, sp).trim());
                        long u = Long.parseLong(line.substring(sp + 1).trim());
                        cur.put(tid, new long[]{u});
                        order.add(tid);
                    } catch (Exception ignored) {
                    }
                } else if (mode == 2) {
                    String nm = line.trim();
                    names.add(nm.isEmpty() ? "?" : nm);
                }
            }
            if (cur.isEmpty()) return;
            // 名称按输出顺序与 tid 对位（仅展示用）
            Map<Integer, String> nmOf = new HashMap<>();
            for (int i = 0; i < order.size() && i < names.size(); i++) nmOf.put(order.get(i), names.get(i));
            if (thrPrev != null && now2 > thrLastAt) {
                double dt = (now2 - thrLastAt) / 1000.0;
                List<double[]> top = new ArrayList<>();   // {pct, tid}
                // 逐线程差分：同 tid 对位，按真实采样间隔折算占用率
                for (Map.Entry<Integer, long[]> en : cur.entrySet()) {
                    long[] prev = thrPrev.get(en.getKey());
                    if (prev == null) continue;
                    long d = en.getValue()[0] - prev[0];
                    if (d <= 0) continue;
                    double pct = Math.min(999, d / thrTck / dt * 100);
                    top.add(new double[]{pct, en.getKey()});
                }
                top.sort(Comparator.comparingDouble(a -> -a[0]));
                List<double[]> pick = top.subList(0, Math.min(6, top.size()));
                final String[] txt = new String[6];
                for (int i = 0; i < 6; i++) {
                    if (i < pick.size()) {
                        double pct = pick.get(i)[0];
                        String nm = nmOf.get((int) pick.get(i)[1]);
                        if (nm == null) nm = "?";
                        if (nm.length() > 14) nm = nm.substring(0, 14);
                        txt[i] = String.format(Locale.US, "%s %.0f%%", nm, pct);
                    } else txt[i] = null;
                }
                ui.post(() -> {
                    for (int i = 0; i < 6; i++) {
                        if (txt[i] == null) continue;   // 无增量数据：保持上一次显示，不回落 "--"
                        {
                            int sp = txt[i].lastIndexOf(' ');
                            SpannableStringBuilder b = new SpannableStringBuilder();
                            int s = b.length();
                            b.append(txt[i].substring(0, sp));
                            b.setSpan(new ForegroundColorSpan(COL_LABEL), s, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                            b.append(' ');
                            s = b.length();
                            b.append(txt[i].substring(sp + 1));
                            double pct = Double.parseDouble(txt[i].substring(sp + 1).replace("%", ""));
                            b.setSpan(new ForegroundColorSpan(
                                            pct >= 80 ? COL_RED : pct >= 50 ? 0xFFF59E0B : COL_VALUE),
                                    s, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                            thrRows[i].setText(b);
                        }
                    }
                });
            }
            thrPrev = cur;
            thrLastAt = now2;
        } catch (Exception ignored) {
        }
    }

    // ==================== 帧率录制（500ms，最短 3 秒） ====================

    private void toggleRec() {
        if (!recording) {
            recording = true;
            recStart = System.currentTimeMillis();
            recShown = 0;
            ts.clear();
            fps.clear();
            cpuTot.clear();
            cores.clear();
            recLastIdle = -1;
            recLastTotal = -1;
            lastCoreIdle = null;
            Toast.makeText(this, "开始录制（至少 3 秒），再次点击停止并保存", Toast.LENGTH_SHORT).show();
            recSample();
        } else {
            stopRec(true);
        }
    }

    private void stopRec(boolean toast) {
        recording = false;
        long dur = System.currentTimeMillis() - recStart;
        int n = ts.size();
        if (dur < MIN_REC_MS || n < 4) {
            if (toast) Toast.makeText(this, "录制不足 3 秒，未保存", Toast.LENGTH_SHORT).show();
            return;
        }
        if (toast) Toast.makeText(this, "录制完成，正在生成曲线图…", Toast.LENGTH_SHORT).show();
        final int fn = n;
        new Thread(() -> saveChart(fn)).start();
    }

    private void recSample() {
        if (!recording) return;
        new Thread(() -> {
            float hz = currentFps();
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
            cHz = hz;
            recShown = now - recStart;
            ui.post(this::updateUi);
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
        c.drawText("实时帧率 (fps)", x0, y1 + 34, hp);
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

    /** /proc/stat 首行差分 → CPU 总占用 %（快速循环） */
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

    /** GPU 负载 %：gpu_load / gpu_busy_percentage / gpuload（>100 视作十分位百分比） */
    private float parseGpuLoad(String line) {
        try {
            String s = line.substring(3).trim().replace("%", "");
            s = s.replaceAll("[^0-9.].*$", "").trim();
            if (s.isEmpty()) return -1;
            float v = Float.parseFloat(s);
            if (v > 100 && v <= 1000) v /= 10f;
            return Math.max(0, Math.min(100, v));
        } catch (Exception ignored) {
        }
        return -1;
    }

    /** GPU 最大频率（MHz）：<3000 视作 MHz，<1e6 视作 kHz，其余视作 Hz */
    private double parseGpuMax(String line) {
        try {
            double v = Double.parseDouble(line.substring(3).trim());
            if (v <= 0) return 0;
            if (v < 3000) return v;
            if (v < 1_000_000) return v / 1000;
            return v / 1e6;
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

    // ==================== 实时帧率（真实渲染帧率，非面板刷新率） ====================

    /**
     * 实时帧率（增量差分：两次轮询间新增帧 ÷ 跨越时长，停止渲染即归零）：
     * 1. SurfaceFlinger --latency：前台图层新增帧（游戏 SurfaceView 也计入）
     * 2. dumpsys gfxinfo：前台应用总渲染帧数差分（--latency 无数据时）
     * 全部 shell 工作合并为单次调用（FPS_CMD），降低偶发失败与延迟；
     * 返回 null = 本次检测失败（命令异常/无焦点），0 = 确认空闲
     */
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
                if (!afterL) {                 // L 之前的纯数字行 = gfxinfo 帧计数
                    long v = parseNs(t);
                    if (v >= 0) gfx = v;
                    continue;
                }
                // SF latency 行：取第二列 actual_present_time
                String[] col = t.split("\\s+");
                long ts = col.length >= 2 ? parseNs(col[1]) : parseNs(col[0]);
                if (ts < 1_000_000_000L || ts >= Long.MAX_VALUE / 2) continue;
                if (ts > newest) newest = ts;
                // 去重：掉帧/持帧时 SF 会按 vsync 重复写入同一 actual_present_time，
                // 重复时间戳不计入帧数，防止瞬时算出超过面板刷新率的值
                if (sfPrevNewest > 0 && ts > sfPrevNewest && ts != lastTs) {
                    fresh++;
                    lastTs = ts;
                }
            }
            if (pkg != null && pkg.isEmpty()) pkg = null;
            if (layer != null && layer.isEmpty()) layer = null;

            // 图层或应用变化：重置差分基准，防止跨应用误算
            boolean changed = !eq(layer, fpsLayer) || !eq(pkg, fpsPkg);
            if (changed) {
                sfPrevNewest = -1;
                gfxFrames = -1;
            }
            fpsLayer = layer;
            fpsPkg = pkg;

            if (layer != null && newest > 0) {
                if (sfPrevNewest <= 0) {          // 首次：仅建立基准，本轮用 gfx 兜底
                    sfPrevNewest = newest;
                } else if (newest <= sfPrevNewest) {
                    return 0f;                    // 本轮无新帧 → 确认空闲
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
                gfxFrames = gfx;                  // 首次或计数回绕：仅建立基准
                gfxAt = now;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    /**
     * 检测失败或空闲（无新帧）时保持上一有效值——首次成功后不再回落 "--"，
     * 仅在从未取得过有效帧率时显示 "--"
     */
    private float currentFps() {
        Float f = realFps();
        if (f != null && f > 0) {
            lastGoodHz = f;
            lastGoodAt = SystemClock.elapsedRealtime();
            return f;
        }
        return lastGoodAt > 0 ? lastGoodHz : 0f;
    }

    /** 安全解析纳秒时间戳 */
    private static long parseNs(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private Notification notif() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel("monitor",
                "负载监视器", NotificationManager.IMPORTANCE_LOW);
        nm.createNotificationChannel(ch);
        PendingIntent close = PendingIntent.getService(this, 1,
                new Intent(this, MonitorService.class).setAction("stop"),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent show = PendingIntent.getService(this, 2,
                new Intent(this, MonitorService.class).setAction("show_pill"),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, "monitor")
                .setContentTitle("监视器功能运行中")
                .setContentText("点击「监视器」展开功能 · 长按胶囊隐藏 · 长按窗口锁定位置")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .addAction(new Notification.Action.Builder(null, "显示菜单", show).build())
                .addAction(new Notification.Action.Builder(null, "关闭", close).build())
                .setOngoing(true)
                .build();
    }
}
