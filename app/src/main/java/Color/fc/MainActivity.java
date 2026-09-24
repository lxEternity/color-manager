package Color.fc;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
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

import Color.fc.view.ChipView;
import Color.fc.view.SparkView;
import Color.fc.view.Warp;

/**
 * 主页：SOC 型号卡 + 实时功耗（自动校准单/双电芯）+ 功能入口
 */
public class MainActivity extends ThemedActivity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SocInfo soc;
    private boolean rooted = false;
    private double peakWatts = 0;
    /** 电芯模式：0=自动校准 1=强制单电芯 2=强制双电芯（并联双芯单节点只报单芯电流，需手动×2） */
    private int cellMode = 0;
    private int lastAutoCells = 1;

    private TextView socMarketing, socPlatform, rootBadge;
    private TextView powerValue, powerStatus, currentValue, voltageValue, peakValue, cellBadge;
    private TextView cpuCount, batteryLevel, batteryTemp;
    /** 位置1：迷你悬浮窗文字按钮（点击显隐，无开关） */
    private TextView monitorToggle;
    /** 位置2：功耗统计按钮（点击展开/收起近 3 小时曲线） */
    private TextView histToggle;
    private LinearLayout powerHistBox;
    private boolean histExpanded = false;

    private SparkView histSpark;
    private TextView histHint;
    private long lastHistUpd = 0;

    private ChipView chipView;
    static SocInfo cachedSoc;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        socMarketing = findViewById(R.id.socMarketing);
        socPlatform = findViewById(R.id.socPlatform);
        rootBadge = findViewById(R.id.rootBadge);
        chipView = findViewById(R.id.chipView);
        powerValue = findViewById(R.id.powerValue);
        powerStatus = findViewById(R.id.powerStatus);
        currentValue = findViewById(R.id.currentValue);
        voltageValue = findViewById(R.id.voltageValue);
        peakValue = findViewById(R.id.peakValue);
        cellBadge = findViewById(R.id.cellBadge);
        cpuCount = findViewById(R.id.cpuCount);
        batteryLevel = findViewById(R.id.batteryLevel);
        batteryTemp = findViewById(R.id.batteryTemp);

        // 功能入口：直接进入下一页面（转场特效已按需求移除）
        wireBeam(R.id.menuSchedule, ScheduleActivity.class);
        wireBeam(R.id.menuGovernor, GovernorActivity.class);
        wireBeam(R.id.menuMode, ModeActivity.class);
        wireBeam(R.id.menuTheme, ThemeActivity.class);
        Warp.press(findViewById(R.id.socCard));
        // 记录卡片：按压动效
        Warp.press(findViewById(R.id.menuFrameRecords));
        Warp.press(findViewById(R.id.menuPowerRecords));

        detectSoc();
        detectRoot();
        startPowerLoop();
        AppLimitService.ensure(this);   // 已配置单应用负载限制则确保执行服务在跑

        // 位置1：点击"迷你悬浮窗"文字显隐悬浮窗（无开关）
        monitorToggle = findViewById(R.id.monitorToggle);
        monitorToggle.setOnClickListener(v -> toggleMonitor(!MonitorService.running));

        // 位置2：点击"功耗统计"展开/收起近 3 小时曲线
        histToggle = findViewById(R.id.histToggle);
        histToggle.setOnClickListener(v -> toggleHist());
        powerHistBox = findViewById(R.id.powerHistBox);

        // 功耗统计曲线（SOC 卡内）：点击查看 Scene 样式详细记录
        histSpark = findViewById(R.id.histSpark);
        histHint = findViewById(R.id.histHint);
        powerHistBox.setOnClickListener(v -> PowerHistoryManager.openDetail(this));

        // 帧率录制记录卡片
        findViewById(R.id.menuFrameRecords).setOnClickListener(v -> showFrameRecords());
        // 功耗记录卡片：Scene 样式详细记录
        findViewById(R.id.menuPowerRecords).setOnClickListener(v -> PowerHistoryManager.openDetail(this));

        cellMode = getSharedPreferences("colorfc", MODE_PRIVATE).getInt("cellMode", 0);
        cellBadge.setOnClickListener(v -> showCellDialog());

        // 兜底：每次到前台重读持久化的电芯模式，防止意外丢失
        updateCellModeFromPrefs();
    }

    /** 入口卡片：按压动效 + 直接进入下一页面（转场特效已移除） */
    private void wireBeam(int id, Class<?> cls) {
        View v = findViewById(id);
        Warp.press(v);
        v.setOnClickListener(x -> startActivity(new Intent(this, cls)));
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** 检测 SOC 型号并展示对应配置 */
    private void detectSoc() {
        if (cachedSoc != null) {
            applySoc(cachedSoc);
            return;
        }
        new Thread(() -> {
            cachedSoc = SocInfo.autoDetect();
            runOnUiThread(() -> applySoc(cachedSoc));
        }).start();
    }

    private void applySoc(SocInfo s) {
        soc = s;
        chipView.setChip(s.shortName, s.code);
        socMarketing.setText(s.marketing);
        socPlatform.setText(String.format(Locale.US, "platform: %s · %s", s.platform, s.vendor));
        int n = PowerMonitor.cpuCount();
        cpuCount.setText(n > 0 ? String.valueOf(n) : "--");
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

    /** 位置1：点击"迷你悬浮窗"文字启停前台服务（无开关） */
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

    /** 位置2：点击"功耗统计"展开/收起近 3 小时曲线 */
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
    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
    }
}
