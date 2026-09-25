package Color.fc;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import Color.fc.view.SparkView;
import Color.fc.view.PowerCurveView;
import Color.fc.view.Warp;

/**
 * 主页：实时功耗（自动校准单/双电芯）+ 功能入口
 */
public class MainActivity extends ThemedActivity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SocInfo soc;
    private boolean rooted = false;
    private double peakWatts = 0;
    /** 电芯模式：0=自动校准 1=强制单电芯 2=强制双电芯（并联双芯单节点只报单芯电流，需手动×2） */
    private int cellMode = 0;
    private int lastAutoCells = 1;

    private TextView rootBadge;
    private TextView powerValue, powerStatus, currentValue, voltageValue, peakValue, cellBadge;
    private TextView batteryLevel, batteryTemp;
    /** 迷你悬浮窗文字按钮（点击显隐，无开关） */
    private TextView monitorToggle;
    /** 功耗统计按钮（点击展开/收起近 3 小时曲线） */
    private TextView histToggle;
    private LinearLayout powerHistBox;
    private boolean histExpanded = false;

    private SparkView histSpark;
    private PowerCurveView powerCurve;
    private TextView histHint;
    private long lastHistUpd = 0;

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

        // 记录卡片：按压动效
        Warp.press(findViewById(R.id.menuFrameRecords));
        Warp.press(findViewById(R.id.menuPowerRecords));

        // 主题设置入口
        findViewById(R.id.menuTheme).setOnClickListener(v ->
                startActivity(new Intent(this, ThemeActivity.class)));

        detectSoc();
        detectRoot();
        startPowerLoop();
        startCpuLoop();
        AppLimitService.ensure(this);   // 已配置单应用负载限制则确保执行服务在跑

        // 迷你悬浮窗文字显隐悬浮窗（无开关）
        monitorToggle = findViewById(R.id.monitorToggle);
        monitorToggle.setOnClickListener(v -> toggleMonitor(!MonitorService.running));

        // 功耗统计：展开/收起近 3 小时曲线
        histToggle = findViewById(R.id.histToggle);
        histToggle.setOnClickListener(v -> toggleHist());
        powerHistBox = findViewById(R.id.powerHistBox);

        histSpark = findViewById(R.id.histSpark);
        powerCurve = findViewById(R.id.powerCurve);
        histHint = findViewById(R.id.histHint);
        powerHistBox.setOnClickListener(v -> PowerHistoryManager.openDetail(this));

        // 帧率录制记录卡片
        findViewById(R.id.menuFrameRecords).setOnClickListener(v -> showFrameRecords());
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
            runOnUiThread(() -> {
                rootBadge.setText(rooted ? "ROOT 已授权" : "无 ROOT");
                GradientDrawable bg = (GradientDrawable) rootBadge.getBackground().mutate();
                bg.setColor(rooted ? 0x2610B981 : 0x26EF4444);
                rootBadge.setTextColor(rooted ? 0xFF10B981 : 0xFFEF4444);
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
        // 回到前台时同步悬浮窗按钮状态（服务可能已被通知栏/菜单关闭）
        syncMonitorUi();
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    /** 迷你悬浮窗按钮状态：运行中主题色，未运行次要色 */
    private void syncMonitorUi() {
        if (monitorToggle != null) {
            monitorToggle.setTextColor(MonitorService.running
                    ? getResources().getColor(R.color.accent)
                    : getResources().getColor(R.color.textSecondary));
        }
    }

    /** 点击"监视器"文字启停前台服务（无开关） */
    private void toggleMonitor(boolean on) {
        if (on) {
            if (!Settings.canDrawOverlays(this)) {
                syncMonitorUi();
                Toast.makeText(this, "请先授予悬浮窗权限后重试", Toast.LENGTH_LONG).show();
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
                return;
            }
            startForegroundService(new Intent(this, MonitorService.class));
        } else {
            // 服务运行且胶囊处于隐藏状态：点击按钮优先恢复菜单胶囊而不是停止服务
            SharedPreferences p = getSharedPreferences("colorfc", MODE_PRIVATE);
            if (MonitorService.running && p.getBoolean("mon_pill_hidden", false)) {
                Intent it = new Intent(this, MonitorService.class);
                it.setAction("show_pill");
                startForegroundService(it);
            } else {
                stopService(new Intent(this, MonitorService.class));
            }
        }
        syncMonitorUi();
    }

    // ==================== CPU 核心状态与管理（照搬 Kin-app 逻辑）====================

    /** CPU 核心状态循环：3s 快照一次（online + 频率），驱动点阵/列表/摘要刷新 */
    private void startCpuLoop() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                new Thread(() -> {
                    final CpuCoreManager.Snapshot sp = refreshCpuSnapshot();
                    runOnUiThread(() -> {
                        if (sp != null) updateCpuUi(sp);
                        handler.postDelayed(this, 3000);
                    });
                }).start();
            }
        }, 500);
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
            dot.setBackground(makeDotShape(true));
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

    /** 点阵方块背景 */
    private android.graphics.drawable.GradientDrawable makeDotShape(boolean on) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        g.setCornerRadius(dp(4));
        if (on) {
            g.setColor(getResources().getColor(R.color.accent));
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
            if (dot != null) dot.setBackground(makeDotShape(on));
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

    /** 帧率录制记录列表：点击条目查看曲线图 */
    private void showFrameRecords() {
        new Thread(() -> {
            final List<FrameRecordStore.Rec> recs = FrameRecordStore.list(this);
            final String[] items = new String[recs.size()];
            SimpleDateFormat df = new SimpleDateFormat("MM-dd HH:mm", Locale.US);
            for (int i = 0; i < recs.size(); i++) {
                FrameRecordStore.Rec r = recs.get(i);
                items[i] = String.format(Locale.US, "%s · %d:%02d · 均%.0fHz · CPU峰%.0f%%",
                        df.format(new Date(r.t)), r.dur / 60000, (r.dur / 1000) % 60,
                        r.avgFps, r.maxCpu);
            }
            runOnUiThread(() -> {
                if (recs.isEmpty()) {
                    new AlertDialog.Builder(this)
                            .setTitle("帧率录制记录")
                            .setMessage("暂无录制\n\n开启【监视器功能】→ 点击状态栏\"监视器\"展开列表 → 打开\"帧率记录器\"，"
                                    + "点击帧率窗口开始录制，再次点击停止并自动保存曲线图（帧率 / CPU线程负载 / CPU使用率）")
                            .setPositiveButton("关闭", null)
                            .show();
                    return;
                }
                new AlertDialog.Builder(this)
                        .setTitle("帧率录制记录")
                        .setItems(items, (d, w) -> showRecordImage(recs.get(w)))
                        .setNeutralButton("清空", (d, w) -> confirmClearRecords())
                        .setPositiveButton("关闭", null)
                        .show();
            });
        }).start();
    }

    /** 展开查看录制曲线图 */
    private void showRecordImage(FrameRecordStore.Rec r) {
        new Thread(() -> {
            Bitmap bmp = null;
            try {
                if (r.ref != null && r.ref.startsWith("content://")) {
                    try (InputStream is = getContentResolver().openInputStream(Uri.parse(r.ref))) {
                        bmp = BitmapFactory.decodeStream(is);
                    }
                } else if (r.ref != null) {
                    bmp = BitmapFactory.decodeFile(r.ref);
                }
            } catch (Exception ignored) {
            }
            final Bitmap fb = bmp;
            runOnUiThread(() -> {
                if (fb == null) {
                    Toast.makeText(this, "曲线图已被删除或无法读取", Toast.LENGTH_SHORT).show();
                    return;
                }
                ImageView iv = new ImageView(this);
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                iv.setAdjustViewBounds(true);
                iv.setImageBitmap(fb);
                ScrollView sv = new ScrollView(this);
                sv.addView(iv);
                new AlertDialog.Builder(this)
                        .setTitle(String.format(Locale.US, "录制 %d:%02d · 均%.0fHz",
                                r.dur / 60000, (r.dur / 1000) % 60, r.avgFps))
                        .setView(sv)
                        .setPositiveButton("关闭", null)
                        .show();
            });
        }).start();
    }

    /** 清空帧率录制记录（含图片） */
    private void confirmClearRecords() {
        new AlertDialog.Builder(this)
                .setTitle("清空录制记录")
                .setMessage("将删除全部录制记录及曲线图，确定？")
                .setPositiveButton("清空", (d, w) -> new Thread(() -> {
                    FrameRecordStore.clear(this);
                    runOnUiThread(() -> {
                        Toast.makeText(this, "已清空录制记录", Toast.LENGTH_SHORT).show();
                    });
                }).start())
                .setNegativeButton("取消", null)
                .show();
    }

    /** 电芯模式切换：并联双电芯机型电压 4.4V 与单芯无异，只能手动指定 */
    private void showCellDialog() {
        String[] items = {
                "自动校准（当前识别: " + (lastAutoCells >= 2 ? "双电芯" : "单电芯") + "）",
                "强制双电芯（电流×2）",
                "强制单电芯"
        };
        int checked = cellMode == 2 ? 1 : (cellMode == 1 ? 2 : 0);
        new AlertDialog.Builder(this)
                .setTitle("电芯模式")
                .setSingleChoiceItems(items, checked, (d, w) -> {
                    cellMode = w == 1 ? 2 : (w == 2 ? 1 : 0);
                    getSharedPreferences("colorfc", MODE_PRIVATE)
                            .edit().putInt("cellMode", cellMode).commit();
                    d.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
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
