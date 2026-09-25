package Color.fc;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import Color.fc.view.Warp;

/**
 * 主页：四模式快切（省电/均衡/性能/极速），仅保留模式切换功能。
 * 与 WebUI 模式快切同一套机制：main.sh 立即切换 + moren= 持久化为默认模式
 * （不写 moren 会被前台监视 qtbh.sh 在下次前台变化时切回省电）。
 */
public class MainActivity extends ThemedActivity {

    /** 悬浮窗权限申请请求码 */
    private static final int REQ_OVERLAY = 0x6CB2;
    /** 模块目录（module.prop id=colorFC） */
    private static final String MOD = "/data/adb/modules/colorFC";
    /** 当前模式记录文件（main.sh 切换时写入） */
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

    private TextView rootBadge;
    private TextView curModeView;
    /** 模式芯片容器与标签（当前模式高亮用） */
    private LinearLayout modeChipsBox;
    private View[] chipViews;
    private TextView[] chipLabels;
    /** 当前运行模式（null = 未知/尚未切换） */
    private String curMode = null;
    /** 模式应用中（防连点） */
    private boolean applying = false;

    /** 悬浮窗管理按钮（点击弹出监视悬浮窗功能列表） */
    private TextView monitorToggle;
    /** 悬浮窗管理弹窗实例（授权返回后同步开关状态用） */
    private OverlayManagerSheet ovlSheet;
    /** 等待悬浮窗权限授权的监视器类型 */
    private int pendingOvl = -1;

    static SocInfo cachedSoc;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        rootBadge = findViewById(R.id.rootBadge);
        curModeView = findViewById(R.id.curMode);
        modeChipsBox = findViewById(R.id.modeChips);

        // 主题设置入口
        findViewById(R.id.menuTheme).setOnClickListener(v ->
                startActivity(new Intent(this, ThemeActivity.class)));

        detectSoc();
        detectRoot();
        buildChips();
        refreshMode();
        AppLimitService.ensure(this);   // 已配置单应用负载限制则确保执行服务在跑

        // 悬浮窗管理：点击弹出监视悬浮窗功能列表（负载/进程/迷你/温度，各自开关）
        monitorToggle = findViewById(R.id.monitorToggle);
        monitorToggle.setOnClickListener(v -> showOverlayManager());

        // 底部导航栏
        setupBottomNav(R.id.navHome);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** 检测 SOC 型号（供其它页面缓存使用） */
    private void detectSoc() {
        if (cachedSoc != null) return;
        new Thread(() -> cachedSoc = SocInfo.autoDetect()).start();
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

    // ==================== 四模式快切 ====================

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

    /** 按当前模式刷新芯片高亮与"当前"文字 */
    private void renderChips() {
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

    /** 读取当前运行模式（cur_powermode.txt；未知内容视为未应用） */
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

    /** 应用模式：main.sh 立即切换 + moren= 持久化（与 WebUI applyMode 完全一致） */
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
            // 方案配置（peiz）：与 WebUI 一致，缺省 all
            String peiz = RootShell.readFile(MOD + "/files/peiz");
            if (peiz == null || peiz.trim().isEmpty()) peiz = "all";
            RootShell.Result r = RootShell.exec("sh " + MOD + "/script/main.sh " + mode
                    + " " + MOD + "/files " + peiz.trim(), 20);
            // 持久化默认模式 moren=：写回 动态模式切换.conf，
            // 否则前台监视(qtbh.sh)在下一次前台变化时按 moren 切回省电；
            // 只用 ASCII glob 定位 conf，避免中文文件名兼容问题
            RootShell.exec("cd " + MOKML + " 2>/dev/null && for f in *.conf; do "
                    + "[ -f \"$f\" ] || continue; "
                    + "grep -q '^moren=' \"$f\" && sed -i 's/^moren=.*/moren=" + mode + "/' \"$f\" "
                    + "|| echo \"moren=" + mode + "\" >> \"$f\"; done; true");
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
                    refreshMode();   // 回读真实状态
                }
            });
        }).start();
    }

    // ==================== 悬浮窗管理（负载/进程/迷你/温度）====================

    @Override
    protected void onResume() {
        super.onResume();
        // 回前台重读当前模式：可能被前台监视(qtbh.sh)或 WebUI 切换过
        refreshMode();
        // 授权返回：自动开启等待中的监视器
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

    /** 点击"悬浮窗"：弹出监视悬浮窗功能列表（负载/进程/迷你/温度各自开关） */
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

    /** 监视器开关落地：持久化 + 显示/隐藏对应悬浮窗 */
    private void setOvlEnabled(int type, boolean on) {
        getSharedPreferences("colorfc", MODE_PRIVATE)
                .edit().putBoolean(MonitorService.PREF_KEYS[type], on).apply();
        if (on) MonitorService.show(this, type);
        else MonitorService.hide(this, type);
        syncMonitorUi();
    }
}
