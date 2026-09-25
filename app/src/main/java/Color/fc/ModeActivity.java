package Color.fc;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
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
 * 应用策略 —— 编辑模块 动态模式切换.conf（moren=全局默认 / 包名=模式）
 */
public class ModeActivity extends ThemedActivity {

    /** 模块工作目录（quanj.sh: mingc="qingtd"） */
    private static final String MOKML = "/sdcard/Android/qingtd";
    private static final String CONF_FILE = MOKML + "/动态模式切换.conf";
    /** 单应用负载限制：小核/大核上限%（独立文件，选择即保存） */
    private static final String LOAD_FILE = AppFreqLimiter.CONF;
    /** 模块目录内的 conf 模板（service.sh 开机恢复源） */
    private static final String MODULE_CONF_FILE = "/data/adb/modules/colorFC/qingtd/动态模式切换.conf";
    private static final String STOP_FILE = MOKML + "/stop";

    /** 模块四模式（powercfg.json 确认） */
    private static final String[][] MODES = {
            {"powersave", "省电"}, {"balance", "均衡"},
            {"performance", "性能"}, {"fast", "极速"}
    };

    private TextView ruleHint;
    private LinearLayout ruleBox;

    private String moren = "powersave";
    /** 应用规则：包名 -> 模式 */
    private final LinkedHashMap<String, String> rules = new LinkedHashMap<>();
    /** 单应用负载限制：包名 -> {小核上限%, 大核上限%}（0=未设；旧版绝对 MHz 值 >100 兼容执行） */
    private final LinkedHashMap<String, long[]> loads = new LinkedHashMap<>();
    /** 当前展开功能列表的应用行 */
    private String expandedPkg = null;
    /** 删除模式：应用行显示红 X */
    private boolean deleteMode = false;
    private TextView btnDelMode, btnDoneMode;
    private boolean confLoaded = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mode);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        setupBottomNav(R.id.navMode);
        ruleHint = findViewById(R.id.ruleHint);
        ruleBox = findViewById(R.id.ruleBox);

        // 右上角：删除模式开关（红 X 显隐）
        btnDelMode = findViewById(R.id.btnDeleteMode);
        btnDoneMode = findViewById(R.id.btnDoneMode);
        btnDelMode.setOnClickListener(v -> setDeleteMode(true));
        btnDoneMode.setOnClickListener(v -> setDeleteMode(false));

        findViewById(R.id.addRuleBtn).setOnClickListener(v -> pickApp());
        findViewById(R.id.saveRuleBtn).setOnClickListener(v -> saveConf());

        loadState();
    }

    // ==================== 状态加载 ====================

    private void loadState() {
        new Thread(() -> {
            String conf = RootShell.readFile(CONF_FILE);
            parseLoads(RootShell.readFile(LOAD_FILE));
            if (conf != null) {
                parseConf(conf);
                confLoaded = true;
                runOnUiThread(this::renderRules);
            } else {
                runOnUiThread(() -> ruleHint.setText("未读取到模块配置文件（动态模式切换.conf）"));
            }
        }).start();
    }

    /** 解析单应用负载限制文件（包名=小核上限%,大核上限%；旧版三段格式（低MHz,高MHz,占用%）忽略第三段） */
    private void parseLoads(String conf) {
        loads.clear();
        if (conf == null) return;
        for (String line : conf.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            try {
                String[] v = line.substring(eq + 1).trim().split(",");
                loads.put(line.substring(0, eq).trim(),
                        new long[]{parseMhz(v.length > 0 ? v[0] : null), parseMhz(v.length > 1 ? v[1] : null)});
            } catch (Exception ignored) {
            }
        }
    }

    private static long parseMhz(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    /** 选择即保存：写入 单应用负载.conf，并立即拉起执行服务 */
    private void writeLoads() {
        StringBuilder sb = new StringBuilder("#单应用负载：包名=小核上限%,大核上限%（0=未设；旧版绝对MHz值>100兼容）\n");
        for (Map.Entry<String, long[]> e : loads.entrySet()) {
            long[] v = e.getValue();
            if (v[0] <= 0 && v[1] <= 0) continue;
            sb.append(e.getKey()).append('=').append(v[0]).append(',').append(v[1]).append('\n');
        }
        new Thread(() -> {
            RootShell.Result r = RootShell.writeFile(getCacheDir(), sb.toString(), LOAD_FILE);
            if (!r.ok()) {
                // 写入失败必须告知：否则界面显示新值、服务仍执行旧配置（表现为限制关不掉）
                runOnUiThread(() -> Toast.makeText(this,
                        "配置写入失败：" + r.err + "，限制设置未保存", Toast.LENGTH_LONG).show());
                return;
            }
            AppLimitService.ensure(this);   // 写入完成后再启动服务，确保读到最新配置
        }).start();
    }

    // ==================== 应用策略 ====================

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
        // 全局默认行（moren）：紧凑单行，点击改模式
        ruleBox.addView(morenRow());
        for (Map.Entry<String, String> e : rules.entrySet()) {
            ruleBox.addView(ruleRow(e.getKey(), appName(e.getKey())));
        }
    }

    /** 全局默认（moren）行：未匹配应用时使用的模式 */
    private View morenRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        row.setLayoutParams(lp);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(getResources().getColor(R.color.accentSoft));
        bg.setCornerRadius(dp(10));
        row.setBackground(bg);
        row.setPadding(dp(12), dp(6), dp(12), dp(6));
        row.setOnClickListener(v -> pickMode(mode -> {
            moren = mode;
            renderRules();
            toast("全局默认已设为 " + modeName(mode) + "\n记得保存策略");
        }));

        TextView label = new TextView(this);
        label.setText("全局默认");
        label.setTextColor(getResources().getColor(R.color.textPrimary));
        label.setTextSize(13);
        label.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        LayoutParams llp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        label.setLayoutParams(llp);
        row.addView(label);

        TextView modeV = new TextView(this);
        modeV.setText(modeName(moren));
        modeV.setTextColor(getResources().getColor(R.color.accent));
        modeV.setTextSize(13);
        modeV.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        row.addView(modeV);
        return row;
    }

    /** 应用行卡片：头部（名称/包名/模式/删除）+ 展开的换行功能列表 */
    private View ruleRow(String pkg, String label) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        LayoutParams clp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(6);
        card.setLayoutParams(clp);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(getResources().getColor(R.color.bgInput));
        bg.setCornerRadius(dp(10));
        card.setBackground(bg);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setOnClickListener(v -> toggleExpand(pkg));

        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        LayoutParams ilp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        info.setLayoutParams(ilp);

        TextView name = new TextView(this);
        name.setText(label);
        name.setTextColor(getResources().getColor(R.color.textPrimary));
        name.setTextSize(13);
        name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        info.addView(name);

        TextView sub = new TextView(this);
        sub.setText(pkg);
        sub.setTextColor(getResources().getColor(R.color.textSecondary));
        sub.setTextSize(10.5f);
        info.addView(sub);
        row.addView(info);

        TextView modeV = new TextView(this);
        modeV.setText(modeName(rules.get(pkg)));
        modeV.setTextColor(getResources().getColor(R.color.accent));
        modeV.setTextSize(13);
        modeV.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        row.addView(modeV);

        // 删除模式：行尾红色 X，点击删除该策略（含单应用负载设置）
        if (deleteMode) {
            ImageView del = new ImageView(this);
            del.setImageResource(android.R.drawable.ic_delete);
            del.setColorFilter(0xFFE5484D, android.graphics.PorterDuff.Mode.SRC_ATOP);
            LayoutParams dlp = new LayoutParams(dp(26), dp(26));
            dlp.leftMargin = dp(12);
            del.setLayoutParams(dlp);
            del.setOnClickListener(v -> {
                rules.remove(pkg);
                loads.remove(pkg);
                writeLoads();
                if (pkg.equals(expandedPkg)) expandedPkg = null;
                renderRules();
            });
            row.addView(del);
        }
        card.addView(row);

        if (pkg.equals(expandedPkg)) card.addView(expandPanel(pkg));
        return card;
    }

    /** 展开/收起功能列表 */
    private void toggleExpand(String pkg) {
        expandedPkg = pkg.equals(expandedPkg) ? null : pkg;
        renderRules();
    }

    /** 删除模式开关：行尾红 X 显隐 + 顶栏按钮态 */
    private void setDeleteMode(boolean on) {
        deleteMode = on;
        btnDelMode.setBackgroundResource(on ? R.drawable.bg_tab_sel : R.drawable.bg_tab);
        btnDoneMode.setBackgroundResource(on ? R.drawable.bg_tab_sel : R.drawable.bg_tab);
        btnDoneMode.setTextColor(getResources().getColor(on ? R.color.accent : R.color.textSecondary));
        renderRules();
    }

    /** 展开的换行功能列表：模式选择 / 小核上限 / 大核上限 */
    private View expandPanel(String pkg) {
        WrapLayout wrap = new WrapLayout(this, dp(8), dp(8));
        wrap.setPadding(dp(12), 0, dp(12), dp(12));
        TextView mc = new TextView(this);
        mc.setText("模式选择");
        mc.setTextColor(getResources().getColor(R.color.accent));
        mc.setTextSize(11.5f);
        mc.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        mc.setBackground(makeChip(0x1A0077A8));
        mc.setPadding(dp(8), dp(3), dp(8), dp(3));
        mc.setOnClickListener(v -> pickMode(mode -> {
            rules.put(pkg, mode);
            renderRules();
        }));
        wrap.addView(mc);

        long[] ld = loads.get(pkg);
        wrap.addView(loadChip("小核上限", ld != null && ld[0] > 0, v -> pickCap(pkg, true)));
        wrap.addView(loadChip("大核上限", ld != null && ld[1] > 0, v -> pickCap(pkg, false)));
        return wrap;
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

    /** 负载芯片：on=已设置（橙色），点击弹频率选择 */
    private View loadChip(String text, boolean on, View.OnClickListener click) {
        TextView c = new TextView(this);
        c.setText(text);
        c.setTextColor(on ? 0xFFE8A33D : 0xFF7C8AA0);
        c.setTextSize(11.5f);
        c.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        c.setBackground(makeChip(on ? 0x1AE8A33D : 0x14808FA6));
        c.setPadding(dp(8), dp(3), dp(8), dp(3));
        c.setOnClickListener(click);
        LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(6);
        c.setLayoutParams(lp);
        return c;
    }

    /** 小核/大核上限（Kin maxL/maxB 语义）：滑条 0~100% + 小数值框（0=不限制，选择即保存） */
    private void pickCap(String pkg, boolean little) {
        long[] ld = loads.get(pkg);
        long raw = ld == null ? 0 : (little ? ld[0] : ld[1]);
        // 旧版绝对 MHz 值（>100）：按 100% 展示，保存后即转为百分比格式
        int cur = (int) Math.min(100, Math.max(0, raw));
        String msg = raw > 100
                ? "当前为旧版绝对 MHz 值（" + raw + "），保存后将转为百分比上限"
                : "按本机各簇最高频率的百分比封顶，0 = 不限制";
        sliderDialog(little ? "小核上限" : "大核上限", msg, cur,
                v -> setLoad(pkg, little ? 0 : 1, v));
    }

    private interface IntCb { void on(int v); }

    /** 滑条 0~100 + 圆角数值框联动对话框（单位 %） */
    private void sliderDialog(String title, String msg, int cur, final IntCb cb) {
        // 弹窗配色基底：跟随日/夜与沉浸背景明暗（自定义视图颜色同步，不依赖资源）
        final boolean darkBase = ThemeStore.dialogDarkBase(this);
        final int cPrimary = darkBase ? 0xFFE7EDF9 : 0xFF1B2540;
        final int cSecondary = darkBase ? 0xFF9CACCB : 0xFF5D6B85;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(4), dp(20), dp(2));

        // 同行：滑条 + 圆角数值框
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        final android.widget.SeekBar sb = new android.widget.SeekBar(this);
        sb.setMax(100);
        sb.setProgress(cur);
        sb.setLayoutParams(new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        row.addView(sb);

        LinearLayout valBox = new LinearLayout(this);
        valBox.setOrientation(LinearLayout.HORIZONTAL);
        valBox.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable vbg = new GradientDrawable();
        vbg.setColor(darkBase ? 0x26FFFFFF : 0x14808FA6);
        vbg.setCornerRadius(dp(8));
        vbg.setStroke(dp(1), darkBase ? 0x339CACCB : 0x2E0077A8);
        valBox.setBackground(vbg);
        valBox.setPadding(dp(8), 0, dp(7), 0);
        LinearLayout.LayoutParams vlp = new LayoutParams(dp(74), LayoutParams.WRAP_CONTENT);
        vlp.leftMargin = dp(12);
        valBox.setLayoutParams(vlp);

        final android.widget.EditText et = new android.widget.EditText(this);
        et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        et.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(3)});
        et.setText(String.valueOf(cur));
        et.setTextSize(13);
        et.setTextColor(cPrimary);
        et.setBackgroundColor(0);
        et.setMinHeight(0);
        et.setMinimumHeight(0);
        et.setPadding(0, dp(6), 0, dp(6));
        et.setGravity(Gravity.CENTER);
        et.setLayoutParams(new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        valBox.addView(et);

        TextView pct = new TextView(this);
        pct.setText("%");
        pct.setTextColor(cSecondary);
        pct.setTextSize(12);
        valBox.addView(pct);

        row.addView(valBox);
        box.addView(row);

        // 滑条 → 数值框
        sb.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar s, int p, boolean fromUser) {
                if (fromUser) et.setText(String.valueOf(p));
            }

            @Override
            public void onStartTrackingTouch(android.widget.SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(android.widget.SeekBar s) {
            }
        });
        // 数值框 → 滑条
        et.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void onTextChanged(CharSequence s, int st, int b, int c) {
                try {
                    int v = Integer.parseInt(s.toString());
                    if (v >= 0 && v <= 100 && v != sb.getProgress()) sb.setProgress(v);
                } catch (Exception ignored) {
                }
            }

            @Override
            public void beforeTextChanged(CharSequence s, int st, int b, int c) {
            }

            @Override
            public void afterTextChanged(android.text.Editable s) {
            }
        });

        AlertDialog dlg = new AlertDialog.Builder(ThemeStore.dialogCtx(this))
                .setTitle(title)
                .setMessage(msg)
                .setView(box)
                .setPositiveButton("确定", (d, w) -> {
                    int v;
                    try {
                        v = Integer.parseInt(et.getText().toString().trim());
                    } catch (Exception e) {
                        v = sb.getProgress();
                    }
                    cb.on(Math.min(100, Math.max(0, v)));
                })
                .setNegativeButton("取消", null)
                .show();
        ThemeStore.styleDialog(this, dlg);   // 圆角卡片 + 尺寸优化
    }

    /** idx: 0=小核上限% 1=大核上限% */
    private void setLoad(String pkg, int idx, long val) {
        long[] ld = loads.get(pkg);
        if (ld == null) ld = new long[]{0, 0};
        ld[idx] = val;
        if (ld[0] <= 0 && ld[1] <= 0) loads.remove(pkg);
        else loads.put(pkg, ld);
        writeLoads();
        renderRules();
        AppLimitService.ensure(this);   // 确保限制执行服务在跑（配置清空则服务自动退出）
    }

    /** 芯片背景：圆角色块 */
    private android.graphics.drawable.GradientDrawable makeChip(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(7));
        return g;
    }

    private void pickMode(final ModeCb cb) {
        String[] labels = new String[MODES.length];
        for (int i = 0; i < MODES.length; i++) labels[i] = MODES[i][1];
        AlertDialog dlg = new AlertDialog.Builder(ThemeStore.dialogCtx(this))
                .setTitle("选择调度模式")
                .setItems(labels, (d, which) -> cb.on(MODES[which][0]))
                .setNegativeButton("取消", null)
                .show();
        ThemeStore.styleDialog(this, dlg);
    }

    private void saveConf() {
        if (!confLoaded) {
            toast("配置尚未加载完成");
            return;
        }
        final String content = buildConf();
        new Thread(() -> {
            // 双写: 工作文件 + 模块目录模板
            // (service.sh/qingtd.sh 可能从模块模板恢复conf, 只写工作文件会被回滚)
            RootShell.Result r1 = RootShell.writeFile(getCacheDir(), content, CONF_FILE);
            boolean saved = r1.ok();
            RootShell.Result r2 = RootShell.writeFile(getCacheDir(), content, MODULE_CONF_FILE);
            if (saved) {
                // 策略由模块动态进程执行：确保它在跑（stop 清理 + 拉起）
                RootShell.exec(
                        "rm -f '" + STOP_FILE + "'"
                        + "; pgrep -f \"[q]ingtdjc\" >/dev/null 2>&1"
                        + " || nohup sh /data/adb/modules/colorFC/script/qingtd.sh >/dev/null 2>&1 &");
                // 写后2秒回读验证是否被外部回滚
                try { Thread.sleep(2000); } catch (InterruptedException ignored) { }
                String back = RootShell.readFile(CONF_FILE);
                final boolean stable = back != null && back.trim().equals(content.trim());
                runOnUiThread(() -> {
                    if (stable) {
                        toast("保存成功");
                    } else {
                        toast("保存后被系统回滚，请把此提示截图反馈");
                    }
                    loadState();
                });
            } else {
                final String err = r1.err + (r2.ok() ? "" : " | 模板写入失败");
                runOnUiThread(() -> toast("保存失败：" + err));
            }
        }).start();
    }

    // ==================== 工具 ====================

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

    /** 横向流式换行容器（功能芯片自动换行） */
    private static class WrapLayout extends android.view.ViewGroup {
        private final int hGap, vGap;

        WrapLayout(Context ctx, int hGap, int vGap) {
            super(ctx);
            this.hGap = hGap;
            this.vGap = vGap;
        }

        @Override
        protected void onMeasure(int wms, int hms) {
            int maxW = MeasureSpec.getSize(wms) - getPaddingLeft() - getPaddingRight();
            int x = 0, lineH = 0, totalH = getPaddingTop();
            for (int i = 0; i < getChildCount(); i++) {
                View ch = getChildAt(i);
                measureChild(ch, wms, hms);
                int cw = ch.getMeasuredWidth(), chh = ch.getMeasuredHeight();
                if (x > 0 && x + hGap + cw > maxW) {   // 放不下 → 换行
                    totalH += lineH + vGap;
                    x = 0;
                    lineH = 0;
                }
                x += (x > 0 ? hGap : 0) + cw;
                lineH = Math.max(lineH, chh);
            }
            totalH += lineH + getPaddingBottom();
            setMeasuredDimension(resolveSize(maxW + getPaddingLeft() + getPaddingRight(), wms),
                    resolveSize(totalH, hms));
        }

        @Override
        protected void onLayout(boolean c, int l, int t, int r, int b) {
            int x = getPaddingLeft(), y = getPaddingTop(), lineH = 0;
            int maxR = getWidth() - getPaddingRight();
            for (int i = 0; i < getChildCount(); i++) {
                View ch = getChildAt(i);
                int cw = ch.getMeasuredWidth(), chh = ch.getMeasuredHeight();
                if (x > getPaddingLeft() && x + cw > maxR) {   // 换行
                    x = getPaddingLeft();
                    y += lineH + vGap;
                    lineH = 0;
                }
                ch.layout(x, y, x + cw, y + chh);
                x += cw + hGap;
                lineH = Math.max(lineH, chh);
            }
        }
    }
}
