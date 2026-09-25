package Color.fc;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.graphics.PorterDuff;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.animation.RotateAnimation;
import android.view.animation.Animation;
import android.view.animation.DecelerateInterpolator;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 调度参数：自动检测 SOC，加载对应 a.all.sh / b.all.sh，
 * 按模式（省电/均衡/性能/极速）展开参数编辑
 */
public class ScheduleActivity extends ThemedActivity {

    private AllConfig cfgA, cfgB;
    private String tab = "a";
    private boolean dirty = false;
    private boolean loading = true;
    private SocInfo soc;

    private final HashMap<String, EditText> inputs = new HashMap<>();
    private final HashMap<String, SeekBar> seekBars = new HashMap<>();
    private boolean syncing = false;
    private TextView tabAV, tabBV, socTip;

    /** 调度参数字段（fillInputs / collectCurrent / lax 共用） */
    private static final String[] FIELDS = {"opt2", "cpuMax", "cpuMin", "llcc", "uclampDisplay",
            "uclampSsfg", "uclampTouch", "uclampMm", "uclampRt", "uclampTopApp", "walt1", "walt2"};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_schedule);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        setupBottomNav(R.id.navSchedule);
        socTip = findViewById(R.id.socTip);
        tabAV = findViewById(R.id.tabA);
        tabBV = findViewById(R.id.tabB);

        soc = MainActivity.cachedSoc;
        if (soc == null) soc = SocInfo.autoDetect();

        String fileA = RootShell.CONFIG_DIR + "/a.all.sh";
        String fileB = RootShell.CONFIG_DIR + "/b.all.sh";
        socTip.setText(soc.known
                ? String.format(Locale.US, "当前 SOC：%s（%s）→ 加载方案 %s", soc.code, soc.marketing,
                        "a".equals(soc.config) ? "1" : "2")
                : "检测错误，默认加载方案1");

        tabAV.setOnClickListener(v -> switchTab("a"));
        tabBV.setOnClickListener(v -> switchTab("b"));
        tab = soc.config; // 默认选中 SOC 对应配置

        findViewById(R.id.saveBtn).setOnClickListener(v -> saveConfig());
        findViewById(R.id.btnImport).setOnClickListener(v -> importLax());
        findViewById(R.id.btnExport).setOnClickListener(v -> exportLax());
        findViewById(R.id.btnReset).setOnClickListener(v -> resetDefaults());

        buildCards();

        // 后台加载两份配置
        new Thread(() -> {
            String a = RootShell.readFile(fileA);
            String b = RootShell.readFile(fileB);
            cfgA = AllConfig.parse(a);
            cfgB = AllConfig.parse(b);
            if (cfgA == null) cfgA = AllConfig.defaults();
            if (cfgB == null) cfgB = AllConfig.defaults();
            runOnUiThread(() -> {
                loading = false;
                applyTabStyle();
                fillInputs();
            });
        }).start();
    }

    /** 构建 4 个模式卡片（收起状态，点击展开参数） */
    private void buildCards() {
        LinearLayout container = findViewById(R.id.modesContainer);
        int[] colors = {0xFF00B5A3, 0xFF0096C8, 0xFFE08A00, 0xFFA02CF0};

        for (int i = 0; i < AllConfig.MODE_KEYS.length; i++) {
            String key = AllConfig.MODE_KEYS[i];
            View card = getLayoutInflater().inflate(R.layout.mode_card, container, false);

            View dot = card.findViewById(R.id.modeDot);
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(colors[i]);
            dot.setBackground(d);

            ((TextView) card.findViewById(R.id.modeTitle)).setText(AllConfig.MODE_NAMES[i]);
            ((TextView) card.findViewById(R.id.modeSubtitle)).setText(AllConfig.MODE_DESC[i]);

            LinearLayout box = card.findViewById(R.id.paramsBox);
            ImageView arrow = card.findViewById(R.id.expandArrow);
            card.findViewById(R.id.cardHeader).setOnClickListener(v -> {
                boolean expand = box.getVisibility() != View.VISIBLE;
                box.setVisibility(expand ? View.VISIBLE : View.GONE);
                rotateArrow(arrow, expand);
            });

            addParam(box, key + ".opt2", "调度增强等级 opt2（0-100，越高越激进，省电设 0）");
            addParam(box, key + ".cpuMax", "CPU 最高频率百分比（json_cpu_max_min 参数一）");
            addParam(box, key + ".cpuMin", "CPU 最低频率百分比（json_cpu_max_min 参数二）");
            addParam(box, key + ".llcc", "LLCC 系统缓存最大频率（Hz）");
            addParam(box, key + ".uclampDisplay", "display 显示任务 uclamp 最低提升");
            addParam(box, key + ".uclampSsfg", "ssfg 前台服务组 uclamp 最低提升");
            addParam(box, key + ".uclampTouch", "touch 触控线程 uclamp 最低提升（影响跟手性）");
            addParam(box, key + ".uclampMm", "multimedia 多媒体任务 uclamp 最低提升");
            addParam(box, key + ".uclampRt", "rt 实时任务 uclamp 最低提升");
            addParam(box, key + ".uclampTopApp", "top-app 前台应用 uclamp 最低提升");
            if ("fast".equals(key)) {
                addParam(box, key + ".walt1", "WALT 提频速率限制 参数一（µs）");
                addParam(box, key + ".walt2", "WALT 提频速率限制 参数二（µs）");
            }
            container.addView(card);
        }
    }

    private void rotateArrow(ImageView arrow, boolean expand) {
        RotateAnimation ra = new RotateAnimation(
                expand ? 0 : 180, expand ? 180 : 0,
                Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f);
        ra.setDuration(200);
        ra.setInterpolator(new DecelerateInterpolator());
        ra.setFillAfter(true);
        arrow.startAnimation(ra);
    }

    /** 参数滑条范围：min~max，步进 step */
    private static class ParamRange {
        final long min, max, step;
        ParamRange(long min, long max, long step) {
            this.min = min;
            this.max = max;
            this.step = step;
        }
        int maxProgress() {
            return (int) ((max - min) / step);
        }
        int toProgress(long v) {
            if (v < min) v = min;
            if (v > max) v = max;
            return (int) ((v - min) / step);
        }
        long toValue(int p) {
            long v = min + p * step;
            return v > max ? max : v;
        }
    }

    /** 按参数 key 确定滑条范围 */
    private static ParamRange rangeOf(String key) {
        String f = key.substring(key.indexOf('.') + 1);
        if ("llcc".equals(f)) return new ParamRange(300000, 1800000, 10000);
        if ("walt1".equals(f) || "walt2".equals(f)) return new ParamRange(0, 2000, 20);
        // opt2 / cpuMax / cpuMin / uclamp* 均为百分比
        return new ParamRange(0, 100, 1);
    }

    /** 添加一个带说明、滑条+输入框联动的参数行 */
    private void addParam(LinearLayout box, String key, String label) {
        View row = getLayoutInflater().inflate(R.layout.param_row, box, false);
        ((TextView) row.findViewById(R.id.label)).setText(label);
        EditText et = row.findViewById(R.id.input);
        SeekBar sb = row.findViewById(R.id.seek);
        ParamRange pr = rangeOf(key);
        sb.setMax(pr.maxProgress());

        // 霓虹青配色（白底下可见）
        try {
            sb.getProgressDrawable().setColorFilter(0xFF0096C8, PorterDuff.Mode.SRC_IN);
            sb.getThumb().setColorFilter(0xFF0096C8, PorterDuff.Mode.SRC_IN);
        } catch (Exception ignored) {
        }

        // 拖动滑条 → 数值写入输入框
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (!fromUser || syncing) return;
                syncing = true;
                et.setText(String.valueOf(pr.toValue(p)));
                syncing = false;
                if (!loading) dirty = true;
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });

        // 输入数值 → 滑条跟随
        et.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b2, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b2, int c) {}
            @Override public void afterTextChanged(Editable s) {
                if (!loading) dirty = true;
                if (syncing) return;
                try {
                    long v = Long.parseLong(s.toString().trim());
                    syncing = true;
                    sb.setProgress(pr.toProgress(v));
                    syncing = false;
                } catch (Exception ignored) {
                }
            }
        });
        inputs.put(key, et);
        seekBars.put(key, sb);
        box.addView(row);
    }

    /** 把当前配置填充到输入框（默认参数可视化） */
    private void fillInputs() {
        AllConfig cfg = currentCfg();
        AllConfig def = AllConfig.defaults();
        for (String mode : AllConfig.MODE_KEYS) {
            AllConfig.Mode m = cfg.modes.get(mode);
            AllConfig.Mode dm = def.modes.get(mode);
            set("opt2", mode, m, dm);
            set("cpuMax", mode, m, dm);
            set("cpuMin", mode, m, dm);
            set("llcc", mode, m, dm);
            set("uclampDisplay", mode, m, dm);
            set("uclampSsfg", mode, m, dm);
            set("uclampTouch", mode, m, dm);
            set("uclampMm", mode, m, dm);
            set("uclampRt", mode, m, dm);
            set("uclampTopApp", mode, m, dm);
            set("walt1", mode, m, dm);
            set("walt2", mode, m, dm);
        }
        dirty = false;
    }

    private void set(String field, String mode, AllConfig.Mode m, AllConfig.Mode dm) {
        EditText et = inputs.get(mode + "." + field);
        if (et == null) return;
        String v = value(m, field);
        if (v == null || v.isEmpty()) v = value(dm, field);
        et.setText(v == null ? "" : v);
    }

    private String value(AllConfig.Mode m, String f) {
        switch (f) {
            case "opt2": return m.opt2;
            case "cpuMax": return m.cpuMax;
            case "cpuMin": return m.cpuMin;
            case "llcc": return m.llcc;
            case "uclampDisplay": return m.uclampDisplay;
            case "uclampSsfg": return m.uclampSsfg;
            case "uclampTouch": return m.uclampTouch;
            case "uclampMm": return m.uclampMm;
            case "uclampRt": return m.uclampRt;
            case "uclampTopApp": return m.uclampTopApp;
            case "walt1": return m.walt1;
            case "walt2": return m.walt2;
        }
        return "";
    }

    private void assign(AllConfig.Mode m, String f, String v) {
        switch (f) {
            case "opt2": m.opt2 = v; break;
            case "cpuMax": m.cpuMax = v; break;
            case "cpuMin": m.cpuMin = v; break;
            case "llcc": m.llcc = v; break;
            case "uclampDisplay": m.uclampDisplay = v; break;
            case "uclampSsfg": m.uclampSsfg = v; break;
            case "uclampTouch": m.uclampTouch = v; break;
            case "uclampMm": m.uclampMm = v; break;
            case "uclampRt": m.uclampRt = v; break;
            case "uclampTopApp": m.uclampTopApp = v; break;
            case "walt1": m.walt1 = v; break;
            case "walt2": m.walt2 = v; break;
        }
    }

    private AllConfig currentCfg() {
        return "a".equals(tab) ? cfgA : cfgB;
    }

    /** 切换 a / b 配置页签 */
    private void switchTab(String t) {
        if (t.equals(tab)) return;
        if (dirty) {
            new AlertDialog.Builder(this)
                    .setTitle("未保存的修改")
                    .setMessage("当前方案已修改但未保存，切换后将丢失，是否继续？")
                    .setPositiveButton("继续", (d, w) -> doSwitch(t))
                    .setNegativeButton("取消", null)
                    .show();
        } else {
            doSwitch(t);
        }
    }

    private void doSwitch(String t) {
        tab = t;
        applyTabStyle();
        fillInputs();
    }

    private void applyTabStyle() {
        boolean isA = "a".equals(tab);
        tabAV.setBackgroundResource(isA ? R.drawable.bg_tab_sel : R.drawable.bg_tab);
        tabAV.setTextColor(isA ? 0xFF0077A8 : 0xFF7C8AA0);
        tabBV.setBackgroundResource(isA ? R.drawable.bg_tab : R.drawable.bg_tab_sel);
        tabBV.setTextColor(isA ? 0xFF7C8AA0 : 0xFF0077A8);
    }

    /** 把当前页签输入收集进配置（保存与导出共用，仅当前方案） */
    private void collectCurrent() {
        AllConfig cfg = currentCfg();
        if (cfg == null) return;
        AllConfig def = AllConfig.defaults();
        for (String mode : AllConfig.MODE_KEYS) {
            AllConfig.Mode m = cfg.modes.get(mode);
            AllConfig.Mode dm = def.modes.get(mode);
            for (String f : FIELDS) {
                EditText et = inputs.get(mode + "." + f);
                if (et == null) continue;
                String v = et.getText().toString().trim();
                if (v.isEmpty()) v = value(dm, f);
                assign(m, f, v);
            }
        }
    }

    /** 保存当前编辑的配置到对应文件 */
    private void saveConfig() {
        if (currentCfg() == null) {
            Toast.makeText(this, "方案仍在加载中", Toast.LENGTH_SHORT).show();
            return;
        }
        collectCurrent();
        String path = RootShell.CONFIG_DIR + "/" + tab + ".all.sh";
        String content = AllConfig.generate(currentCfg());
        // 方案2 的 conf 引用 B/ 脚本目录（调速器参数可按方案分别保存）
        if ("b".equals(tab)) content = content.replace("$mokzdz/A/", "$mokzdz/B/");
        final String out = content;

        new Thread(() -> {
            RootShell.Result r = RootShell.writeFile(getCacheDir(), out, path);
            runOnUiThread(() -> {
                if (r.ok()) {
                    dirty = false;
                    Toast.makeText(this, "保存成功", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "写入失败：" + r.err, Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    // ==================== 恢复默认值（可选方案1/方案2/全部） ====================

    /** 弹窗选择要恢复的方案，确认后立即写入出厂默认值并保存 */
    private void resetDefaults() {
        if (cfgA == null || cfgB == null) {
            Toast.makeText(this, "方案仍在加载中", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] opts = {"方案1", "方案2", "方案1+方案2"};
        final int[] sel = {0};
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("恢复默认值")
                .setMessage("选择要恢复出厂默认参数的方案，点击确定后立即写入并保存")
                .setSingleChoiceItems(opts, 0, (d, w) -> sel[0] = w)
                .setPositiveButton("确定", (d, w) -> doResetDefaults(sel[0]))
                .setNegativeButton("取消", null)
                .create();
        if (dlg.getWindow() != null) {
            dlg.getWindow().setBackgroundDrawableResource(R.drawable.bg_dialog);
        }
        dlg.show();
    }

    /** w: 0=方案1  1=方案2  2=方案1+方案2。内存替换 + 立即写对应 a/b.all.sh */
    private void doResetDefaults(int w) {
        if (w != 1) cfgA = AllConfig.defaults();
        if (w != 0) cfgB = AllConfig.defaults();
        if ((w == 0 && "a".equals(tab)) || (w == 1 && "b".equals(tab)) || w == 2) {
            fillInputs();
            dirty = false;   // 已直接持久化，无未保存修改
        }
        final String label = w == 0 ? "方案1" : w == 1 ? "方案2" : "方案1+方案2";
        new Thread(() -> {
            boolean ok = true;
            if (w != 1) ok = RootShell.writeFile(getCacheDir(), AllConfig.generate(cfgA),
                    RootShell.CONFIG_DIR + "/a.all.sh").ok() && ok;
            if (w != 0) ok = RootShell.writeFile(getCacheDir(),
                    AllConfig.generate(cfgB).replace("$mokzdz/A/", "$mokzdz/B/"),
                    RootShell.CONFIG_DIR + "/b.all.sh").ok() && ok;
            final boolean okF = ok;
            runOnUiThread(() -> Toast.makeText(this, okF
                    ? "已恢复默认值并保存（" + label + "）"
                    : "写入失败，请重试", Toast.LENGTH_SHORT).show());
        }).start();
    }

    // ==================== color.lax 导入导出（方案1+方案2 全部模式） ====================

    /** 导入文件选择器请求码 */
    private static final int REQ_IMPORT = 7301;
    /** 导入方案选择：0=方案1  1=方案2  2=方案1+2 */
    private int laxScheme = 2;

    /** 导出前先选择方案（方案1 / 方案2 / 方案1+2），再写入内部储存根目录 color.lax */
    private void exportLax() {
        if (cfgA == null || cfgB == null) {
            Toast.makeText(this, "方案仍在加载中", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] opts = {"方案1", "方案2", "方案1+方案2"};
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("选择要导出的方案")
                .setSingleChoiceItems(opts, 2, (d, w) -> {
                    d.dismiss();
                    doExport(w);
                })
                .setNegativeButton("取消", null)
                .create();
        if (dlg.getWindow() != null) {
            dlg.getWindow().setBackgroundDrawableResource(R.drawable.bg_dialog);
        }
        dlg.show();
    }

    /** 按所选方案导出（只覆盖文件中对应方案的小节，另一方案保持原样） */
    private void doExport(int scheme) {
        collectCurrent();   // 当前页签未保存的编辑也一并导出
        LinkedHashMap<String, String> block = new LinkedHashMap<>();
        if (scheme != 1) putLaxCfg(block, "a", cfgA);
        if (scheme != 0) putLaxCfg(block, "b", cfgB);
        final String label = scheme == 0 ? "方案1" : scheme == 1 ? "方案2" : "方案1+2";
        new Thread(() -> {
            RootShell.Result r = LaxStore.write(getCacheDir(), block);
            runOnUiThread(() -> Toast.makeText(this, r.ok()
                    ? "已导出" + label + " 到内部储存根目录 /storage/emulated/0/color.lax"
                    : "导出失败：" + r.err, Toast.LENGTH_LONG).show());
        }).start();
    }

    private void putLaxCfg(LinkedHashMap<String, String> block, String t, AllConfig cfg) {
        for (String mode : AllConfig.MODE_KEYS) {
            AllConfig.Mode m = cfg.modes.get(mode);
            if (m == null) continue;
            String p = "sch." + t + "." + mode + ".";
            for (String f : FIELDS) block.put(p + f, value(m, f));
        }
    }

    /** 导入前先选择要应用的方案（方案1 / 方案2 / 方案1+2），再打开文件选择器 */
    private void importLax() {
        if (cfgA == null || cfgB == null) {
            Toast.makeText(this, "方案仍在加载中", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] opts = {"方案1", "方案2", "方案1+方案2"};
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("选择要导入的方案")
                .setSingleChoiceItems(opts, 2, (d, w) -> {
                    d.dismiss();
                    laxScheme = w;
                    Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    it.addCategory(Intent.CATEGORY_OPENABLE);
                    it.setType("*/*");
                    startActivityForResult(it, REQ_IMPORT);
                })
                .setNegativeButton("取消", null)
                .create();
        if (dlg.getWindow() != null) {
            dlg.getWindow().setBackgroundDrawableResource(R.drawable.bg_dialog);
        }
        dlg.show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_IMPORT || resultCode != RESULT_OK
                || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        if (cfgA == null || cfgB == null) {
            Toast.makeText(this, "方案仍在加载中", Toast.LENGTH_SHORT).show();
            return;
        }
        new Thread(() -> {
            LinkedHashMap<String, String> map = LaxStore.readUri(this, uri);
            runOnUiThread(() -> {
                if (map.isEmpty()) {
                    Toast.makeText(this, "无法读取所选文件", Toast.LENGTH_SHORT).show();
                    return;
                }
                int n = applyLaxScheme(map);
                if (n == 0) {
                    Toast.makeText(this, "文件中没有调度参数", Toast.LENGTH_SHORT).show();
                    return;
                }
                fillInputs();
                dirty = true;
                String label = laxScheme == 0 ? "方案1" : laxScheme == 1 ? "方案2" : "方案1+2";
                Toast.makeText(this, "已导入 " + n + " 项（" + label + "），点击保存后生效",
                        Toast.LENGTH_LONG).show();
            });
        }).start();
    }

    /** 按导入方案选择应用 lax 中的 sch.*（缺失的项保持原值），返回应用项数 */
    private int applyLaxScheme(Map<String, String> map) {
        int n = 0;
        if (laxScheme != 1) n += applyLaxCfg(map, cfgA, "a");
        if (laxScheme != 0) n += applyLaxCfg(map, cfgB, "b");
        return n;
    }

    private int applyLaxCfg(Map<String, String> map, AllConfig cfg, String t) {
        if (cfg == null) return 0;
        int n = 0;
        for (String mode : AllConfig.MODE_KEYS) {
            AllConfig.Mode m = cfg.modes.get(mode);
            if (m == null) continue;
            String p = "sch." + t + "." + mode + ".";
            for (String f : FIELDS) {
                String v = map.get(p + f);
                if (v != null && !v.isEmpty()) {
                    assign(m, f, v);
                    n++;
                }
            }
        }
        return n;
    }
}
