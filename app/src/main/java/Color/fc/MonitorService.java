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
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 迷你悬浮窗监视器（菜单 + 独立窗架构）：
 * - ≡ 胶囊：点击展开悬浮窗菜单；菜单内逐项开/关独立悬浮窗（位置独立、可拖动、✕ 单独关闭）
 * - 独立窗：功耗（仅实时功耗）/ CPU / GPU / 温度 / 实时帧率（含 ● 录制，最短 3 秒）
 * - 菜单内 "位置锁定"：锁定后窗口不可拖动且隐藏菜单胶囊，点击任意窗口唤出菜单解锁
 * - 功耗 500ms 快速刷新（与主页电芯模式同步），GPU/温度 2s 慢速扫描；功耗记录后台常采
 * - 帧率：SurfaceFlinger 帧呈现时间戳 → 真实实时帧率（gfxinfo 帧数差分兜底）
 * - 菜单底部 "✕ 关闭监视器" 可停止服务；通知栏也可关闭
 */
public class MonitorService extends Service {

    /** 主页开关状态同步用 */
    public static volatile boolean running = false;
    /** 录制最短时长 */
    private static final long MIN_REC_MS = 3000;

    private WindowManager wm;
    private final Handler ui = new Handler(Looper.getMainLooper());
    /** 电芯模式（与主页同步）：0=自动校准 1=强制单电芯 2=强制双电芯 */
    private int cellMode = 0;

    // ===== 菜单胶囊与菜单 =====
    private TextView pill;
    private LinearLayout menu;
    private WindowManager.LayoutParams pillLp, menuLp;
    private TextView menuClose, menuQuit, menuLock;
    private final TextView[] menuRows = new TextView[5];
    private boolean menuOpen = false;
    /** 位置锁定：锁定后窗口不可拖动且隐藏菜单胶囊，点击任意窗口唤出菜单 */
    private boolean locked = false;

    // ===== 5 个独立悬浮窗: 0功耗 1CPU 2GPU 3温度 4帧率 =====
    private static final String[] WIN_KEYS = {"win_power", "win_cpu", "win_gpu", "win_temp", "win_fps"};
    private static final String[] WIN_LABELS = {"功耗", "CPU", "GPU", "温度", "帧率"};
    private static final int[] WIN_COLORS = {0xFF00E5FF, 0xFFE6EDF3, 0xFFE6EDF3, 0xFFE6EDF3, 0xFF8B949E};
    private final boolean[] winOpen = new boolean[5];
    private final LinearLayout[] winBox = new LinearLayout[5];
    private final TextView[] winText = new TextView[5];
    private final WindowManager.LayoutParams[] winLp = new WindowManager.LayoutParams[5];
    /** 帧率窗的录制按钮 */
    private TextView tvRec;

    // ===== 显示缓存（工作线程写 / UI 读） =====
    private volatile double cW = -1;       // 功耗 W（已按电芯模式修正）
    private volatile double cBusy = -1;    // CPU 总占用 %
    private volatile double cCpuM = 0;     // CPU 最高频 MHz
    private volatile double cGpuM = 0;     // GPU 频率 MHz
    private volatile double cCpuT = 0, cSocT = 0, cBatT = 0;
    private volatile float cHz = 0;
    private volatile long recShown = 0;     // 录制已进行时长 ms

    // CPU 占用差分基准（快速循环）
    private long lastIdle = -1, lastTotal = -1;
    // 录制 500ms 循环基准
    private long recLastIdle = -1, recLastTotal = -1;
    private long[] lastCoreIdle, lastCoreTotal;

    // ===== 实时帧率采样缓存 =====
    private String fpsLayer, fpsPkg;   // 前台应用图层（约 5s 刷新一次）
    private long layerAt;
    private long gfxFrames = -1;        // gfxinfo 最近总渲染帧数（差分基准）
    private long gfxAt;

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
        SharedPreferences p = getSharedPreferences("colorfc", MODE_PRIVATE);
        cellMode = p.getInt("cellMode", 0);
        for (int i = 0; i < winOpen.length; i++) winOpen[i] = p.getBoolean(WIN_KEYS[i], i == 0);
        locked = p.getBoolean("win_locked", false);
        startForeground(1, notif());
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        buildPill();
        for (int i = 0; i < winOpen.length; i++) {
            if (winOpen[i]) addWindow(i);
        }
        if (locked && anyWindowOpen()) hidePill();   // 锁定状态启动时隐藏菜单胶囊
        ui.postDelayed(this::fastLoop, 200);
        ui.postDelayed(this::slowLoop, 800);
        ui.postDelayed(this::historyLoop, 5000);
    }

    @Override
    public void onDestroy() {
        running = false;
        ui.removeCallbacksAndMessages(null);
        if (recording) stopRec(false);
        closeMenu();
        for (int i = 0; i < winOpen.length; i++) {
            if (winOpen[i]) {
                try {
                    wm.removeView(winBox[i]);
                } catch (Exception ignored) {
                }
                winOpen[i] = false;
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

    // ==================== 通用拖动+点击触摸 ====================

    private interface Tap {
        void tap(View v, MotionEvent e);
    }

    /** 拖动 + 单击（可选拖动回调）；lockable 的视图在锁定状态下不响应拖动但仍可点击 */
    private class DragTouch implements View.OnTouchListener {
        private final Tap tap;
        private final Runnable onMoved;
        private final boolean lockable;
        private float sx, sy, dx, dy;
        private long downAt;
        private boolean moved = false;

        DragTouch(Tap tap, Runnable onMoved, boolean lockable) {
            this.tap = tap;
            this.onMoved = onMoved;
            this.lockable = lockable;
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
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (Math.abs(e.getRawX() - sx) > dp(12) || Math.abs(e.getRawY() - sy) > dp(12)) {
                        if (!moved && onMoved != null) onMoved.run();
                        moved = true;
                    }
                    if (lockable && locked) return true;   // 锁定：位置不动
                    lp.x = (int) (e.getRawX() - dx);
                    lp.y = (int) (e.getRawY() - dy);
                    try {
                        wm.updateViewLayout(v, lp);
                    } catch (Exception ignored) {
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!moved && System.currentTimeMillis() - downAt < 350 && tap != null) tap.tap(v, e);
                    return true;
            }
            return false;
        }
    }

    private boolean hit(TextView v, MotionEvent e) {
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        return e.getRawX() >= loc[0] && e.getRawX() <= loc[0] + v.getWidth()
                && e.getRawY() >= loc[1] && e.getRawY() <= loc[1] + v.getHeight();
    }

    // ==================== 菜单胶囊 ====================

    private void buildPill() {
        pill = new TextView(this);
        pill.setTextColor(0xFF00E5FF);
        pill.setTextSize(13);
        pill.setText("≡");
        pill.setBackground(winBg(dp(14)));
        pill.setPadding(dp(9), dp(2), dp(9), dp(3));
        pillLp = overlayLp();
        pillLp.x = dp(12);
        pillLp.y = dp(120);
        pill.setOnTouchListener(new DragTouch((v, e) -> toggleMenu(), this::closeMenu, true));
        wm.addView(pill, pillLp);
    }

    private void toggleMenu() {
        if (menuOpen) closeMenu();
        else openMenuAt(pill);
    }

    /** 在锚点视图下方弹出菜单（锁定状态下由窗口点击唤起） */
    private void openMenuAt(View anchor) {
        if (menuOpen) return;
        if (menu == null) buildMenu();
        WindowManager.LayoutParams alp = (WindowManager.LayoutParams) anchor.getLayoutParams();
        menuLp = overlayLp();
        menuLp.x = alp.x;
        menuLp.y = alp.y + dp(44);
        wm.addView(menu, menuLp);
        menuOpen = true;
        updateMenu();
    }

    private void closeMenu() {
        if (!menuOpen || menu == null) return;
        try {
            wm.removeView(menu);
        } catch (Exception ignored) {
        }
        menuOpen = false;
    }

    private void buildMenu() {
        menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setBackground(menuBg());
        menu.setPadding(dp(9), dp(3), dp(9), dp(5));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        TextView title = new TextView(this);
        title.setTextColor(0xFF6B7785);
        title.setTextSize(10);
        title.setText("悬浮窗菜单");
        head.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        menuClose = new TextView(this);
        menuClose.setTextColor(0xFF8B949E);
        menuClose.setTextSize(11);
        menuClose.setText("✕");
        menuClose.setPadding(dp(5), dp(1), dp(1), dp(1));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cp.leftMargin = dp(8);
        head.addView(menuClose, cp);
        menu.addView(head);

        for (int i = 0; i < menuRows.length; i++) {
            menuRows[i] = new TextView(this);
            menuRows[i].setTextSize(11);
            menuRows[i].setTypeface(Typeface.MONOSPACE);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            p.topMargin = dp(2);
            menu.addView(menuRows[i], p);
        }

        menuLock = new TextView(this);
        menuLock.setTextSize(11);
        menuLock.setTypeface(Typeface.MONOSPACE);
        LinearLayout.LayoutParams kl = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        kl.topMargin = dp(4);
        menu.addView(menuLock, kl);

        menuQuit = new TextView(this);
        menuQuit.setTextColor(0xFFEF4444);
        menuQuit.setTextSize(10);
        menuQuit.setText("✕ 关闭监视器");
        LinearLayout.LayoutParams qp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        qp.topMargin = dp(9);
        menu.addView(menuQuit, qp);

        menu.setOnTouchListener(new DragTouch((v, e) -> {
            if (hit(menuClose, e)) {
                closeMenu();
                return;
            }
            if (hit(menuQuit, e)) {
                stopSelf();
                return;
            }
            if (hit(menuLock, e)) {
                toggleLock();
                return;
            }
            for (int i = 0; i < menuRows.length; i++) {
                if (hit(menuRows[i], e)) {
                    toggleWindow(i);
                    return;
                }
            }
        }, null, false));
    }

    /** 菜单行状态刷新：已开启 淡蓝色 / 未开启 暗红色 */
    private void updateMenu() {
        if (!menuOpen) return;
        for (int i = 0; i < menuRows.length; i++) {
            menuRows[i].setText((winOpen[i] ? "✓ " : "✗ ") + WIN_LABELS[i]);
            menuRows[i].setTextColor(winOpen[i] ? 0xFF7DD3FC : 0xFFB91C1C);
        }
        menuLock.setText(locked ? "✓ 位置已锁定" : "✗ 位置未锁定");
        menuLock.setTextColor(locked ? 0xFF7DD3FC : 0xFFB91C1C);
    }

    /** 锁定/解锁位置：锁定后窗口不可拖动并隐藏菜单胶囊，点击任意窗口唤出菜单 */
    private void toggleLock() {
        locked = !locked;
        getSharedPreferences("colorfc", MODE_PRIVATE).edit().putBoolean("win_locked", locked).apply();
        if (locked) {
            closeMenu();
            if (anyWindowOpen()) hidePill();
            Toast.makeText(this, "已锁定 · 点击任意悬浮窗唤出菜单", Toast.LENGTH_SHORT).show();
        } else {
            showPill();
            updateMenu();
            Toast.makeText(this, "已解锁，可自由拖动", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean anyWindowOpen() {
        for (boolean b : winOpen) if (b) return true;
        return false;
    }

    private void hidePill() {
        if (pill == null) return;
        try {
            wm.removeView(pill);
        } catch (Exception ignored) {
        }
    }

    private void showPill() {
        if (pill == null) return;
        try {
            wm.addView(pill, pill.getLayoutParams());
        } catch (Exception ignored) {
        }
    }

    // ==================== 独立悬浮窗 ====================

    /** 监视窗背景：半透明深色 + 青色描边 */
    private GradientDrawable winBg(int radius) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xC0101820);
        bg.setStroke(1, 0x5000E5FF);
        bg.setCornerRadius(radius);
        return bg;
    }

    /** 菜单背景 */
    private GradientDrawable menuBg() {
        return winBg(dp(10));
    }

    private WindowManager.LayoutParams overlayLp() {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        return lp;
    }

    private void toggleWindow(int i) {
        if (winOpen[i]) closeWindow(i);
        else openWindow(i);
    }

    private void openWindow(int i) {
        if (winOpen[i]) return;
        addWindow(i);
        winOpen[i] = true;
        saveWins();
        updateMenu();
        updateAll();
    }

    private void closeWindow(int i) {
        if (!winOpen[i]) return;
        if (i == 4 && recording) stopRec(false);   // 关闭帧率窗时结束录制
        try {
            wm.removeView(winBox[i]);
        } catch (Exception ignored) {
        }
        winOpen[i] = false;
        saveWins();
        updateMenu();
        // 锁定时关掉全部窗口则恢复菜单胶囊，避免无法唤出菜单
        if (locked && !anyWindowOpen()) showPill();
    }

    private void addWindow(int i) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setBackground(winBg(dp(10)));
        box.setPadding(dp(8), dp(3), dp(6), dp(3));

        final TextView close = new TextView(this);
        if (i == 4) {
            tvRec = new TextView(this);
            tvRec.setTextColor(0xFF00E5FF);
            tvRec.setTextSize(11);
            tvRec.setText("●");
            tvRec.setPadding(dp(1), dp(1), dp(4), dp(1));
            box.addView(tvRec, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        winText[i] = new TextView(this);
        winText[i].setTextColor(WIN_COLORS[i]);
        winText[i].setTextSize(11);
        winText[i].setTypeface(Typeface.MONOSPACE);
        box.addView(winText[i], new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        close.setTextColor(0xFF8B949E);
        close.setTextSize(11);
        close.setText("✕");
        close.setPadding(dp(6), dp(1), dp(1), dp(1));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cp.leftMargin = dp(4);
        box.addView(close, cp);

        winLp[i] = overlayLp();
        winLp[i].x = dp(16) + i * dp(10);
        winLp[i].y = dp(170) + i * dp(40);
        final int idx = i;
        box.setOnTouchListener(new DragTouch((v, e) -> {
            if (hit(close, e)) {
                closeWindow(idx);
                return;
            }
            if (idx == 4 && hit(tvRec, e)) {
                toggleRec();
                return;
            }
            // 锁定状态下点击窗口主体唤出菜单（可解锁/开关其他窗）
            if (locked) openMenuAt(v);
        }, null, true));
        winBox[i] = box;
        wm.addView(box, winLp[i]);
        winText[i].setText(WIN_LABELS[i] + " --");
    }

    private void saveWins() {
        SharedPreferences.Editor ed = getSharedPreferences("colorfc", MODE_PRIVATE).edit();
        for (int i = 0; i < winOpen.length; i++) ed.putBoolean(WIN_KEYS[i], winOpen[i]);
        ed.apply();
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ==================== 界面刷新 ====================

    /** 追加一个着色片段，非首个片段前自动加 " · " 分隔 */
    private void item(SpannableStringBuilder b, String txt, int color) {
        if (b.length() > 0) {
            int s = b.length();
            b.append(" · ");
            b.setSpan(new ForegroundColorSpan(0xFF566373), s, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        int s = b.length();
        b.append(txt);
        b.setSpan(new ForegroundColorSpan(color), s, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /** 温度着色：≥75 红 ≥60 橙 其余白 */
    private static int tColor(double t) {
        return t >= 75 ? 0xFFEF4444 : t >= 60 ? 0xFFF59E0B : 0xFFE6EDF3;
    }

    private void updateAll() {
        if (winOpen[0]) winText[0].setText(cW >= 0
                ? String.format(Locale.US, "功耗 %.2fW", cW) : "功耗 --");
        if (winOpen[1]) {
            SpannableStringBuilder b = new SpannableStringBuilder();
            item(b, "CPU " + (cBusy >= 0 ? String.format(Locale.US, "%.0f%%", cBusy) : "--"), 0xFFE6EDF3);
            if (cCpuM > 0) item(b, String.format(Locale.US, "%.0fMHz", cCpuM), 0xFF8B949E);
            winText[1].setText(b);
        }
        if (winOpen[2]) winText[2].setText(cGpuM > 0
                ? String.format(Locale.US, "GPU %.0fMHz", cGpuM) : "GPU --");
        if (winOpen[3]) {
            SpannableStringBuilder b = new SpannableStringBuilder();
            item(b, String.format(Locale.US, "CPU温度 %.1f℃", cCpuT), tColor(cCpuT));
            item(b, String.format(Locale.US, "SOC温度 %.1f℃", cSocT), tColor(cSocT));
            winText[3].setText(b);
        }
        if (winOpen[4]) {
            if (recording) {
                SpannableStringBuilder b = new SpannableStringBuilder();
                item(b, String.format(Locale.US, "● %02d:%02d", recShown / 60000, (recShown / 1000) % 60), 0xFFEF4444);
                item(b, String.format(Locale.US, "%.1ffps", cHz), 0xFF8B949E);
                winText[4].setText(b);
            } else {
                winText[4].setText(cHz > 0 ? String.format(Locale.US, "%.1ffps", cHz) : "--fps");
            }
        }
    }

    // ==================== 快速循环（500ms：功耗/CPU/帧率 实时） ====================

    private void fastLoop() {
        boolean needPower = winOpen[0];
        boolean needCpu = winOpen[1] || recording;
        boolean needHz = winOpen[4] || recording;
        if (needPower || needCpu || needHz) {
            new Thread(() -> {
                if (needPower) {
                    PowerMonitor.BatteryStat st = null;
                    try {
                        st = PowerMonitor.readOnce();
                    } catch (Exception ignored) {
                    }
                    if (st != null) {
                        // 与主页一致的电芯模式修正（显示与记录同步）
                        cW = PowerMonitor.applyCellMode(st, cellMode);
                        cBatT = st.tempC;
                        // 功耗历史记录（内部节流），按修正后功耗写入
                        st.watts = cW;
                        PowerHistoryManager.record(this, st);
                    } else {
                        cW = -1;
                    }
                }
                if (needCpu) cBusy = readCpuBusy();
                if (needHz && !recording) cHz = realFps();
                ui.post(this::updateAll);
                ui.postDelayed(this::fastLoop, 500);
            }).start();
        } else {
            ui.postDelayed(this::fastLoop, 500);
        }
    }

    // ==================== 慢速循环（2s：GPU/CPU 频率/温度） ====================

    private void slowLoop() {
        if (winOpen[1] || winOpen[2] || winOpen[3]) {
            new Thread(() -> {
                String out = "";
                try {
                    RootShell.Result r = RootShell.exec(SCAN);
                    if (r.ok() && r.out != null) out = r.out;
                } catch (Exception ignored) {
                }
                double gpuHz = parseGpu(out);
                cGpuM = gpuHz > 0 ? gpuHz / 1e6 : 0;
                cCpuM = parseFreq(out) / 1000;
                double cpuT = parseZoneTemp(out, "cpu", cBatT);
                cCpuT = cpuT;
                cSocT = parseZoneTemp(out, "soc", cpuT);
                ui.post(this::updateAll);
                ui.postDelayed(this::slowLoop, 2000);
            }).start();
        } else {
            ui.postDelayed(this::slowLoop, 2000);
        }
    }

    // ==================== 后台功耗记录（功耗窗关闭时仍采样） ====================

    private void historyLoop() {
        if (!winOpen[0]) {   // 功耗窗开启时 fastLoop 已高频记录
            new Thread(() -> {
                try {
                    PowerMonitor.BatteryStat st = PowerMonitor.readOnce();
                    if (st != null) {
                        // 记录功耗按主页电芯模式修正（与显示一致）
                        st.watts = PowerMonitor.applyCellMode(st, cellMode);
                        PowerHistoryManager.record(this, st);
                    }
                } catch (Exception ignored) {
                }
                ui.postDelayed(this::historyLoop, 10_000);
            }).start();
        } else {
            ui.postDelayed(this::historyLoop, 10_000);
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
            if (tvRec != null) {
                tvRec.setText("■");
                tvRec.setTextColor(0xFFEF4444);
            }
            Toast.makeText(this, "开始录制（至少 3 秒）", Toast.LENGTH_SHORT).show();
            recSample();
        } else {
            stopRec(true);
        }
    }

    private void stopRec(boolean toast) {
        recording = false;
        if (tvRec != null) {
            tvRec.setText("●");
            tvRec.setTextColor(0xFF00E5FF);
        }
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
            float hz = realFps();
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
            ui.post(this::updateAll);
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

    // ==================== 实时帧率（真实渲染帧率，非面板刷新率） ====================

    /**
     * 实时帧率三级策略（全部为实测渲染帧率，绝不显示面板刷新率档位）：
     * 1. SurfaceFlinger --latency：前台图层的帧呈现时间戳 → 最近窗口真实 fps（游戏 SurfaceView 也计入）
     * 2. dumpsys gfxinfo：前台应用总渲染帧数差分（部分系统移除了 --latency 时）
     * 3. dumpsys gfxinfo framestats：PROFILEDATA 帧完成时间戳差分
     * 全部失败返回 0，界面显示 "--fps"
     */
    private float realFps() {
        Float f = sfFps();
        if (f == null) f = gfxFps();
        if (f == null) f = gfxFrameStatsFps();
        return f != null ? f : 0f;
    }

    /** dumpsys gfxinfo framestats：PROFILEDATA 帧完成时间戳差分 → 实时 fps（第三兜底） */
    private Float gfxFrameStatsFps() {
        if (fpsPkg == null) return null;
        try {
            RootShell.Result r = RootShell.exec(
                    "dumpsys gfxinfo " + fpsPkg + " framestats 2>/dev/null", 8);
            if (!r.ok() || r.out == null) return null;
            long min = Long.MAX_VALUE, max = 0;
            int n = 0;
            for (String l : r.out.split("\\n")) {
                if (!l.matches("\\d+(,\\d+)+")) continue;
                String[] c = l.split(",");
                if (c.length < 16) continue;
                try {
                    long t = Long.parseLong(c[14]);   // FrameCompleted（纳秒）
                    if (t <= 0) continue;
                    n++;
                    if (t < min) min = t;
                    if (t > max) max = t;
                } catch (NumberFormatException ignored) {
                }
            }
            if (n >= 2 && max > min) {
                double span = (max - min) / 1e9;
                if (span >= 0.05 && span <= 30) {
                    return Math.max(1f, Math.min(240f, (float) ((n - 1) / span)));
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 刷新前台应用图层缓存（约 5 秒一次）：前台包名 + SF 图层列表择优 */
    private void refreshLayer() {
        long now = SystemClock.elapsedRealtime();
        if (fpsLayer != null && now - layerAt < 5000) return;
        try {
            RootShell.Result r = RootShell.exec(
                    "f=$(dumpsys window 2>/dev/null | grep -m1 -E 'mCurrentFocus|mFocusedApp');"
                            + "p=$(echo \"$f\" | grep -oE '[a-z0-9_.]+/' | head -1);"
                            + "echo \"P:${p%/}\";"
                            + "dumpsys SurfaceFlinger --list 2>/dev/null", 8);
            if (!r.ok() || r.out == null) return;
            String[] lines = r.out.split("\\n");
            String pkg = null;
            int start = 0;
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].startsWith("P:")) {
                    pkg = lines[i].substring(2).trim();
                    start = i + 1;
                    break;
                }
            }
            if (pkg == null || pkg.isEmpty()) return;
            // 择优：含包名的 SurfaceView 图层（游戏帧）优先，否则首个该应用图层
            String best = null;
            for (int i = start; i < lines.length; i++) {
                String l = lines[i].trim();
                if (l.isEmpty() || !l.contains(pkg)) continue;
                if (l.contains("SurfaceView")) {
                    best = l;
                    break;
                }
                if (best == null) best = l;
            }
            if (best != null) {
                fpsLayer = best;
                fpsPkg = pkg;
                layerAt = now;
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * SurfaceFlinger --latency：读取图层最近呈现的帧时间戳（纳秒，环形缓冲约 127 帧），
     * fps = 帧数 / 时间跨度。头部为刷新周期值、尾部为 0，用阈值过滤
     */
    private Float sfFps() {
        refreshLayer();
        if (fpsLayer == null) return null;
        try {
            String safe = fpsLayer.replace("'", "'\\''");
            RootShell.Result r = RootShell.exec(
                    "dumpsys SurfaceFlinger --latency '" + safe + "' 2>/dev/null", 8);
            if (!r.ok() || r.out == null) return null;
            long min = Long.MAX_VALUE, max = 0;
            int n = 0;
            for (String l : r.out.split("\\n")) {
                l = l.trim();
                if (l.length() < 10 || !l.matches("\\d+")) continue;
                long t = Long.parseLong(l);
                if (t < 1_000_000_000L) continue;   // 过滤刷新周期(µs/ms 级)与结尾 0
                n++;
                if (t < min) min = t;
                if (t > max) max = t;
            }
            if (n >= 2 && max > min) {
                double span = (max - min) / 1e9;
                if (span >= 0.05) {
                    return Math.max(1f, Math.min(240f, (float) ((n - 1) / span)));
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** dumpsys gfxinfo 前台应用总渲染帧数差分 → 实时 fps（--latency 不可用时的兜底） */
    private Float gfxFps() {
        if (fpsPkg == null) return null;
        try {
            RootShell.Result r = RootShell.exec(
                    "dumpsys gfxinfo " + fpsPkg + " 2>/dev/null | grep -m1 'Total frames rendered'", 8);
            if (!r.ok() || r.out == null) return null;
            int i = r.out.lastIndexOf(' ');
            long cur = Long.parseLong(r.out.substring(i + 1).trim());
            long now = SystemClock.uptimeMillis();
            if (gfxFrames >= 0 && cur > gfxFrames) {
                float fps = 1000f * (cur - gfxFrames) / Math.max(1, now - gfxAt);
                gfxFrames = cur;
                gfxAt = now;
                return Math.max(1f, Math.min(240f, fps));
            }
            gfxFrames = cur;   // 首次或计数回绕：只记录基准
            gfxAt = now;
        } catch (Exception ignored) {
        }
        return null;
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
                .setContentText("≡ 菜单 · 锁定后点击任意悬浮窗唤出菜单")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .addAction(new Notification.Action.Builder(null, "关闭", close).build())
                .setOngoing(true)
                .build();
    }
}
