package Color.fc;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 调度接管（Scene 式）：
 * 1. 全局模式接管/恢复 —— /data/powercfg.sh 外部控制接口 + stop 文件
 * 2. 刷新率管理 —— 扫描系统全部档位并锁定/恢复自动
 * 3. 应用策略 —— 编辑 动态模式切换.conf（moren=全局默认 / 包名=模式）
 */
public class ModeActivity extends Activity {

    /** 模块工作目录（quanj.sh: mingc="qingtd"） */
    private static final String MOKML = "/sdcard/Android/qingtd";
    private static final String CUR_MODE_FILE = MOKML + "/cur_powermode.txt";
    private static final String CONF_FILE = MOKML + "/动态模式切换.conf";
    private static final String STOP_FILE = MOKML + "/stop";
    private static final String POWERCFG = "/data/powercfg.sh";

    /** 模块四模式（powercfg.json 确认） */
    private static final String[][] MODES = {
            {"powersave", "省电"}, {"balance", "均衡"},
            {"performance", "性能"}, {"fast", "极速"}
    };

    private TextView curModeView, curRefreshView, ruleHint;
    private LinearLayout modeBox, refreshBox, ruleBox;

    private String curMode = "";
    private boolean takenOver = false;   // 是否处于接管状态（stop 文件存在）
    private String moren = "powersave";
    /** 应用规则：包名 -> 模式 */
    private final LinkedHashMap<String, String> rules = new LinkedHashMap<>();
    private String pinnedHz = null;     // 刷新率锁定值，null=自动
    private boolean confLoaded = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mode);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        curModeView = findViewById(R.id.curModeView);
        curRefreshView = findViewById(R.id.curRefreshView);
        ruleHint = findViewById(R.id.ruleHint);
        modeBox = findViewById(R.id.modeBox);
        refreshBox = findViewById(R.id.refreshBox);
        ruleBox = findViewById(R.id.ruleBox);

        findViewById(R.id.addRuleBtn).setOnClickListener(v -> pickApp());
        findViewById(R.id.saveRuleBtn).setOnClickListener(v -> saveConf());

        loadState();
    }

    // ==================== 状态加载 ====================

    private void loadState() {
        new Thread(() -> {
            // 当前模式 + 接管状态
            String cur = RootShell.readFile(CUR_MODE_FILE);
            RootShell.Result stop = RootShell.exec("ls '" + STOP_FILE + "' 2>/dev/null");
            // conf
            String conf = RootShell.readFile(CONF_FILE);
            // 当前刷新率锁定状态
            RootShell.Result peak = RootShell.exec("settings get system peak_refresh_rate");
            final boolean taken = stop.ok() && !stop.out.trim().isEmpty();
            final String mode = cur == null ? "" : cur.trim();
            final String pin = (peak.ok() && !peak.out.trim().isEmpty()
                    && !"null".equals(peak.out.trim())) ? peak.out.trim() : null;
            runOnUiThread(() -> {
                curMode = mode;
                takenOver = taken;
                pinnedHz = pin;
                applyModeState();
                applyRefreshState();
            });
            if (conf != null) {
                parseConf(conf);
                confLoaded = true;
                runOnUiThread(this::renderRules);
            } else {
                runOnUiThread(() -> ruleHint.setText("未读取到模块配置文件（动态模式切换.conf）"));
            }
        }).start();
        renderRefreshChips();
    }

    private void applyModeState() {
        String disp = modeName(curMode);
        curModeView.setText(takenOver
                ? String.format("当前模式：%s（已接管固定）", disp)
                : String.format("当前模式：%s（模块动态切换中）", disp));
        renderModeButtons();
    }

    // ==================== 区块1: 全局模式接管 ====================

    private void renderModeButtons() {
        modeBox.removeAllViews();
        // 2x2 模式按钮
        LinearLayout row1 = newRow();
        LinearLayout row2 = newRow();
        for (int i = 0; i < MODES.length; i++) {
            final String key = MODES[i][0];
            TextView btn = chipButton(modeName(key), MODES[i][0].equals(curMode) && takenOver,
                    v -> switchMode(key));
            LinearLayout row = (i < 2) ? row1 : row2;
            row.addView(btn);
        }
        modeBox.addView(row1);
        modeBox.addView(row2);
        // 恢复动态切换
        TextView restore = chipButton("恢复动态切换（交还模块）", false, v -> restoreDynamic());
        restore.setTextColor(0xFFA02CF0);
        modeBox.addView(restore);
    }

    private LinearLayout newRow() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        r.setLayoutParams(lp);
        return r;
    }

    private void switchMode(String mode) {
        // Scene 同款外部控制：sh /data/powercfg.sh <模式>（创建 stop，固定全局模式）
        new Thread(() -> {
            RootShell.Result r = RootShell.exec("sh '" + POWERCFG + "' " + mode
                    + " && echo done");
            runOnUiThread(() -> {
                if (r.out != null && r.out.contains("done")) {
                    toast("已接管：" + modeName(mode));
                    loadState();
                } else {
                    toast("切换失败，请检查模块是否安装");
                }
            });
        }).start();
    }

    private void restoreDynamic() {
        // stop 时模块已 kill 动态进程(qingtdjc)，恢复需删除 stop 并重新拉起
        // 注意: pgrep -f "[q]ingtdjc" 用正则技巧避免匹配到执行命令的 shell 自身
        final String cmd = "rm -f '" + STOP_FILE + "'"
                + "; pgrep -f \"[q]ingtdjc\" >/dev/null 2>&1"
                + " || nohup sh /data/adb/modules/colorFC/script/qingtd.sh >/dev/null 2>&1 &";
        new Thread(() -> {
            RootShell.exec(cmd);
            runOnUiThread(() -> {
                toast("已恢复动态切换");
                loadState();
            });
        }).start();
    }

    // ==================== 区块2: 刷新率管理 ====================

    private void renderRefreshChips() {
        refreshBox.removeAllViews();
        List<Float> hzs = scanRefreshRates();
        LinearLayout row = newRow();
        int inRow = 0;
        // "自动" 档
        TextView auto = chipButton("自动", pinnedHz == null, v -> pinRefresh(null));
        row.addView(auto);
        inRow++;
        for (float hz : hzs) {
            final String label = String.format(Locale.US, "%.0f Hz", hz);
            final String val = String.format(Locale.US, "%.1f", hz);
            TextView c = chipButton(label, pinnedHz != null && val.equals(pinnedHz)
                    || pinnedHz != null && label.equals(pinnedHz + " Hz")
                    || (pinnedHz != null && Math.abs(Float.parseFloat(pinnedHz) - hz) < 0.05f),
                    v -> pinRefresh(val));
            if (inRow == 3) {
                refreshBox.addView(row);
                row = newRow();
                inRow = 0;
            }
            row.addView(c);
            inRow++;
        }
        if (inRow > 0) refreshBox.addView(row);
    }

    private void applyRefreshState() {
        curRefreshView.setText(pinnedHz == null
                ? String.format("当前状态：自动（%s）", getRealRefresh())
                : String.format("当前状态：锁定 %s Hz", pinnedHz.endsWith(".0")
                        ? pinnedHz.substring(0, pinnedHz.length() - 2)
                        : pinnedHz));
    }

    private String getRealRefresh() {
        try {
            DisplayManager dm = (DisplayManager) getSystemService(DISPLAY_SERVICE);
            Display.Mode m = dm.getDisplay(Display.DEFAULT_DISPLAY).getMode();
            return String.format(Locale.US, "%.0f Hz", m.getRefreshRate());
        } catch (Exception e) {
            return "";
        }
    }

    /** 扫描系统支持的全部刷新率档位（无需 root） */
    private List<Float> scanRefreshRates() {
        List<Float> hzs = new ArrayList<>();
        try {
            DisplayManager dm = (DisplayManager) getSystemService(DISPLAY_SERVICE);
            Display.Mode[] modes = dm.getDisplay(Display.DEFAULT_DISPLAY).getSupportedModes();
            for (Display.Mode m : modes) {
                float hz = m.getRefreshRate();
                boolean dup = false;
                for (float h : hzs) if (Math.abs(h - hz) < 0.05f) { dup = true; break; }
                if (!dup) hzs.add(hz);
            }
            Collections.sort(hzs);
        } catch (Exception ignored) {
        }
        if (hzs.isEmpty()) {
            // 回退：常见档位
            hzs.add(60f); hzs.add(90f); hzs.add(120f);
        }
        return hzs;
    }

    private void pinRefresh(String hz) {
        final String cmd = hz == null
                ? "settings delete system peak_refresh_rate; settings delete system min_refresh_rate"
                : "settings put system peak_refresh_rate " + hz
                  + " && settings put system min_refresh_rate " + hz;
        new Thread(() -> {
            RootShell.exec(cmd);
            pinnedHz = hz;
            runOnUiThread(() -> {
                applyRefreshState();
                renderRefreshChips();
                toast(hz == null ? "已恢复自动刷新率" : "已锁定 " + hz + " Hz");
            });
        }).start();
    }

    // ==================== 区块3: 应用策略 ====================

    private void parseConf(String conf) {
        rules.clear();
        moren = "powersave";
        for (String line : conf.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String key = line.substring(0, eq).trim();
            String val = line.substring(eq + 1).trim();
            if ("moren".equals(key)) moren = val;
            else rules.put(key, val);
        }
    }

    private String buildConf() {
        StringBuilder sb = new StringBuilder();
        sb.append("#moren是全局默认的意思，包名等号加模式（实时的）\n");
        sb.append("#模式powersave、balance、performance、fast\n");
        sb.append("moren=").append(moren).append("\n");
        for (Map.Entry<String, String> e : rules.entrySet()) {
            sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        }
        return sb.toString();
    }

    private void renderRules() {
        ruleBox.removeAllViews();
        // 全局默认行（moren）
        ruleBox.addView(ruleRow("moren", "全局默认（未匹配应用时）", moren, v ->
                pickMode(mode -> {
                    moren = mode;
                    renderRules();
                })));
        for (Map.Entry<String, String> e : rules.entrySet()) {
            ruleBox.addView(ruleRow(e.getKey(), appName(e.getKey()), e.getValue(), v ->
                    pickMode(mode -> {
                        rules.put(e.getKey(), mode);
                        renderRules();
                    })));
        }
    }

    /** 一条规则行：点击整行改模式，右侧 × 删除（moren 行无删除） */
    private View ruleRow(String pkg, String label, String mode, View.OnClickListener onEdit) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        row.setLayoutParams(lp);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFF2F7FC);
        bg.setCornerRadius(dp(10));
        row.setBackground(bg);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setOnClickListener(onEdit);

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        LayoutParams ilp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        info.setLayoutParams(ilp);

        TextView name = new TextView(this);
        name.setText(label);
        name.setTextColor(0xFF1B2540);
        name.setTextSize(13);
        name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        info.addView(name);

        TextView sub = new TextView(this);
        sub.setText("moren".equals(pkg) ? "moren=" + moren : pkg);
        sub.setTextColor(0xFF5D6B85);
        sub.setTextSize(10.5f);
        info.addView(sub);
        row.addView(info);

        TextView modeV = new TextView(this);
        modeV.setText(modeName(mode));
        modeV.setTextColor(0xFF0096C8);
        modeV.setTextSize(13);
        modeV.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        row.addView(modeV);

        if (!"moren".equals(pkg)) {
            ImageView del = new ImageView(this);
            del.setImageResource(android.R.drawable.ic_delete);
            del.setColorFilter(0xFFE5484D, android.graphics.PorterDuff.Mode.SRC_ATOP);
            LayoutParams dlp = new LayoutParams(dp(26), dp(26));
            dlp.leftMargin = dp(12);
            del.setLayoutParams(dlp);
            del.setOnClickListener(v -> {
                rules.remove(pkg);
                renderRules();
            });
            row.addView(del);
        }
        return row;
    }

    private void pickApp() {
        if (!confLoaded) {
            toast("配置尚未加载完成");
            return;
        }
        final List<ResolveInfo> apps = new ArrayList<>();
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_LAUNCHER);
            apps.addAll(getPackageManager().queryIntentActivities(i, 0));
        } catch (Exception ignored) {
        }
        if (apps.isEmpty()) {
            toast("未扫描到应用");
            return;
        }
        Collections.sort(apps, (a, b) -> String.valueOf(a.loadLabel(getPackageManager()))
                .compareTo(String.valueOf(b.loadLabel(getPackageManager()))));
        final String[] names = new String[apps.size()];
        final String[] pkgs = new String[apps.size()];
        for (int i = 0; i < apps.size(); i++) {
            names[i] = String.valueOf(apps.get(i).loadLabel(getPackageManager()));
            pkgs[i] = apps.get(i).activityInfo.packageName;
        }
        new AlertDialog.Builder(this)
                .setTitle("选择应用")
                .setItems(names, (d, which) -> {
                    final String pkg = pkgs[which];
                    if (rules.containsKey(pkg)) {
                        toast(names[which] + " 已有规则，点击该行可修改");
                        return;
                    }
                    pickMode(mode -> {
                        rules.put(pkg, mode);
                        renderRules();
                        toast("已添加：" + names[which] + " → " + modeName(mode) + "\n记得保存策略");
                    });
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private interface ModeCb { void on(String mode); }

    private void pickMode(final ModeCb cb) {
        String[] labels = new String[MODES.length];
        for (int i = 0; i < MODES.length; i++) labels[i] = MODES[i][1];
        new AlertDialog.Builder(this)
                .setTitle("选择调度模式")
                .setItems(labels, (d, which) -> cb.on(MODES[which][0]))
                .setNegativeButton("取消", null)
                .show();
    }

    private void saveConf() {
        if (!confLoaded) {
            toast("配置尚未加载完成");
            return;
        }
        final String content = buildConf();
        new Thread(() -> {
            RootShell.Result r = RootShell.writeFile(getCacheDir(), content, CONF_FILE);
            runOnUiThread(() -> toast(r.ok() ? "保存成功" : "保存失败"));
        }).start();
    }

    // ==================== 工具 ====================

    private TextView chipButton(String text, boolean selected, View.OnClickListener onClick) {
        TextView v = new TextView(this);
        LayoutParams lp = new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f);
        v.setLayoutParams(lp);
        v.setGravity(Gravity.CENTER);
        v.setMinHeight(dp(44));
        v.setText(text);
        v.setTextSize(13.5f);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(selected ? 0xFFE5F7FD : 0xFFF2F7FC);
        bg.setCornerRadius(dp(10));
        if (selected) {
            bg.setStroke(dp(1), 0xFF0096C8);
            v.setTextColor(0xFF0096C8);
        } else {
            v.setTextColor(0xFF1B2540);
        }
        v.setBackground(bg);
        v.setOnClickListener(onClick);
        return v;
    }

    private String modeName(String key) {
        for (String[] m : MODES) if (m[0].equals(key)) return m[1];
        return key == null || key.isEmpty() ? "未知" : key;
    }

    private String appName(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return String.valueOf(pm.getApplicationLabel(ai));
        } catch (Exception e) {
            return pkg;
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    /** LayoutParams 快捷引用（父容器为 LinearLayout） */
    private static class LayoutParams extends android.widget.LinearLayout.LayoutParams {
        public LayoutParams(int w, int h) { super(w, h); }
        public LayoutParams(int w, int h, float weight) { super(w, h, weight); }
    }
}
