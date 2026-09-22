package Color.fc;

import android.app.Activity;
import android.app.AlertDialog;
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
import android.widget.TextView;
import android.widget.Toast;

import java.util.HashMap;
import java.util.Locale;

/**
 * 调度参数：自动检测 SOC，加载对应 a.all.sh / b.all.sh，
 * 按模式（省电/均衡/性能/极速）展开参数编辑
 */
public class ScheduleActivity extends Activity {

    private AllConfig cfgA, cfgB;
    private String tab = "a";
    private boolean dirty = false;
    private boolean loading = true;
    private SocInfo soc;

    private final HashMap<String, EditText> inputs = new HashMap<>();
    private TextView tabAV, tabBV, socTip, saveHint;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_schedule);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        socTip = findViewById(R.id.socTip);
        tabAV = findViewById(R.id.tabA);
        tabBV = findViewById(R.id.tabB);
        saveHint = findViewById(R.id.saveHint);

        soc = MainActivity.cachedSoc;
        if (soc == null) soc = SocInfo.detect("");

        String fileA = RootShell.CONFIG_DIR + "/a.all.sh";
        String fileB = RootShell.CONFIG_DIR + "/b.all.sh";
        socTip.setText(soc.known
                ? String.format(Locale.US, "当前 SOC：%s（%s）→ 加载配置 %s", soc.code, soc.marketing, soc.config.toUpperCase())
                : "检测错误，默认加载方案1");

        tabAV.setOnClickListener(v -> switchTab("a"));
        tabBV.setOnClickListener(v -> switchTab("b"));
        tab = soc.config; // 默认选中 SOC 对应配置

        findViewById(R.id.saveBtn).setOnClickListener(v -> saveConfig());

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
                String hint = (cfgA == null ? "a 为默认值" : "a 已读取");
                saveHint.setText(hint + " · 修改后点击保存写入");
            });
        }).start();
    }

    /** 构建 4 个模式卡片（收起状态，点击展开参数） */
    private void buildCards() {
        LinearLayout container = findViewById(R.id.modesContainer);
        int[] colors = {0xFF10B981, 0xFF3B82F6, 0xFFF59E0B, 0xFFEF4444};

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

    /** 添加一个带说明的参数输入框 */
    private void addParam(LinearLayout box, String key, String label) {
        View row = getLayoutInflater().inflate(R.layout.param_row, box, false);
        ((TextView) row.findViewById(R.id.label)).setText(label);
        EditText et = row.findViewById(R.id.input);
        et.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b2, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b2, int c) {}
            @Override public void afterTextChanged(Editable s) {
                if (!loading) dirty = true;
            }
        });
        inputs.put(key, et);
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
                    .setMessage("当前配置已修改但未保存，切换后将丢失，是否继续？")
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
        tabAV.setTextColor(isA ? 0xFF22D3EE : 0xFF8B98A8);
        tabBV.setBackgroundResource(isA ? R.drawable.bg_tab : R.drawable.bg_tab_sel);
        tabBV.setTextColor(isA ? 0xFF8B98A8 : 0xFF22D3EE);
    }

    /** 保存当前编辑的配置到对应文件 */
    private void saveConfig() {
        if (currentCfg() == null) {
            Toast.makeText(this, "配置仍在加载中", Toast.LENGTH_SHORT).show();
            return;
        }
        AllConfig cfg = currentCfg();
        AllConfig def = AllConfig.defaults();
        for (String mode : AllConfig.MODE_KEYS) {
            AllConfig.Mode m = cfg.modes.get(mode);
            AllConfig.Mode dm = def.modes.get(mode);
            String[] fields = {"opt2", "cpuMax", "cpuMin", "llcc", "uclampDisplay",
                    "uclampSsfg", "uclampTouch", "uclampMm", "uclampRt", "uclampTopApp", "walt1", "walt2"};
            for (String f : fields) {
                EditText et = inputs.get(mode + "." + f);
                if (et == null) continue;
                String v = et.getText().toString().trim();
                if (v.isEmpty()) v = value(dm, f);
                assign(m, f, v);
            }
        }
        String path = RootShell.CONFIG_DIR + "/" + tab + ".all.sh";
        String content = AllConfig.generate(cfg);
        Toast.makeText(this, "正在写入 " + path, Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            RootShell.Result r = RootShell.writeFile(getCacheDir(), content, path);
            runOnUiThread(() -> {
                if (r.ok()) {
                    dirty = false;
                    Toast.makeText(this, "已保存到 " + path, Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, "写入失败：" + r.err, Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }
}
