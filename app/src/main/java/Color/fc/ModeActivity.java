package Color.fc;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
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
 * 调度管理：
 * 1. 全局模式切换/恢复 —— /data/powercfg.sh 切换入口 + stop 文件
 * 2. 应用策略 —— 编辑 动态模式切换.conf（moren=全局默认 / 包名=模式）
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

    private TextView curModeView, ruleHint;
    private LinearLayout modeBox, ruleBox;

    private String curMode = "";
    private boolean takenOver = false;   // 是否处于接管状态（stop 文件存在）
    private String moren = "powersave";
    /** 应用规则：包名 -> 模式 */
    private final LinkedHashMap<String, String> rules = new LinkedHashMap<>();
    private boolean confLoaded = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mode);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        curModeView = findViewById(R.id.curModeView);
        ruleHint = findViewById(R.id.ruleHint);
        modeBox = findViewById(R.id.modeBox);
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
            final boolean taken = stop.ok() && !stop.out.trim().isEmpty();
            final String mode = cur == null ? "" : cur.trim();
            runOnUiThread(() -> {
                curMode = mode;
                takenOver = taken;
                applyModeState();
            });
            if (conf != null) {
                parseConf(conf);
                confLoaded = true;
                runOnUiThread(this::renderRules);
            } else {
                runOnUiThread(() -> ruleHint.setText("未读取到模块配置文件（动态模式切换.conf）"));
            }
        }).start();
    }

    private void applyModeState() {
        String disp = modeName(curMode);
        curModeView.setText(takenOver
                ? String.format("当前模式：%s（已切换）", disp)
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
        // 先 source 模块变量(quanj.sh)保证 $mokml/$MODULE_PATH 就绪，
        // 并复位 qhz 防堵塞标记，再执行模块切换入口
        final String cmd = ". /data/adb/modules/colorFC/script/quanj.sh 2>/dev/null;"
                + "[ -f \"$mosdz/qhz\" ] && echo 1 > \"$mosdz/qhz\";"
                + "sh '" + POWERCFG + "' " + mode + "; echo EXIT_$?";
        new Thread(() -> {
            RootShell.Result r = RootShell.exec(cmd, 15);
            String cur = RootShell.readFile(CUR_MODE_FILE);
            String log = lastLogLine();
            final boolean applied = mode.equals(cur == null ? "" : cur.trim());
            runOnUiThread(() -> {
                if (applied) {
                    toast("已切换：" + modeName(mode));
                } else if (r.out != null && r.out.contains("EXIT_0")) {
                    toast("已切换（模块日志：\n" + (log.isEmpty() ? "无新日志" : log) + "）");
                } else {
                    toast("切换失败：" + (r.err != null && !r.err.isEmpty() ? r.err : "模块无响应"));
                }
                loadState();
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

    /** 模块日志末行（反馈用） */
    private String lastLogLine() {
        RootShell.Result r = RootShell.exec("tail -1 '" + MOKML + "/logs.txt' 2>/dev/null");
        return r.out == null ? "" : r.out.trim();
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
        // moren 不再显示卡片(与全局模式切换功能重复), conf 中原值保留
        for (Map.Entry<String, String> e : rules.entrySet()) {
            ruleBox.addView(ruleRow(e.getKey(), appName(e.getKey()), e.getValue(), v ->
                    pickMode(mode -> {
                        rules.put(e.getKey(), mode);
                        renderRules();
                    })));
        }
    }

    /** 一条规则行：点击整行改模式，右侧 × 删除 */
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
        sub.setText(pkg);
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
        return row;
    }

    private void pickApp() {
        if (!confLoaded) {
            toast("配置尚未加载完成");
            return;
        }
        // 底部收纳式应用菜单：可搜索、带图标、已配置应用带模式徽章
        AppPickerDialog.show(this, rules, (pkg, label) ->
                pickMode(mode -> {
                    rules.put(pkg, mode);
                    renderRules();
                    toast("已添加：" + label + " → " + modeName(mode) + "\n记得保存策略");
                }));
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
            final boolean saved = r.ok();
            if (saved) {
                // 策略由模块动态进程执行：保存后确保它在跑（stop 清理 + 拉起）
                RootShell.exec(
                        "rm -f '" + STOP_FILE + "'"
                        + "; pgrep -f \"[q]ingtdjc\" >/dev/null 2>&1"
                        + " || nohup sh /data/adb/modules/colorFC/script/qingtd.sh >/dev/null 2>&1 &");
            }
            runOnUiThread(() -> {
                if (saved) {
                    toast("保存成功，动态切换已就绪");
                    loadState();
                } else {
                    toast("保存失败");
                }
            });
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
