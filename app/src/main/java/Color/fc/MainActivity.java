package Color.fc;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.Locale;

import Color.fc.view.SparkView;
import Color.fc.view.PowerCurveView;
import Color.fc.view.Warp;

/**
 * 主页：实时功耗（自动校准单/双电芯）+ 四模式快切 + CPU 核心 + 功能入口。
 * 模式切换与 WebUI 同机制：main.sh 立即应用 + moren= 持久化为默认模式
 * （不写 moren 会被前台监视 qtbh.sh 在下次前台变化时切回省电）。
 */
public class MainActivity extends ThemedActivity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** 悬浮窗权限申请请求码 */
    private static final int REQ_OVERLAY = 0x6CB2;
    /** 模块目录（module.prop id=colorFC） */
    private static final String MOD = "/data/adb/modules/colorFC";
    /** 当前模式记录文件（main.sh 切换时由 config/*.all.sh 写入） */
    private static final String CUR_MODE_FILE = "/sdcard/Android/qingtd/cur_powermode.txt";
    /** 前台监视配置目录（动态模式切换.conf 所在） */
    private static final String MOKML = "/sdcard/Android/qingtd";

    /** 四模式（key/名称，色值与 WebUI MODE_COLORS 一致：省电青/均衡蓝/性能橙/极速紫） */
    private static final String[] MODE_KEYS = {"powersave", "balance", "performance", "fast"};
    private static final String[] MODE_NAMES = {"省电", "均衡", "性能", "极速"};
    private static final int[] MODE_COLORS = {0xFF00B5A3, 0xFF0096C8, 0xFFE08A00, 0xFFA02CF0};

    private boolean rooted = false;
    /** root 检测是否已完成（未完成前不拦截切换，避免误报无 ROOT） */
    private boolean rootChecked = false;
    private double peakWatts = 0;
    /** 电芯模式：0=自动校准 1=强制单电芯 2=强制双电芯（并联双芯单节点只报单芯电流，需手动×2） */
    private int cellMode = 0;
    private int lastAutoCells = 1;

    private TextView rootBadge;
    private TextView powerValue, powerStatus, currentValue, voltageValue, peakValue, cellBadge;
    private TextView batteryLevel, batteryTemp;
    /** 悬浮窗管理按钮（点击弹出监视悬浮窗功能列表） */
    private TextView monitorToggle;
    /** 悬浮窗管理弹窗实例（授权返回后同步开关状态用） */
    private OverlayManagerSheet ovlSheet;
    /** 等待悬浮窗权限授权的监视器类型 */
    private int pendingOvl = -1;
    /** 功耗统计按钮（点击展开/收起近 3 小时曲线） */
    private TextView histToggle;
    private LinearLayout powerHistBox;
    private boolean histExpanded = false;

    private SparkView histSpark;
    private PowerCurveView powerCurve;
    private TextView histHint;
    private long lastHistUpd = 0;

    // ===== 模式快切 =====
    private TextView curModeView;
    private LinearLayout modeChipsBox;
    private View[] chipViews;
    private TextView[] chipLabels;
    /** 当前运行模式（null = 未知） */
    private String curMode = null;
    /** 模式应用中（防连点） */
    private boolean applying = false;

    // ===== CPU 核心状态 + 管理（逻辑照搬 Kin-app SysfsReader）=====
    private TextView cpuSummary, cpuManageBtn;
    private LinearLayout cpuDots, cpuCoreList;
    private boolean cpuExpanded = false;
    /** 已构建的核心行（点阵方块 / 列表行），索引即核心号 */
    private View[] cpuDotViews;
    private TextView[] cpuRowNames;
    private TextView[] cpuRowBoxes;
    private int cpuCoreCount = 0;
    private volatile boolean cpuSwitching = false;
    private long lastCpuUpd = 0;
    /** 簇拓扑缓存（policy → 核心范围），首次快照时取一次 */
    private List<int[]> cpuTopo;

    static SocInfo cachedSoc;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        rootBadge = findViewById(R.id.rootBadge);
        powerValue = findViewById(R.id.powerValue);
        powerStatus = findViewById(R.id.powerStatus);
        currentValue = findViewById(R.id.currentValue);
        voltageValue = findViewById(R.id.voltageValue);
        peakValue = findViewById(R.id.peakValue);
        cellBadge = findViewById(R.id.cellBadge);
        batteryLevel = findViewById(R.id.batteryLevel);
        batteryTemp = findViewById(R.id.batteryTemp);
        curModeView = findViewById(R.id.curMode);
        modeChipsBox = findViewById(R.id.modeChips);

        // 记录卡片：按压动效
        Warp.press(findViewById(R.id.menuPowerRecords));

        // 主题设置入口
        findViewById(R.id.menuTheme).setOnClickListener(v ->
                startActivity(new Intent(this, ThemeActivity.class)));

        detectSoc();
        detectRoot();
        buildChips();
        refreshMode();
        startPowerLoop();
        startCpuLoop();
        AppLimitService.ensure(this);   // 已配置单应用负载限制则确保执行服务在跑

        // 悬浮窗管理：点击弹出监视悬浮窗功能列表（负载/进程/迷你/温度，各自开关）
        monitorToggle = findViewById(R.id.monitorToggle);
        monitorToggle.setOnClickListener(v -> showOverlayManager());

        // 功耗统计：展开/收起近 3 小时曲线
        histToggle = findViewById(R.id.histToggle);
        histToggle.setOnClickListener(v -> toggleHist());
        powerHistBox = findViewById(R.id.powerHistBox);

        histSpark = findViewById(R.id.histSpark);
        powerCurve = findViewById(R.id.powerCurve);
        histHint = findViewById(R.id.histHint);
        powerHistBox.setOnClickListener(v -> PowerHistoryManager.openDetail(this));

        // 功耗记录卡片：Scene 样式详细记录
        findViewById(R.id.menuPowerRecords).setOnClickListener(v -> PowerHistoryManager.openDetail(this));

        cellMode = getSharedPreferences("colorfc", MODE_PRIVATE).getInt("cellMode", 0);
        cellBadge.setOnClickListener(v -> showCellDialog());

        // CPU 核心：管理按钮展开/收起各核开关列表
        cpuSummary = findViewById(R.id.cpuSummary);
        cpuManageBtn = findViewById(R.id.cpuManageBtn);
        cpuDots = findViewById(R.id.cpuDots);
        cpuCoreList = findViewById(R.id.cpuCoreList);
        cpuManageBtn.setOnClickListener(v -> toggleCpuList());

        // 兜底：每次到前台重读持久化的电芯模式，防止意外丢失
        updateCellModeFromPrefs();

        // 底部导航栏
        setupBottomNav(R.id.navHome);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** 检测 SOC 型号（供其它页面缓存使用，主页不再展示 SOC 卡片） */
    private void detectSoc() {
        if (cachedSoc != null) return;
        new Thread(() -> {
            cachedSoc = SocInfo.autoDetect();
        }).start();
    }

    private void detectRoot() {
        new Thread(() -> {
            rooted = RootShell.hasRoot();
            rootChecked = true;
            runOnUiThread(() -> {
                rootBadge.setText(rooted ? "ROOT 已授权" : "无 ROOT");
                GradientDrawable bg = (GradientDrawable) rootBadge.getBackground().mutate();
                bg.setColor(rooted ? 0x2610B981 : 0x26EF4444);
                rootBadge.setTextColor(rooted ? 0xFF10B981 : 0xFFEF4444);
            });
        }).start();
    }

    // ==================== 模式快切（与 WebUI 同机制） ====================

    /** 构建四模式芯片（色点 + 名称，点击立即应用并同步默认模式） */
    private void buildChips() {
        chipViews = new View[MODE_KEYS.length];
        chipLabels = new TextView[MODE_KEYS.length];
        for (int i = 0; i < MODE_KEYS.length; i++) {
            final String key = MODE_KEYS[i];

            LinearLayout chip = new LinearLayout(this);
            chip.setOrientation(LinearLayout.HORIZONTAL);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(10), dp(9), dp(10), dp(9));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) lp.leftMargin = dp(8);
            chip.setLayoutParams(lp);
            Warp.press(chip);

            View dot = new View(this);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(8), dp(8));
            dot.setLayoutParams(dlp);
            GradientDrawable dg = new GradientDrawable();
            dg.setShape(GradientDrawable.OVAL);
            dg.setColor(MODE_COLORS[i]);
            dot.setBackground(dg);
            chip.addView(dot);

            TextView label = new TextView(this);
            label.setText(MODE_NAMES[i]);
            label.setTextSize(13);
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            llp.leftMargin = dp(6);
            label.setLayoutParams(llp);
            chip.addView(label);

            chip.setOnClickListener(v -> applyMode(key));
            modeChipsBox.addView(chip);
            chipViews[i] = chip;
            chipLabels[i] = label;
        }
        renderChips();
    }

    /** 按当前模式刷新芯片高亮与"当前"文字（未构建完成时跳过） */
    private void renderChips() {
        if (chipViews == null || curModeView == null) return;
        int active = indexOfMode(curMode);
        for (int i = 0; i < chipViews.length; i++) {
            boolean on = i == active;
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(18));
            if (on) {
                bg.setColor((MODE_COLORS[i] & 0x00FFFFFF) | 0x1A000000);   // 10% 色底
                bg.setStroke(dp(1), MODE_COLORS[i]);
            } else {
                bg.setColor(0x00000000);
                bg.setStroke(dp(1), getResources().getColor(R.color.bgCardStroke));
            }
            chipViews[i].setBackground(bg);
            chipLabels[i].setTextColor(on ? MODE_COLORS[i]
                    : getResources().getColor(R.color.textPrimary));
            chipLabels[i].setTypeface(null, on ? Typeface.BOLD : Typeface.NORMAL);
        }
        curModeView.setText(active >= 0 ? MODE_NAMES[active] : "--");
        curModeView.setTextColor(active >= 0 ? MODE_COLORS[active]
                : getResources().getColor(R.color.textSecondary));
    }

    private int indexOfMode(String key) {
        if (key == null) return -1;
        for (int i = 0; i < MODE_KEYS.length; i++) {
            if (MODE_KEYS[i].equals(key)) return i;
        }
        return -1;
    }

    /** 读取当前运行模式（cur_powermode.txt 由 config/*.all.sh 切换时写入） */
    private void refreshMode() {
        new Thread(() -> {
            String cur = RootShell.readFile(CUR_MODE_FILE);
            String m = cur == null ? null : cur.trim();
            final String mode = indexOfMode(m) >= 0 ? m : null;
            runOnUiThread(() -> {
                curMode = mode;
                renderChips();
            });
        }).start();
    }

    /** 应用模式：统一调度接口 /data/powercfg.sh（与 WebUI / Scene / 动态监视同一入口）。
     *  manual 语义 = 脚本内先写 moren= 再 main.sh 应用（顺序执行无间隙，原子），
     *  动态切换继续运行。单一入口 + 单一状态文件，所有端改动天然同步，无权限抢夺。
     *  powercfg.sh 不存在时（开机早期/异常）回退旧命令 */
    private void applyMode(String mode) {
        if (applying) return;
        if (rootChecked && !rooted) {
            Toast.makeText(this, "需要 ROOT 权限，请先在管理器授权", Toast.LENGTH_LONG).show();
            return;
        }
        final int idx = indexOfMode(mode);
        if (idx < 0) return;
        applying = true;
        Toast.makeText(this, "正在应用 " + MODE_NAMES[idx] + " …", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            // 方案配置（peiz）：旧接口 fallback 用
            String peiz = RootShell.readFile(MOD + "/files/peiz");
            if (peiz == null || peiz.trim().isEmpty()) peiz = "all";
            // 优先统一接口；不存在时回退（写 moren= + main.sh，单条原子命令，glob ASCII 定位 conf）
            RootShell.Result r = RootShell.exec("if [ -f /data/powercfg.sh ]; then "
                    + "sh /data/powercfg.sh " + mode + " manual; "
                    + "else cd " + MOKML + " 2>/dev/null && for f in *.conf; do "
                    + "[ -f \"$f\" ] || continue; "
                    + "grep -q '^moren=' \"$f\" && sed -i 's/^moren=.*/moren=" + mode + "/' \"$f\" "
                    + "|| echo \"moren=" + mode + "\" >> \"$f\"; done; "
                    + "sh " + MOD + "/script/main.sh " + mode + " " + MOD + "/files " + peiz.trim()
                    + "; fi; true", 25);
            final boolean ok = r.ok();
            runOnUiThread(() -> {
                applying = false;
                if (ok) {
                    curMode = mode;
                    renderChips();
                    Toast.makeText(this, MODE_NAMES[idx] + " 已应用（并设为默认模式）",
                            Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "应用失败："
                            + (r.err == null || r.err.trim().isEmpty() ? "ROOT 执行失败" : r.err.trim()),
                            Toast.LENGTH_LONG).show();
                }
                // 无论成败都回读真实状态（cur_powermode.txt 由 a.all.sh 写入）
                refreshMode();
            });
        }).start();
    }

    /** 实时功耗刷新（每秒），顺带写入功耗历史记录 */
    private void startPowerLoop() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                new Thread(() -> {
                    PowerMonitor.BatteryStat st = PowerMonitor.readOnce();
                    if (st != null) {
                        // 历史采样（内部 1 分钟节流），功耗按主页电芯模式修正后记录
                        st.watts = PowerMonitor.applyCellMode(st, cellMode);
                        PowerHistoryManager.record(MainActivity.this, st);
                        // 功耗统计曲线每分钟刷新一次
                        if (System.currentTimeMillis() - lastHistUpd > 60_000) {
                            lastHistUpd = System.currentTimeMillis();
                            refreshHistCurve();
                        }
                    }
                    runOnUiThread(() -> {
                        if (st != null) updatePower(st);
                    });
                    handler.postDelayed(this, 1000);
                }).start();
            }
        }, 300);
    }

    /** 每次到前台从持久化存储重读电芯模式 */
    private void updateCellModeFromPrefs() {
        cellMode = getSharedPreferences("colorfc", MODE_PRIVATE).getInt("cellMode", 0);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateCellModeFromPrefs();
        // 回前台重读当前模式：可能被前台监视(qtbh.sh)或 WebUI 切换过
        refreshMode();
        // 授权返回：自动开启等待中的监视器（照搬 Kin 权限回调行为）
        if (pendingOvl >= 0) {
            int t = pendingOvl;
            pendingOvl = -1;
            if (Settings.canDrawOverlays(this)) setOvlEnabled(t, true);
        }
        // 弹窗仍开着：窗内（✕/长按）可能已关闭监视器，重读 Prefs 同步开关
        if (ovlSheet != null && ovlSheet.isShowing()) ovlSheet.syncStates();
        // 回到前台时同步悬浮窗按钮状态（服务可能已被通知栏/窗内关闭）
        syncMonitorUi();
    }

    /** 悬浮窗管理按钮状态：任一监视窗开启主题色，否则次要色 */
    private void syncMonitorUi() {
        if (monitorToggle == null) return;
        SharedPreferences p = getSharedPreferences("colorfc", MODE_PRIVATE);
        boolean any = false;
        for (String k : MonitorService.PREF_KEYS) {
            if (p.getBoolean(k, false)) {
                any = true;
                break;
            }
        }
        monitorToggle.setTextColor(any
                ? getResources().getColor(R.color.accent)
                : getResources().getColor(R.color.textSecondary));
    }

    /** 点击"悬浮窗管理"：弹出监视悬浮窗功能列表（负载/进程/迷你/温度各自开关） */
    private void showOverlayManager() {
        if (ovlSheet != null && ovlSheet.isShowing()) {
            ovlSheet.dismiss();
            return;
        }
        ovlSheet = new OverlayManagerSheet(this, new OverlayManagerSheet.Host() {
            @Override
            public void onOvlToggle(int type, boolean on) {
                setOvlEnabled(type, on);
            }

            @Override
            public void onOvlNeedPermission(int type) {
                pendingOvl = type;
                Toast.makeText(MainActivity.this, "请先授予悬浮窗权限后重试", Toast.LENGTH_LONG).show();
                startActivityForResult(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())), REQ_OVERLAY);
            }
        });
        ovlSheet.show();
    }

    /** 监视器开关落地：持久化 + 显示/隐藏对应悬浮窗（照搬 Kin Prefs + FloatWindowService） */
    private void setOvlEnabled(int type, boolean on) {
        getSharedPreferences("colorfc", MODE_PRIVATE)
                .edit().putBoolean(MonitorService.PREF_KEYS[type], on).apply();
        if (on) MonitorService.show(this, type);
        else MonitorService.hide(this, type);
        syncMonitorUi();
    }

    // ==================== CPU 核心状态与管理（照搬 Kin-app 逻辑）====================

    /** CPU 核心状态循环：1s 快照一次（online + 频率 + 实时占用），驱动点阵/列表/摘要刷新 */
    private void startCpuLoop() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                new Thread(() -> {
                    final CpuCoreManager.Snapshot sp = refreshCpuSnapshot();
                    runOnUiThread(() -> {
                        if (sp != null) updateCpuUi(sp);
                        handler.postDelayed(this, 1000);
                    });
                }).start();
            }
        }, 300);
    }

    /** 取核心快照；首次/核心数变化时在 UI 线程构建点阵与开关行 */
    private CpuCoreManager.Snapshot refreshCpuSnapshot() {
        try {
            int n = cpuCoreCount > 0 ? cpuCoreCount : CpuCoreManager.coreCount();
            if (n <= 0) return null;
            CpuCoreManager.Snapshot sp = CpuCoreManager.snapshot(n);
            if (cpuDotViews == null || cpuDotViews.length != n) {
                if (cpuTopo == null) cpuTopo = CpuCoreManager.policyTopology();
                final int cnt = n;
                final CpuCoreManager.Snapshot s0 = sp;
                runOnUiThread(() -> buildCpuViews(cnt, s0));
            }
            return sp;
        } catch (Exception e) {
            return null;
        }
    }

    /** 构建核心状态点阵 + 展开列表的每核行（一次性，核心数变化时重建） */
    private void buildCpuViews(int n, CpuCoreManager.Snapshot first) {
        cpuCoreCount = n;
        cpuDots.removeAllViews();
        cpuCoreList.removeAllViews();
        cpuDotViews = new View[n];
        cpuRowNames = new TextView[n];
        cpuRowBoxes = new TextView[n];

        for (int i = 0; i < n; i++) {
            // ---- 点阵方块（在线实心主题色 / 离线空心）----
            View dot = new View(this);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(14), dp(14));
            dlp.setMarginEnd(dp(6));
            dot.setLayoutParams(dlp);
            dot.setBackground(makeDotShape(true, 0));
            cpuDots.addView(dot);
            cpuDotViews[i] = dot;

            // ---- 列表行：CPU0 · 簇名 · 频率    [开关方格] ----
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rlp.topMargin = dp(4);
            row.setLayoutParams(rlp);

            TextView name = new TextView(this);
            name.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            name.setTextColor(getResources().getColor(R.color.textPrimary));
            name.setTextSize(12);
            cpuRowNames[i] = name;
            row.addView(name);

            TextView box = new TextView(this);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(22), dp(22));
            box.setLayoutParams(blp);
            box.setGravity(android.view.Gravity.CENTER);
            box.setTextSize(12);
            box.setTypeface(null, Typeface.BOLD);
            box.setTextColor(0xFFFFFFFF);
            box.setBackground(makeBoxShape(true));
            Warp.press(box);
            cpuRowBoxes[i] = box;
            row.addView(box);

            cpuCoreList.addView(row);
        }
        if (first != null) updateCpuUi(first);
    }

    /** 方格开关背景：选中实心主题色 / 未选中描边（GradientDrawable，主题沉浸时随全局玻璃化） */
    private android.graphics.drawable.GradientDrawable makeBoxShape(boolean on) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        g.setCornerRadius(dp(6));
        if (on) {
            g.setColor(getResources().getColor(R.color.accent));
        } else {
            g.setColor(0x00000000);
            g.setStroke(dp(2), getResources().getColor(R.color.textDim));
        }
        return g;
    }

    /** 点阵方块背景：在线按实时负载加深（35%~100% 主题色），离线空心 */
    private android.graphics.drawable.GradientDrawable makeDotShape(boolean on, float load) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        g.setCornerRadius(dp(4));
        if (on) {
            int base = getResources().getColor(R.color.accent);
            int alpha = Math.round(89 + 166 * Math.min(1f, Math.max(0f, load / 100f)));   // 0x59~0xFF
            g.setColor((base & 0x00FFFFFF) | (alpha << 24));
        } else {
            g.setColor(0x00000000);
            g.setStroke(dp(2), getResources().getColor(R.color.bgCardStroke));
        }
        return g;
    }

    /** 点方格：即时开关对应核心（Kin 方式直接写 sysfs，cpu0 恒在线不可关） */
    private void onCpuBoxClick(final int cpu) {
        if (cpuSwitching) return;
        if (cpu == 0) {
            Toast.makeText(this, "CPU0 为主核，系统不允许关闭", Toast.LENGTH_SHORT).show();
            return;
        }
        final boolean target = cpuRowBoxes[cpu] != null
                && cpuRowBoxes[cpu].getText().toString().contains("✓");
        final boolean on = !target;
        cpuSwitching = true;
        Toast.makeText(this, "正在" + (on ? "启用" : "停用") + " CPU" + cpu + " …", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            final boolean ok = CpuCoreManager.setCoreOnline(cpu, on);
            runOnUiThread(() -> {
                cpuSwitching = false;
                if (!ok) Toast.makeText(this, "切换失败：需要 ROOT 或内核不支持热插拔", Toast.LENGTH_SHORT).show();
                // 立即刷新一次快照回读真实状态（内核可能拒绝离线最后一个大核等）
                new Thread(() -> {
                    final CpuCoreManager.Snapshot sp = refreshCpuSnapshot();
                    runOnUiThread(() -> { if (sp != null) updateCpuUi(sp); });
                }).start();
            });
        }).start();
    }

    /** 刷新点阵/列表/摘要显示 */
    private void updateCpuUi(CpuCoreManager.Snapshot sp) {
        if (cpuDotViews == null || sp == null || sp.cores != cpuDotViews.length) return;
        List<int[]> topo = cpuTopo;
        for (int i = 0; i < sp.cores; i++) {
            boolean on = sp.online[i];
            // 点阵
            View dot = cpuDotViews[i];
            if (dot != null) dot.setBackground(makeDotShape(on, on && sp.busy != null ? sp.busy[i] : 0));
            // 列表行文字：CPU0 · 小核 1804MHz（离线显示"已停用"）
            TextView name = cpuRowNames[i];
            if (name != null) {
                StringBuilder sb = new StringBuilder("CPU").append(i);
                String cluster = clusterLabel(i, topo);
                if (cluster != null) sb.append(" · ").append(cluster);
                if (on) {
                    sb.append(sp.freqMhz[i] > 0
                            ? String.format(Locale.US, " · %d MHz", sp.freqMhz[i]) : "");
                } else {
                    sb.append(" · 已停用");
                }
                name.setText(sb);
                name.setTextColor(getResources().getColor(on ? R.color.textPrimary : R.color.textDim));
            }
            // 开关方格（cpu0 显示"主"不可关；其余 ✓/空）
            TextView box = cpuRowBoxes[i];
            if (box != null) {
                if (i == 0) {
                    box.setText("主");
                    box.setTextSize(9);
                    box.setTextColor(getResources().getColor(R.color.textSecondary));
                    box.setBackground(makeBoxShape(true));
                    box.setOnClickListener(null);
                } else {
                    box.setText(on ? "✓" : "");
                    box.setTextSize(12);
                    box.setTextColor(0xFFFFFFFF);
                    box.setBackground(makeBoxShape(on));
                    final int cpu = i;
                    box.setOnClickListener(v -> onCpuBoxClick(cpu));
                }
            }
        }
        cpuSummary.setText(String.format(Locale.US, "%d核 · %d在线", sp.cores, sp.onlineCount()));
    }

    /** 核心所在簇名：小核/中核/大核（按 policy 的 related_cpus 覆盖范围判断） */
    private String clusterLabel(int cpu, List<int[]> topo) {
        if (topo == null || topo.isEmpty()) return null;
        int idx = -1;
        for (int i = 0; i < topo.size(); i++) {
            int[] t = topo.get(i);
            if (t[1] >= 0 && cpu >= t[1] && cpu <= t[2]) { idx = i; break; }
        }
        if (idx < 0) return null;
        switch (topo.size()) {
            case 1: return "全核";
            case 2: return idx == 0 ? "小核" : "大核";
            default: return idx == 0 ? "小核" : (idx == topo.size() - 1 ? "大核" : "中核");
        }
    }

    /** 展开/收起各核开关列表 */
    private void toggleCpuList() {
        cpuExpanded = !cpuExpanded;
        cpuCoreList.setVisibility(cpuExpanded ? View.VISIBLE : View.GONE);
        cpuManageBtn.setText(cpuExpanded ? "管理 ▴" : "管理 ▾");
        cpuManageBtn.setTextColor(cpuExpanded
                ? getResources().getColor(R.color.accent)
                : getResources().getColor(R.color.textSecondary));
        if (cpuExpanded) {   // 展开时立即刷新一次，避免旧数据
            new Thread(() -> {
                final CpuCoreManager.Snapshot sp = refreshCpuSnapshot();
                runOnUiThread(() -> { if (sp != null) updateCpuUi(sp); });
            }).start();
        }
    }

    /** 点击"功耗统计"展开/收起近 3 小时曲线 */
    private void toggleHist() {
        histExpanded = !histExpanded;
        powerHistBox.setVisibility(histExpanded ? View.VISIBLE : View.GONE);
        histToggle.setText(histExpanded ? "功耗统计 ▴" : "功耗统计 ▾");
        histToggle.setTextColor(histExpanded
                ? getResources().getColor(R.color.accent)
                : getResources().getColor(R.color.textSecondary));
        if (histExpanded) refreshHistCurve();
    }

    /** 功耗统计曲线：近 3 小时采样 + 均值/峰值提示 */
    private void refreshHistCurve() {
        new Thread(() -> {
            final float[] vals = PowerHistoryManager.recentWatts(this, 180);
            runOnUiThread(() -> {
                if (histSpark == null) return;
                histSpark.setData(vals);
                if (vals.length == 0) {
                    histHint.setText("暂无记录 · 采样中");
                } else {
                    double avg = 0, max = 0;
                    for (float v : vals) {
                        avg += v;
                        if (v > max) max = v;
                    }
                    avg /= vals.length;
                    histHint.setText(String.format(Locale.US, "近3小时 · 均值 %.2f W · 峰值 %.2f W", avg, max));
                }
            });
        }).start();
    }

    /** 电芯模式切换：并联双电芯机型电压 4.4V 与单芯无异，只能手动指定 */
    private void showCellDialog() {
        String[] items = {
                "自动校准（当前识别: " + (lastAutoCells >= 2 ? "双电芯" : "单电芯") + "）",
                "强制双电芯（电流×2）",
                "强制单电芯"
        };
        int checked = cellMode == 2 ? 1 : (cellMode == 1 ? 2 : 0);
        AlertDialog dlg = new AlertDialog.Builder(ThemeStore.dialogCtx(this))
                .setTitle("电芯模式")
                .setSingleChoiceItems(items, checked, (d, w) -> {
                    cellMode = w == 1 ? 2 : (w == 2 ? 1 : 0);
                    getSharedPreferences("colorfc", MODE_PRIVATE)
                            .edit().putInt("cellMode", cellMode).commit();
                    d.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
        ThemeStore.styleDialog(this, dlg);
    }

    private void updatePower(PowerMonitor.BatteryStat st) {
        lastAutoCells = st.cells;
        int cells = cellMode == 0 ? st.cells : cellMode;
        // 强制双电芯且自动判定为单：并联双芯单节点只报单芯电流，电流×2、功率重算
        boolean up = cells >= 2 && st.cells < 2;
        double amps = up ? st.amps * 2 : st.amps;
        double w = up ? Math.abs(st.volts * amps) : Math.abs(st.watts);

        powerValue.setText(w > 0 ? String.format(Locale.US, "%.2f", w) : "--");
        if (w > peakWatts) peakWatts = w;
        peakValue.setText(peakWatts > 0 ? String.format(Locale.US, "%.2f W", peakWatts) : "--");

        String status = st.status.isEmpty()
                ? (amps > 0 ? "放电" : "充电")
                : ("Charging".equalsIgnoreCase(st.status) ? "充电中"
                : "Full".equalsIgnoreCase(st.status) ? "已充满" : "放电中");
        powerStatus.setText(status);
        powerStatus.setTextColor("Charging".equalsIgnoreCase(st.status) ? 0xFF00B5A3 : 0xFFE08A00);

        currentValue.setText(amps != 0 ? String.format(Locale.US, "%.0f mA", Math.abs(amps) * 1000) : "--");
        voltageValue.setText(st.volts > 0 ? String.format(Locale.US, "%.2f V", st.volts) : "--");

        cellBadge.setText((cells >= 2 ? "双电芯" : "单电芯")
                + (cellMode == 0 ? " · 已校准 ▸" : " · 手动 ▸"));
        if (st.level >= 0) batteryLevel.setText(st.level + "%");
        if (st.tempC > 0) {
            batteryTemp.setText(String.format(Locale.US, "%.1f℃", st.tempC));
        }

        // 实时功耗曲线（30 点滚动，充放电分色）
        if (powerCurve != null && w > 0) {
            boolean chg = "Charging".equalsIgnoreCase(st.status)
                    || "Full".equalsIgnoreCase(st.status)
                    || "Not charging".equalsIgnoreCase(st.status);
            float temp = st.tempC > 0 ? (float) st.tempC : 0f;
            powerCurve.push((float) w, temp, chg);
        }
    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
    }
}
