package Color.fc;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
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
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import Color.fc.view.Beam;
import Color.fc.view.ChipView;
import Color.fc.view.SparkView;

/**
 * 主页：SOC 可视化 + 实时功耗（自动校准单/双电芯）+ 硬件实时状态 + 功能入口
 */
public class MainActivity extends ThemedActivity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SocInfo soc;
    private boolean rooted = false;
    private double peakWatts = 0;
    /** 电芯模式：0=自动校准 1=强制单电芯 2=强制双电芯（并联双芯单节点只报单芯电流，需手动×2） */
    private int cellMode = 0;
    private int lastAutoCells = 1;

    private ChipView chipView;
    private TextView socMarketing, socPlatform, rootBadge;
    private TextView powerValue, powerStatus, currentValue, voltageValue, peakValue, cellBadge;
    private TextView cpuCount, batteryLevel, batteryTemp;
    private SparkView sparkView;
    private Switch monitorSwitch;
    private boolean suppressSwitch = false;
    private TextView powerHistorySummary;
    private TextView frameRecordsSummary;
    private int powerTick = 0;

    // ===== 硬件实时状态（主页 2s 扫描） =====
    private TextView hwCpuBusy, hwCpuTemp, hwGpuFreq, hwGpuTemp, hwSocTemp, hwCpuFreq;
    private boolean hwRunning = false;
    private long hwIdle = -1, hwTotal = -1;
    /** 电池温度（CPU 温区兜底用，功耗循环更新） */
    private volatile double batTempC = 0;
    /** 自调度扫描任务（onCreate 中创建，避免字段初始化自引用） */
    private Runnable hwTick;

    static SocInfo cachedSoc;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        chipView = findViewById(R.id.chipView);
        socMarketing = findViewById(R.id.socMarketing);
        socPlatform = findViewById(R.id.socPlatform);
        rootBadge = findViewById(R.id.rootBadge);
        powerValue = findViewById(R.id.powerValue);
        powerStatus = findViewById(R.id.powerStatus);
        currentValue = findViewById(R.id.currentValue);
        voltageValue = findViewById(R.id.voltageValue);
        peakValue = findViewById(R.id.peakValue);
        cellBadge = findViewById(R.id.cellBadge);
        cpuCount = findViewById(R.id.cpuCount);
        batteryLevel = findViewById(R.id.batteryLevel);
        batteryTemp = findViewById(R.id.batteryTemp);
        sparkView = findViewById(R.id.sparkView);

        // 硬件实时状态
        hwCpuBusy = findViewById(R.id.hwCpuBusy);
        hwCpuTemp = findViewById(R.id.hwCpuTemp);
        hwGpuFreq = findViewById(R.id.hwGpuFreq);
        hwGpuTemp = findViewById(R.id.hwGpuTemp);
        hwSocTemp = findViewById(R.id.hwSocTemp);
        hwCpuFreq = findViewById(R.id.hwCpuFreq);

        // 自调度硬件扫描任务（2s 循环，onResume 启动 / onPause 停止）
        hwTick = () -> {
            if (!hwRunning) return;
            new Thread(() -> {
                // CPU 占用：/proc/stat 差分（无需 root）
                double busy = -1;
                try (BufferedReader r = new BufferedReader(new FileReader("/proc/stat"))) {
                    String l = r.readLine();
                    if (l != null && l.startsWith("cpu ")) {
                        String[] p = l.split("\\s+");
                        long idle = Long.parseLong(p[4]) + Long.parseLong(p[5]);
                        long total = 0;
                        for (int i = 1; i < p.length; i++) total += Long.parseLong(p[i]);
                        if (hwIdle >= 0 && total > hwTotal) {
                            busy = Math.max(0, Math.min(100,
                                    100.0 * (total - hwTotal - (idle - hwIdle)) / (total - hwTotal)));
                        }
                        hwIdle = idle;
                        hwTotal = total;
                    }
                } catch (Exception ignored) {
                }
                // GPU/CPU 频率 + 温区：一次 root 扫描多字段解析
                double gpuHz = 0, cpuKHz = 0, cpuT = 0, socT = 0, gpuT = 0;
                try {
                    RootShell.Result r = RootShell.exec(HardwareMonitor.SCAN, 8);
                    if (r.ok() && r.out != null) {
                        gpuHz = HardwareMonitor.gpuHz(r.out);
                        cpuKHz = HardwareMonitor.cpuMaxKHz(r.out);
                        cpuT = HardwareMonitor.zoneTemp(r.out, "cpu", batTempC);
                        socT = HardwareMonitor.zoneTemp(r.out, "soc", cpuT);
                        gpuT = HardwareMonitor.zoneTemp(r.out, "gpu", socT);
                    }
                } catch (Exception ignored) {
                }
                final double b = busy, g = gpuHz, ck = cpuKHz, ct = cpuT, st = socT, gt = gpuT;
                runOnUiThread(() -> updateHardware(b, ck, g, ct, st, gt));
                if (hwRunning) handler.postDelayed(hwTick, 2000);
            }).start();
        };

        // 功能入口：光束转场进入下一页面
        wireBeam(R.id.menuSchedule, ScheduleActivity.class);
        wireBeam(R.id.menuGovernor, GovernorActivity.class);
        wireBeam(R.id.menuMode, ModeActivity.class);
        wireBeam(R.id.menuTheme, ThemeActivity.class);
        tintBadges();
        // 记录卡片：按压动效
        Beam.press(findViewById(R.id.menuFrameRecords));
        Beam.press(findViewById(R.id.menuPowerHistory));
        Beam.press(findViewById(R.id.menuMonitor));
        Beam.press(findViewById(R.id.socCard));

        detectSoc();
        detectRoot();
        startPowerLoop();

        // 迷你悬浮窗开关
        monitorSwitch = findViewById(R.id.monitorSwitch);
        monitorSwitch.setOnCheckedChangeListener((btn, on) -> {
            if (suppressSwitch) return;
            toggleMonitor(on);
        });

        // 功耗记录卡片
        powerHistorySummary = findViewById(R.id.powerHistorySummary);
        findViewById(R.id.menuPowerHistory).setOnClickListener(v -> showHistoryDialog());
        refreshHistorySummary();

        // 帧率录制记录卡片
        frameRecordsSummary = findViewById(R.id.frameRecordsSummary);
        findViewById(R.id.menuFrameRecords).setOnClickListener(v -> showFrameRecords());
        refreshRecordsSummary();

        cellMode = getSharedPreferences("colorfc", MODE_PRIVATE).getInt("cellMode", 0);
        cellBadge.setOnClickListener(v -> showCellDialog());

        // 兜底：每次到前台重读持久化的电芯模式，防止意外丢失
        updateCellModeFromPrefs();
    }

    /** 入口卡片：按压动效 + 点击触发光束转场进入下一页面 */
    private void wireBeam(int id, Class<?> cls) {
        View v = findViewById(id);
        Beam.press(v);
        v.setOnClickListener(x -> Beam.go(this, new Intent(this, cls)));
    }

    /** 功能入口徽章着色：柔和底色 + 同色系字符 */
    private void tintBadges() {
        int[][] pairs = {
                {R.id.badgeSchedule, R.color.accent},
                {R.id.badgeGovernor, R.color.magenta},
                {R.id.badgeMode, R.color.green},
                {R.id.badgeTheme, R.color.orange}
        };
        for (int[] p : pairs) {
            TextView badge = findViewById(p[0]);
            if (badge == null) continue;
            int color = getResources().getColor(p[1]);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(9));
            bg.setColor((color & 0x00FFFFFF) | 0x26000000);
            badge.setBackground(bg);
            badge.setTextColor(color);
        }
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
                        // 每 15 秒刷新一次记录摘要
                        if (++powerTick % 15 == 0) refreshHistorySummary();
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
        // 回到前台时同步悬浮窗开关状态（服务可能已被通知栏/✕关闭）
        suppressSwitch = true;
        if (monitorSwitch != null) monitorSwitch.setChecked(MonitorService.running);
        suppressSwitch = false;
        // 硬件实时状态：前台每 2 秒扫描，后台自动停止省电
        hwRunning = true;
        handler.postDelayed(hwTick, 400);
    }

    @Override
    protected void onPause() {
        super.onPause();
        hwRunning = false;
        handler.removeCallbacks(hwTick);
    }

    /** 迷你悬浮窗开关：权限检查 + 启停前台服务 */
    private void toggleMonitor(boolean on) {
        if (on) {
            if (!Settings.canDrawOverlays(this)) {
                monitorSwitch.setChecked(false);
                Toast.makeText(this, "请先授予悬浮窗权限后重试", Toast.LENGTH_LONG).show();
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
                return;
            }
            startForegroundService(new Intent(this, MonitorService.class));
        } else {
            stopService(new Intent(this, MonitorService.class));
        }
    }

    /** 刷新功耗记录卡片摘要 */
    private void refreshHistorySummary() {
        new Thread(() -> {
            final String s = PowerHistoryManager.todaySummary(this);
            runOnUiThread(() -> {
                if (powerHistorySummary != null) powerHistorySummary.setText(s);
            });
        }).start();
    }

    /** 功耗历史记录弹窗（充/放电会话） */
    private void showHistoryDialog() {
        new Thread(() -> {
            final String text = PowerHistoryManager.historyText(this, 30);
            runOnUiThread(() -> new AlertDialog.Builder(this)
                    .setTitle("功耗历史记录")
                    .setMessage(text)
                    .setPositiveButton("关闭", null)
                    .setNeutralButton("清空记录", (d, w) -> {
                        PowerHistoryManager.clear(this);
                        refreshHistorySummary();
                        Toast.makeText(this, "已清空功耗记录", Toast.LENGTH_SHORT).show();
                    })
                    .show());
        }).start();
    }

    /** 刷新帧率录制记录卡片摘要 */
    private void refreshRecordsSummary() {
        new Thread(() -> {
            final String s = FrameRecordStore.summary(this);
            runOnUiThread(() -> {
                if (frameRecordsSummary != null) frameRecordsSummary.setText(s);
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
                            .setMessage("暂无录制\n\n开启迷你悬浮窗后，点击悬浮窗上的 ● 开始录制，"
                                    + "再次点击停止并自动保存曲线图（帧率 / CPU线程负载 / CPU使用率）")
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
                        refreshRecordsSummary();
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
        sparkView.push(w);
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
            batTempC = st.tempC;
        }
    }

    // ==================== 硬件实时状态刷新 ====================

    /** 温度着色：≥75 红 ≥60 橙 其余白 */
    private static int tempColor(double t) {
        return t >= 75 ? 0xFFEF4444 : t >= 60 ? 0xFFF59E0B : 0xFFE6EDF3;
    }

    private static String fmtTemp(double t) {
        return t > 0 ? String.format(Locale.US, "%.1f℃", t) : "--";
    }

    private void updateHardware(double busy, double cpuKHz, double gpuHz,
                                double cpuT, double socT, double gpuT) {
        if (hwCpuBusy != null) {
            hwCpuBusy.setText(busy >= 0 ? String.format(Locale.US, "%.0f%%", busy) : "--");
            hwCpuBusy.setTextColor(busy >= 85 ? 0xFFEF4444
                    : busy >= 60 ? 0xFFF59E0B : 0xFFE6EDF3);
        }
        if (hwCpuTemp != null) {
            hwCpuTemp.setText(fmtTemp(cpuT));
            hwCpuTemp.setTextColor(tempColor(cpuT));
        }
        if (hwGpuFreq != null) {
            hwGpuFreq.setText(gpuHz > 0 ? String.format(Locale.US, "%.0fMHz", gpuHz / 1e6) : "--");
        }
        if (hwGpuTemp != null) {
            hwGpuTemp.setText(fmtTemp(gpuT));
            hwGpuTemp.setTextColor(tempColor(gpuT));
        }
        if (hwSocTemp != null) {
            hwSocTemp.setText(fmtTemp(socT));
            hwSocTemp.setTextColor(tempColor(socT));
        }
        if (hwCpuFreq != null) {
            hwCpuFreq.setText(cpuKHz > 0 ? String.format(Locale.US, "%.0fMHz", cpuKHz / 1000) : "--");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
    }
}
