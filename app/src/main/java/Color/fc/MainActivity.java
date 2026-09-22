package Color.fc;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

import Color.fc.view.ChipView;
import Color.fc.view.SparkView;

/**
 * 主页：SOC 可视化 + 实时功耗（自动校准单/双电芯）+ 功能入口
 */
public class MainActivity extends Activity {

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

        findViewById(R.id.menuSchedule).setOnClickListener(v ->
                startActivity(new Intent(this, ScheduleActivity.class)));
        findViewById(R.id.menuGovernor).setOnClickListener(v ->
                startActivity(new Intent(this, GovernorActivity.class)));

        detectSoc();
        detectRoot();
        startPowerLoop();

        cellMode = getSharedPreferences("colorfc", MODE_PRIVATE).getInt("cellMode", 0);
        cellBadge.setOnClickListener(v -> showCellDialog());
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

    /** 实时功耗刷新（每秒） */
    private void startPowerLoop() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                new Thread(() -> {
                    PowerMonitor.BatteryStat st = PowerMonitor.readOnce();
                    runOnUiThread(() -> {
                        if (st != null) updatePower(st);
                    });
                    handler.postDelayed(this, 1000);
                }).start();
            }
        }, 300);
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
                            .edit().putInt("cellMode", cellMode).apply();
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
        powerStatus.setTextColor("Charging".equalsIgnoreCase(st.status) ? 0xFF10B981 : 0xFFF59E0B);

        currentValue.setText(amps != 0 ? String.format(Locale.US, "%.0f mA", Math.abs(amps) * 1000) : "--");
        voltageValue.setText(st.volts > 0 ? String.format(Locale.US, "%.2f V", st.volts) : "--");

        cellBadge.setText((cells >= 2 ? "双电芯" : "单电芯")
                + (cellMode == 0 ? " · 已校准 ▸" : " · 手动 ▸"));
        if (st.level >= 0) batteryLevel.setText(st.level + "%");
        if (st.tempC > 0) batteryTemp.setText(String.format(Locale.US, "%.1f℃", st.tempC));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
    }
}
