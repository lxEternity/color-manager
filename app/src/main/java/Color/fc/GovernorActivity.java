package Color.fc;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.RotateAnimation;
import android.app.AlertDialog;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 调速器配置：conservative.sh（省电）/ scx1.sh（均衡）/ scx2.sh（性能）/ scx3.sh（极速）
 * 每个模式调速器名称与参数可编辑，输入框内默认值可视化（conservative / scx）
 */
public class GovernorActivity extends ThemedActivity {

    private final GovernorConfig.Gov[] govsA = new GovernorConfig.Gov[4];
    private final GovernorConfig.Gov[] govsB = new GovernorConfig.Gov[4];
    /** 当前页签（a=方案1 b=方案2）对应的配置数组引用 */
    private GovernorConfig.Gov[] govs;
    private String tab = "a";
    private TextView tabAV, tabBV;
    private final HashMap<String, EditText> inputs = new HashMap<>();
    private final HashMap<String, TextView> spinners = new HashMap<>();
    /** 每模式的 8 个核心芯片（key=模式索引） */
    private final HashMap<String, TextView[]> coreChips = new HashMap<>();
    /** 每模式的限频选择值（key=模式.minFreq / 模式.maxFreq） */
    private final HashMap<String, TextView> freqVals = new HashMap<>();
    /** 本机可用 CPU 频率档位（MHz 降序），首次扫描一次后永久缓存 */
    private long[] cpuFreqs = new long[0];
    /** 本机支持的调速器列表（首次扫描一次后缓存），空 = 扫描失败回退 GOV_PRESETS */
    private String[] govList = new String[0];
    private boolean loading = true;

    /** 预设调速器列表（本机扫描失败时的回退列表） */
    private static final String[] GOV_PRESETS = {
            "conservative", "walt", "ips", "sugov_next", "scx",
            "hmbird", "powersave", "performance", "schedutil"
    };

    private static final String[] FILES = {"conservative.sh", "scx1.sh", "scx2.sh", "scx3.sh"};
    /** 方案2 调速器脚本目录（与 A/ 结构相同，b.all.sh 引用） */
    private static final String GOV_DIR_B = "/data/adb/modules/colorFC/B";
    private static final String[] NAMES = {"省电模式", "均衡模式", "性能模式", "极速模式"};
    private static final String[] DESCS = {
            "省电模式调速器参数（CPU0-7）",
            "均衡模式调速器参数（CPU 0/3/5/7）",
            "性能模式调速器参数（CPU 0/3/5/7）",
            "极速模式全核调速器参数（CPU0-7）"
    };
    /** 每模式的参数字段（fillInputs / collectInputs / lax 共用），minFreq/maxFreq = CPU 自定义限频（MHz，0=不限制） */
    private static final String[][] FIELDS = {
            {"governor", "upThreshold", "downThreshold", "freqStep", "samplingRate", "minFreq", "maxFreq"},
            {"governor", "targetLoads", "minFreq", "maxFreq"},
            {"governor", "targetLoads", "minFreq", "maxFreq"},
            {"governor", "targetLoads", "minFreq", "maxFreq"}
    };
    /** 出厂默认（与模块 A/ 出厂脚本一致：conservative.sh 95/90/1/8000、scx1=90、scx2=85、scx3=walt） */
    private static final String[][] DEFAULTS = {
            {"conservative", "95", "90", "1", "8000", "0", "0"},
            {"scx", "90", "0", "0"},
            {"scx", "85", "0", "0"},
            {"walt", "70", "0", "0"}
    };
    /** 模块可能用于恢复 A/ 脚本的镜像位置（类似 conf 模板机制），保存时三重写入 */
    private static final String[] MIRROR_DIRS = {
            "/sdcard/Android/qingtd/A",
            "/data/adb/modules/colorFC/qingtd/A"
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_governor);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        setupBottomNav(R.id.navGovernor);
        findViewById(R.id.saveBtn).setOnClickListener(v -> saveAll());
        findViewById(R.id.btnImport).setOnClickListener(v -> importLax());
        findViewById(R.id.btnExport).setOnClickListener(v -> exportLax());
        findViewById(R.id.btnReset).setOnClickListener(v -> resetDefaults());

        // 方案页签：默认选中 SOC 对应方案，与调度页一致
        tabAV = findViewById(R.id.tabA);
        tabBV = findViewById(R.id.tabB);
        tabAV.setOnClickListener(v -> switchTab("a"));
        tabBV.setOnClickListener(v -> switchTab("b"));
        SocInfo soc = MainActivity.cachedSoc;
        if (soc == null) soc = SocInfo.autoDetect();
        tab = soc.config != null ? soc.config : "a";

        buildCards();

        new Thread(() -> {
            // 方案1（A/）与方案2（B/）分别解析，各自镜像兜底
            GovernorConfig.Gov[] mirrorA = loadGovMirror("a");
            GovernorConfig.Gov[] mirrorB = loadGovMirror("b");
            for (int i = 0; i < 4; i++) {
                GovernorConfig.Gov ga = GovernorConfig.parse(
                        RootShell.readFile(RootShell.GOV_DIR + "/" + FILES[i]), i == 0);
                govsA[i] = mergeGov(ga, mirrorA[i], i);
                GovernorConfig.Gov gb = GovernorConfig.parse(
                        RootShell.readFile(GOV_DIR_B + "/" + FILES[i]), i == 0);
                govsB[i] = mergeGov(gb, mirrorB[i], i);
            }
            loadFreqScan();   // 本机频率档位：仅在无缓存时扫描一次
            loadGovScan();   // 本机支持的调速器：仅首次扫描，避免选到不支持的调速器
            runOnUiThread(() -> {
                loading = false;
                govs = "b".equals(tab) ? govsB : govsA;
                applyTabStyle();
                fillInputs();
            });
        }).start();
    }

    private void buildCards() {
        LinearLayout container = findViewById(R.id.modesContainer);
        int[] colors = {0xFF00B5A3, 0xFF0096C8, 0xFFE08A00, 0xFFA02CF0};

        for (int i = 0; i < 4; i++) {
            final int idx = i;
            View card = getLayoutInflater().inflate(R.layout.mode_card, container, false);

            View dot = card.findViewById(R.id.modeDot);
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(colors[i]);
            dot.setBackground(d);

            ((TextView) card.findViewById(R.id.modeTitle)).setText(NAMES[i]);
            ((TextView) card.findViewById(R.id.modeSubtitle)).setText(DESCS[i]);

            LinearLayout box = card.findViewById(R.id.paramsBox);
            ImageView arrow = card.findViewById(R.id.expandArrow);
            card.findViewById(R.id.cardHeader).setOnClickListener(v -> {
                boolean expand = box.getVisibility() != View.VISIBLE;
                box.setVisibility(expand ? View.VISIBLE : View.GONE);
                rotateArrow(arrow, expand);
            });

            addCoreSelector(box, idx);
            addGovernorSelector(box, idx + ".governor",
                    "调速器选择", i == 0 ? "conservative" : i == 3 ? "walt" : "scx");
            if (i == 0) {
                addParam(box, idx + ".upThreshold", "up_threshold 升频阈值（% 负载超过即升频）", "95", true);
                addParam(box, idx + ".downThreshold", "down_threshold 降频阈值（% 负载低于即降频）", "90", true);
                addParam(box, idx + ".freqStep", "freq_step 每次调频步进（%）", "1", true);
                addParam(box, idx + ".samplingRate", "sampling_rate 采样周期（µs）", "8000", false);
            } else {
                addParam(box, idx + ".targetLoads", "target_loads 目标负载（%）",
                        i == 1 ? "90" : i == 2 ? "85" : "70", true);
            }
            addFreqSelector(box, idx + ".minFreq", "CPU 最小频率限制");
            addFreqSelector(box, idx + ".maxFreq", "CPU 最大频率限制");
            container.addView(card);
        }
    }

    /** 启用核心选择行：8 个可点击芯片，选中=启用该核心 */
    private void addCoreSelector(LinearLayout box, int idx) {
        TextView label = new TextView(this);
        label.setText("启用或关闭核心");
        label.setTextSize(11);
        label.setTextColor(getResources().getColor(R.color.textSecondary));
        label.setPadding(dp(2), dp(4), dp(2), dp(4));
        box.addView(label);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        row.setLayoutParams(rlp);

        TextView[] chips = new TextView[8];
        for (int c = 0; c < 8; c++) {
            TextView chip = new TextView(this);
            chip.setText(String.valueOf(c));
            chip.setGravity(android.view.Gravity.CENTER);
            chip.setTextSize(12);
            chip.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(8));
            chip.setBackground(bg);
            chip.setTag(Boolean.TRUE);   // 默认启用，fillInputs 按脚本覆盖
            styleChip(chip, true);
            final int core = c;
            chip.setOnClickListener(v -> {
                if (loading) return;
                boolean on = !Boolean.TRUE.equals(chip.getTag());
                if (core == 0) {
                    Toast.makeText(this, "CPU0 为主核，系统不允许关闭", Toast.LENGTH_SHORT).show();
                    return;
                }
                chip.setTag(on);
                styleChip(chip, on);
                // 即时生效（照搬 Kin-app：直接写 sysfs online 节点，不等脚本/重启）
                new Thread(() -> CpuCoreManager.setCoreOnline(core, on)).start();
            });
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(30), dp(30));
            clp.rightMargin = dp(6);
            chip.setLayoutParams(clp);
            row.addView(chip);
            chips[c] = chip;
        }
        coreChips.put(String.valueOf(idx), chips);
        box.addView(row);
    }

    /** 芯片选中/未选样式 */
    private void styleChip(TextView chip, boolean on) {
        GradientDrawable bg = (GradientDrawable) chip.getBackground();
        bg.setColor(on ? 0xFF0096C8 : getResources().getColor(R.color.bgInput));
        chip.setTextColor(on ? 0xFFFFFFFF : getResources().getColor(R.color.textDim));
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
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

    private void addParam(LinearLayout box, String key, String label, String def, boolean seek) {
        View row = getLayoutInflater().inflate(R.layout.param_row, box, false);
        ((TextView) row.findViewById(R.id.label)).setText(label);
        EditText et = row.findViewById(R.id.input);
        et.setText(def);
        SeekBar sb = row.findViewById(R.id.seek);
        if (seek) {
            sb.setMax(100);
            try {
                sb.getProgressDrawable().setColorFilter(0xFF0096C8, android.graphics.PorterDuff.Mode.SRC_IN);
                sb.getThumb().setColorFilter(0xFF0096C8, android.graphics.PorterDuff.Mode.SRC_IN);
            } catch (Exception ignored) {
            }
            et.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b2, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b2, int c) {}
                @Override public void afterTextChanged(android.text.Editable s) {
                    try {
                        int v = Integer.parseInt(s.toString().trim());
                        sb.setProgress(Math.max(0, Math.min(100, v)));
                    } catch (Exception ignored) {
                    }
                }
            });
            sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                    if (fromUser) et.setText(String.valueOf(p));
                }
                @Override public void onStartTrackingTouch(SeekBar s) {}
                @Override public void onStopTrackingTouch(SeekBar s) {}
            });
        } else {
            sb.setVisibility(View.GONE);
        }
        inputs.put(key, et);
        box.addView(row);
    }

    /** 预设调速器选择：点击弹出上下滑动单选弹窗 */
    private void addGovernorSelector(LinearLayout box, String key, String label, String def) {
        View row = getLayoutInflater().inflate(R.layout.governor_row, box, false);
        ((TextView) row.findViewById(R.id.label)).setText(label);
        TextView val = row.findViewById(R.id.govValue);
        val.setText(def);
        val.setOnClickListener(v -> showGovPicker(key, val));
        spinners.put(key, val);
        box.addView(row);
    }

    /** 上下滑动选择弹窗：列表仅显示本机支持的调速器（scaling_available_governors 扫描），
     *  本机没有的调速器不可以选择，点击时提示「没有这个调速器！」；当前值高亮 */
    private void showGovPicker(String key, TextView val) {
        String cur = val.getText().toString();
        String[] items = govList.length > 0 ? govList : GOV_PRESETS;
        int checked = -1;
        for (int i = 0; i < items.length; i++) {
            if (items[i].equals(cur)) { checked = i; break; }
        }
        final String[] list = items;
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("选择调速器")
                .setSingleChoiceItems(list, checked, (d, w) -> {
                    // 点击的调速器不在本机支持列表（如扫描未完成时回退预设列表）：提示且不选中
                    if (govList.length > 0 && !inGovList(list[w])) {
                        Toast.makeText(this, "没有这个调速器！", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    val.setText(list[w]);
                    d.dismiss();
                })
                .setNegativeButton("取消", null)
                .create();
        if (dlg.getWindow() != null) {
            dlg.getWindow().setBackgroundDrawableResource(R.drawable.bg_dialog);
        }
        dlg.show();
    }

    /** 调速器是否在本机支持列表中 */
    private boolean inGovList(String g) {
        for (String s : govList) if (s.equals(g)) return true;
        return false;
    }

    /** 限频选择行：显示当前值（不限制 / xxx MHz），点击弹出本机档位选择 */
    private void addFreqSelector(LinearLayout box, String key, String label) {
        View row = getLayoutInflater().inflate(R.layout.governor_row, box, false);
        ((TextView) row.findViewById(R.id.label)).setText(label);
        TextView val = row.findViewById(R.id.govValue);
        val.setText("不限制");
        val.setOnClickListener(v -> showFreqPicker(key, val));
        freqVals.put(key, val);
        box.addView(row);
    }

    /** 弹出本机频率档位单选弹窗（列表来自首次扫描并缓存的 freqScan） */
    private void showFreqPicker(String key, TextView val) {
        if (cpuFreqs.length == 0) {
            Toast.makeText(this, "频率列表不可用，改为手动输入（MHz，0=不限制）",
                    Toast.LENGTH_SHORT).show();
            showFreqManual(val);
            return;
        }
        String cur = val.getText().toString().trim();
        String[] items = new String[cpuFreqs.length + 2];
        items[0] = "不限制";
        int checked = "不限制".equals(cur) ? 0 : -1;
        for (int i = 0; i < cpuFreqs.length; i++) {
            items[i + 1] = cpuFreqs[i] + " MHz";
            if (checked < 0 && String.valueOf(cpuFreqs[i]).equals(cur)) checked = i + 1;
        }
        items[cpuFreqs.length + 1] = "手动输入 MHz…";
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("选择频率")
                .setSingleChoiceItems(items, checked, (d, w) -> {
                    d.dismiss();
                    if (w == 0) {
                        val.setText("不限制");
                    } else if (w == items.length - 1) {
                        showFreqManual(val);
                    } else {
                        val.setText(String.valueOf(cpuFreqs[w - 1]));
                    }
                })
                .setNegativeButton("取消", null)
                .create();
        if (dlg.getWindow() != null) {
            dlg.getWindow().setBackgroundDrawableResource(R.drawable.bg_dialog);
        }
        dlg.show();
    }

    /** 手动输入限频（MHz，0 或留空=不限制） */
    private void showFreqManual(TextView val) {
        final EditText et = new EditText(this);
        et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        et.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(10)});
        String cur = val.getText().toString().trim();
        et.setText("不限制".equals(cur) ? "0" : cur);
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("输入频率（MHz，0=不限制）")
                .setView(et)
                .setPositiveButton("确定", (d, w) -> {
                    String s = et.getText().toString().trim();
                    if (s.isEmpty()) s = "0";
                    try {
                        long v = Long.parseLong(s);
                        val.setText(v <= 0 ? "不限制" : String.valueOf(v));
                    } catch (Exception ignored) {
                    }
                })
                .setNegativeButton("取消", null)
                .create();
        if (dlg.getWindow() != null) {
            dlg.getWindow().setBackgroundDrawableResource(R.drawable.bg_dialog);
        }
        dlg.show();
    }

    /** 扫描本机所有 CPU 的可用频率档位（MHz 降序去重）。仅在无缓存时执行一次，
     *  结果永久保存到 SharedPreferences，之后不再重复扫描 */
    private void loadFreqScan() {
        try {
            String csv = getSharedPreferences("colorfc", MODE_PRIVATE).getString("freqScan", "");
            if (!csv.isEmpty()) {
                String[] ps = csv.split(",");
                long[] arr = new long[ps.length];
                int n = 0;
                for (String p : ps) {
                    try {
                        arr[n++] = Long.parseLong(p);
                    } catch (Exception ignored) {
                    }
                }
                cpuFreqs = java.util.Arrays.copyOf(arr, n);
                return;
            }
            StringBuilder cmd = new StringBuilder();
            for (int c = 0; c < 8; c++) {
                cmd.append("cat /sys/devices/system/cpu/cpu").append(c)
                        .append("/cpufreq/scaling_available_frequencies 2>/dev/null; ");
            }
            java.util.TreeSet<Long> set = new java.util.TreeSet<>(java.util.Collections.reverseOrder());
            RootShell.Result r = RootShell.exec(cmd.toString());
            if (r.ok() && r.out != null) {
                for (String tok : r.out.trim().split("\\s+")) {
                    try {
                        long k = Long.parseLong(tok);
                        if (k > 1000) set.add(k / 1000);
                    } catch (Exception ignored) {
                    }
                }
            }
            // 部分内核无 available 列表：退回各簇 cpuinfo_min/max 兜底
            if (set.size() < 2) {
                StringBuilder fb = new StringBuilder();
                for (int c = 0; c < 8; c++) {
                    fb.append("cat /sys/devices/system/cpu/cpu").append(c)
                            .append("/cpufreq/cpuinfo_min_freq 2>/dev/null; ");
                    fb.append("cat /sys/devices/system/cpu/cpu").append(c)
                            .append("/cpufreq/cpuinfo_max_freq 2>/dev/null; ");
                }
                r = RootShell.exec(fb.toString());
                if (r.ok() && r.out != null) {
                    for (String tok : r.out.trim().split("\\s+")) {
                        try {
                            long k = Long.parseLong(tok);
                            if (k > 1000) set.add(k / 1000);
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
            if (!set.isEmpty()) {
                StringBuilder sbCsv = new StringBuilder();
                for (long v : set) {
                    if (sbCsv.length() > 0) sbCsv.append(',');
                    sbCsv.append(v);
                }
                getSharedPreferences("colorfc", MODE_PRIVATE).edit()
                        .putString("freqScan", sbCsv.toString()).commit();
                cpuFreqs = new long[set.size()];
                int n = 0;
                for (long v : set) cpuFreqs[n++] = v;
            }
        } catch (Exception ignored) {
        }
    }

    /** 扫描本机所有 CPU 支持的调速器（scaling_available_governors 并集去重）。
     *  仅首次扫描，结果缓存到 SharedPreferences（内核级列表，重启不变） */
    private void loadGovScan() {
        try {
            String csv = getSharedPreferences("colorfc", MODE_PRIVATE).getString("govScan", "");
            if (!csv.isEmpty()) {
                govList = csv.split(",");
                return;
            }
            StringBuilder cmd = new StringBuilder();
            for (int c = 0; c < 8; c++) {
                cmd.append("cat /sys/devices/system/cpu/cpu").append(c)
                        .append("/cpufreq/scaling_available_governors 2>/dev/null; ");
            }
            RootShell.Result r = RootShell.exec(cmd.toString());
            java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
            if (r.ok() && r.out != null) {
                for (String tok : r.out.trim().split("\\s+")) {
                    String g = tok.trim();
                    if (!g.isEmpty()) set.add(g);
                }
            }
            if (!set.isEmpty()) {
                govList = set.toArray(new String[0]);
                StringBuilder sbCsv = new StringBuilder();
                for (String g : set) {
                    if (sbCsv.length() > 0) sbCsv.append(',');
                    sbCsv.append(g);
                }
                getSharedPreferences("colorfc", MODE_PRIVATE).edit()
                        .putString("govScan", sbCsv.toString()).commit();
            }
        } catch (Exception ignored) {
        }
    }

    private void fillInputs() {
        for (int i = 0; i < 4; i++) {
            GovernorConfig.Gov g = govs[i];
            // 刷新核心芯片
            TextView[] chips = coreChips.get(String.valueOf(i));
            if (chips != null && g != null) {
                for (int c = 0; c < 8; c++) {
                    chips[c].setTag(g.cores[c]);
                    styleChip(chips[c], g.cores[c]);
                }
            }
            for (int j = 0; j < FIELDS[i].length; j++) {
                String key = i + "." + FIELDS[i][j];
                String v = readField(g, FIELDS[i][j]);
                if (v == null || v.isEmpty()) v = DEFAULTS[i][j];
                if ("governor".equals(FIELDS[i][j])) {
                    TextView sp = spinners.get(key);
                    if (sp != null && v != null && !v.isEmpty()) sp.setText(v);
                    continue;
                }
                if ("minFreq".equals(FIELDS[i][j]) || "maxFreq".equals(FIELDS[i][j])) {
                    TextView tv = freqVals.get(key);
                    if (tv != null) tv.setText(normF(v).isEmpty() ? "不限制" : v.trim());
                    continue;
                }
                EditText et = inputs.get(key);
                if (et == null) continue;
                et.setText(v);
            }
        }
    }

    private String readField(GovernorConfig.Gov g, String f) {
        switch (f) {
            case "governor": return g.governor;
            case "upThreshold": return g.upThreshold;
            case "downThreshold": return g.downThreshold;
            case "freqStep": return g.freqStep;
            case "samplingRate": return g.samplingRate;
            case "targetLoads": return g.targetLoads;
            case "minFreq": return g.minFreq;
            case "maxFreq": return g.maxFreq;
        }
        return "";
    }

    private void writeField(GovernorConfig.Gov g, String f, String v) {
        switch (f) {
            case "governor": g.governor = v; break;
            case "upThreshold": g.upThreshold = v; break;
            case "downThreshold": g.downThreshold = v; break;
            case "freqStep": g.freqStep = v; break;
            case "samplingRate": g.samplingRate = v; break;
            case "targetLoads": g.targetLoads = v; break;
            case "minFreq": g.minFreq = v; break;
            case "maxFreq": g.maxFreq = v; break;
        }
    }

    /** 把界面输入收集进 govs（保存与导出共用） */
    private void collectInputs() {
        for (int i = 0; i < 4; i++) {
            // 芯片状态写回
            TextView[] chips = coreChips.get(String.valueOf(i));
            if (chips != null && govs[i] != null) {
                for (int c = 0; c < 8; c++) {
                    Object t = chips[c].getTag();
                    govs[i].cores[c] = t == null || (Boolean) t;
                }
            }
            for (int j = 0; j < FIELDS[i].length; j++) {
                String f = FIELDS[i][j];
                if ("governor".equals(f)) {
                    TextView sp = spinners.get(i + "." + f);
                    if (sp != null && sp.getText() != null && sp.getText().length() > 0) {
                        writeField(govs[i], f, sp.getText().toString());
                    }
                    continue;
                }
                if ("minFreq".equals(f) || "maxFreq".equals(f)) {
                    TextView tv = freqVals.get(i + "." + f);
                    if (tv == null) continue;
                    String s = tv.getText().toString().trim();
                    writeField(govs[i], f, (s.isEmpty() || "不限制".equals(s)) ? "0" : s);
                    continue;
                }
                EditText et = inputs.get(i + "." + f);
                if (et == null) continue;
                String v = et.getText().toString().trim();
                if (v.isEmpty()) v = DEFAULTS[i][j];
                writeField(govs[i], f, v);
            }
        }
    }

    /** 保存调速器脚本：加载哪个方案（当前页签）就保存到哪个方案，不再弹窗选择 */
    private void saveAll() {
        if (loading || govs == null || govs[0] == null) {
            Toast.makeText(this, "配置仍在加载中", Toast.LENGTH_SHORT).show();
            return;
        }
        collectInputs();
        saveAll("b".equals(tab) ? 2 : 1);
    }

    /** 切换方案页签：当前编辑写回内存后切换回显（不丢失） */
    private void switchTab(String t) {
        if (t.equals(tab) || loading || govsA[0] == null) return;
        collectInputs();
        tab = t;
        govs = "b".equals(tab) ? govsB : govsA;
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

    /** scheme: 0=方案1+方案2  1=仅方案1(A)  2=仅方案2(B)。src 传入要持久化的配置数组
     *  （正常保存=当前页签收集值，恢复默认值=出厂默认数组），okMsg 为成功提示前缀 */
    private void persistGovs(GovernorConfig.Gov[] src, int scheme, String okMsg) {
        new Thread(() -> {
            // 镜像按所选方案分别落盘（恢复默认值时镜像同步为默认配置，兜底回显不再指向旧值）
            if (scheme != 2) saveMirrorFor(src, "a");
            if (scheme != 1) saveMirrorFor(src, "b");
            java.util.ArrayList<String> saveDirs = new java.util.ArrayList<>();
            if (scheme != 2) saveDirs.add(RootShell.GOV_DIR);
            if (scheme != 1) saveDirs.add(GOV_DIR_B);
            // 所选目录首次替换 json_cpu_max_min 前分别备份原 ELF；B/ 不存在时先创建
            StringBuilder bk = new StringBuilder();
            for (String d : saveDirs) {
                bk.append("mkdir -p '").append(d).append("'; ");
                bk.append("[ -f '").append(d).append("/json_cpu_max_min.orig' ] || ")
                        .append("cp '").append(d).append("/json_cpu_max_min' '")
                        .append(d).append("/json_cpu_max_min.orig'; ");
            }
            RootShell.exec(bk.toString());
            // 9 文件集（4 调速器 + 4 限频 + json_cpu_max_min 复现脚本）× 所选目录
            String[] srcContents = new String[9];
            String[] srcNames = new String[9];
            srcContents[0] = GovernorConfig.generateConservative(src[0]);
            srcContents[1] = GovernorConfig.generateScx(src[1]);
            srcContents[2] = GovernorConfig.generateScx(src[2]);
            srcContents[3] = GovernorConfig.generateScx3(src[3]);
            srcContents[4] = GovernorConfig.generateFreqScript(src[0], 0);
            srcContents[5] = GovernorConfig.generateFreqScript(src[1], 1);
            srcContents[6] = GovernorConfig.generateFreqScript(src[2], 2);
            srcContents[7] = GovernorConfig.generateFreqScript(src[3], 3);
            srcContents[8] = GovernorConfig.generateJmmScript();
            for (int i = 0; i < 4; i++) srcNames[i] = FILES[i];
            for (int i = 0; i < 4; i++) srcNames[4 + i] = "freq" + i + ".sh";
            srcNames[8] = "json_cpu_max_min";
            String[] contents = new String[saveDirs.size() * 9];
            String[] targets = new String[saveDirs.size() * 9];
            for (int d = 0; d < saveDirs.size(); d++) {
                boolean isB = GOV_DIR_B.equals(saveDirs.get(d));
                for (int i = 0; i < 9; i++) {
                    // B/conservative.sh 用方案2 变体：保留模块出厂 B 脚本的
                    // ignore_nice_load=1 与「关闭 game_opt 早检测」行为
                    contents[d * 9 + i] = (i == 0 && isB)
                            ? GovernorConfig.generateConservative(src[0], true) : srcContents[i];
                    targets[d * 9 + i] = saveDirs.get(d) + "/" + srcNames[i];
                }
            }
            // 一次 su 完成全部文件写入
            boolean ok = RootShell.writeFiles(getCacheDir(), contents, targets);
            // 一次 su 完成镜像位置同步（仅同步所选方案对应的目录；best-effort，失败不影响保存结果）
            if (ok) {
                StringBuilder mc = new StringBuilder();
                for (String mirror : MIRROR_DIRS) {
                    String mirrorB = mirror.endsWith("/A") ? mirror.substring(0, mirror.length() - 2) + "/B" : mirror + "B";
                    if (saveDirs.contains(RootShell.GOV_DIR)) {
                        mc.append("mkdir -p '").append(mirror).append("'; ");
                        for (int i = 0; i < 9; i++) {
                            mc.append("cp '").append(RootShell.GOV_DIR).append("/").append(srcNames[i]).append("' '")
                                    .append(mirror).append("/").append(srcNames[i]).append("'; ");
                        }
                    }
                    if (saveDirs.contains(GOV_DIR_B)) {
                        mc.append("mkdir -p '").append(mirrorB).append("'; ");
                        for (int i = 0; i < 9; i++) {
                            mc.append("cp '").append(GOV_DIR_B).append("/").append(srcNames[i]).append("' '")
                                    .append(mirrorB).append("/").append(srcNames[i]).append("'; ");
                        }
                    }
                }
                RootShell.exec(mc.toString());
            }
            // 给已部署的方案 conf 幂等补上 freq 调用行（a 引用 A/，b 引用 B/，与各自目录的 freqN.sh 对应）
            String[][] confs = {{"a.all.sh", "A"}, {"b.all.sh", "B"}};
            for (String[] cf : confs) {
                try {
                    String path = RootShell.CONFIG_DIR + "/" + cf[0];
                    String cur = RootShell.readFile(path);
                    if (cur == null || cur.trim().isEmpty()) continue;
                    String patched = AllConfig.patchFreqLines(cur, cf[1]);
                    if (!patched.equals(cur)) RootShell.writeFile(getCacheDir(), patched, path);
                } catch (Exception ignored) {
                }
            }
            // 写后回读校验，发现被外部回滚立即提示（而不是下次进页面静默回显默认值）
            String verify = "";
            if (ok) {
                try { Thread.sleep(800); } catch (InterruptedException ignored) { }
                int bad = verifySaved(saveDirs);
                if (bad > 0) verify = " · " + bad + " 个文件回读不符(被外部修改?)";
            }
            final boolean okF = ok;
            final String verifyF = verify;
            runOnUiThread(() -> {
                if (okF) {
                    Toast.makeText(this, okMsg + verifyF, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "保存失败：脚本写入不成功，请重试", Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    /** scheme: 0=方案1+方案2  1=仅方案1(A)  2=仅方案2(B) */
    private void saveAll(int scheme) {
        persistGovs(govs, scheme, "保存成功");
    }

    // ==================== 恢复默认值（可选方案1/方案2/全部） ====================

    /** 弹窗选择要恢复的方案，确认后立即写入出厂默认值并保存 */
    private void resetDefaults() {
        if (loading || govsA[0] == null) {
            Toast.makeText(this, "配置仍在加载中", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] opts = {"方案1", "方案2", "方案1+方案2"};
        final int[] sel = {0};
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("恢复默认值")
                .setMessage("选择要恢复出厂默认参数的方案，点击确定后立即写入并保存（调速器、参数、限频与核心全恢复默认）")
                .setSingleChoiceItems(opts, 0, (d, w) -> sel[0] = w)
                .setPositiveButton("确定", (d, w) -> doResetDefaults(sel[0]))
                .setNegativeButton("取消", null)
                .create();
        if (dlg.getWindow() != null) {
            dlg.getWindow().setBackgroundDrawableResource(R.drawable.bg_dialog);
        }
        dlg.show();
    }

    /** w: 0=方案1  1=方案2  2=方案1+方案2。内存替换 + 立即持久化到所选方案的脚本目录 */
    private void doResetDefaults(int w) {
        GovernorConfig.Gov[] def = defaultGovs();
        if (w != 1) {
            GovernorConfig.Gov[] ca = copyGovs(def);
            for (int i = 0; i < 4; i++) govsA[i] = ca[i];
        }
        if (w != 0) {
            GovernorConfig.Gov[] cb = copyGovs(def);
            for (int i = 0; i < 4; i++) govsB[i] = cb[i];
        }
        boolean curA = "a".equals(tab);
        // 当前页签在恢复范围内才刷新输入框，避免覆盖另一页签的未保存编辑
        if ((w != 1 && curA) || (w != 0 && !curA)) {
            govs = curA ? govsA : govsB;
            fillInputs();
        }
        final String label = w == 0 ? "方案1" : w == 1 ? "方案2" : "方案1+方案2";
        persistGovs(def, w == 0 ? 1 : w == 1 ? 2 : 0,
                "已恢复默认值并保存（" + label + "）");
    }

    /** 出厂默认配置数组：调速器/参数取 DEFAULTS，限频 0=不限制，核心全启用 */
    private GovernorConfig.Gov[] defaultGovs() {
        GovernorConfig.Gov[] out = new GovernorConfig.Gov[4];
        for (int i = 0; i < 4; i++) {
            GovernorConfig.Gov g = new GovernorConfig.Gov();
            g.governor = DEFAULTS[i][0];
            if (i == 0) {
                g.upThreshold = DEFAULTS[0][1];
                g.downThreshold = DEFAULTS[0][2];
                g.freqStep = DEFAULTS[0][3];
                g.samplingRate = DEFAULTS[0][4];
                g.minFreq = DEFAULTS[0][5];
                g.maxFreq = DEFAULTS[0][6];
            } else {
                g.targetLoads = DEFAULTS[i][1];
                g.minFreq = DEFAULTS[i][2];
                g.maxFreq = DEFAULTS[i][3];
            }
            // cores 保持构造默认（8 核全启用）
            out[i] = g;
        }
        return out;
    }

    /** 深拷贝（cores 数组独立，两个方案互不影响） */
    private GovernorConfig.Gov[] copyGovs(GovernorConfig.Gov[] src) {
        GovernorConfig.Gov[] out = new GovernorConfig.Gov[4];
        for (int i = 0; i < 4; i++) {
            GovernorConfig.Gov g = new GovernorConfig.Gov();
            GovernorConfig.Gov s = src[i];
            g.governor = s.governor;
            g.upThreshold = s.upThreshold;
            g.downThreshold = s.downThreshold;
            g.freqStep = s.freqStep;
            g.samplingRate = s.samplingRate;
            g.targetLoads = s.targetLoads;
            g.ignoreNiceLoad = s.ignoreNiceLoad;
            g.minFreq = s.minFreq;
            g.maxFreq = s.maxFreq;
            System.arraycopy(s.cores, 0, g.cores, 0, 8);
            out[i] = g;
        }
        return out;
    }

    // ==================== 配置镜像（SharedPreferences，回显兜底） ====================

    /** 保存指定方案 4 模式配置镜像（按方案分键），脚本读取失败/字段缺失时用它兜底回显 */
    private void saveMirrorFor(GovernorConfig.Gov[] src, String t) {
        if (src == null) return;
        try {
            JSONArray arr = new JSONArray();
            for (int i = 0; i < 4; i++) {
                GovernorConfig.Gov g = src[i];
                JSONObject o = new JSONObject();
                o.put("name", g.governor);
                if (i == 0) {
                    o.put("up", g.upThreshold);
                    o.put("down", g.downThreshold);
                    o.put("step", g.freqStep);
                    o.put("rate", g.samplingRate);
                    o.put("nice", g.ignoreNiceLoad ? 1 : 0);
                } else {
                    o.put("loads", g.targetLoads);
                }
                o.put("fmin", g.minFreq);
                o.put("fmax", g.maxFreq);
                StringBuilder cs = new StringBuilder();
                for (boolean c : g.cores) cs.append(c ? '1' : '0');
                o.put("cores", cs.toString());
                arr.put(o);
            }
            getSharedPreferences("colorfc", MODE_PRIVATE).edit()
                    .putString("govMirror." + t, arr.toString()).commit();
        } catch (Exception ignored) {
        }
    }

    /** 读取指定方案上次保存的配置镜像（方案1 兼容旧版单方案镜像键） */
    private GovernorConfig.Gov[] loadGovMirror(String t) {
        GovernorConfig.Gov[] out = new GovernorConfig.Gov[4];
        try {
            String js = getSharedPreferences("colorfc", MODE_PRIVATE)
                    .getString("govMirror." + t, "");
            if (js.isEmpty() && "a".equals(t)) {
                js = getSharedPreferences("colorfc", MODE_PRIVATE)
                        .getString("govMirror", "");   // 旧版（仅方案1）镜像兼容
            }
            if (js.isEmpty()) js = "[]";
            JSONArray arr = new JSONArray(js);
            for (int i = 0; i < 4 && i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                GovernorConfig.Gov g = new GovernorConfig.Gov();
                if (o.has("name")) g.governor = o.getString("name");
                if (i == 0) {
                    if (o.has("up")) g.upThreshold = o.getString("up");
                    if (o.has("down")) g.downThreshold = o.getString("down");
                    if (o.has("step")) g.freqStep = o.getString("step");
                    if (o.has("rate")) g.samplingRate = o.getString("rate");
                    if (o.has("nice")) g.ignoreNiceLoad = o.optInt("nice", 0) == 1;
                } else {
                    if (o.has("loads")) g.targetLoads = o.getString("loads");
                }
                if (o.has("fmin")) g.minFreq = o.getString("fmin");
                if (o.has("fmax")) g.maxFreq = o.getString("fmax");
                String cs = o.optString("cores", "");
                if (cs.length() == 8) {
                    for (int c = 0; c < 8; c++) g.cores[c] = cs.charAt(c) == '1';
                }
                out[i] = g;
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** 脚本解析值优先，缺失字段用上次保存的镜像兜底（仍缺失则保持 null 由默认值兜底） */
    private GovernorConfig.Gov mergeGov(GovernorConfig.Gov script, GovernorConfig.Gov mirror, int idx) {
        GovernorConfig.Gov o = new GovernorConfig.Gov();
        o.governor = pick(script == null ? null : script.governor,
                mirror == null ? null : mirror.governor);
        if (idx == 0) {
            o.upThreshold = pick(script == null ? null : script.upThreshold,
                    mirror == null ? null : mirror.upThreshold);
            o.downThreshold = pick(script == null ? null : script.downThreshold,
                    mirror == null ? null : mirror.downThreshold);
            o.freqStep = pick(script == null ? null : script.freqStep,
                    mirror == null ? null : mirror.freqStep);
            o.samplingRate = pick(script == null ? null : script.samplingRate,
                    mirror == null ? null : mirror.samplingRate);
            o.ignoreNiceLoad = script != null ? script.ignoreNiceLoad
                    : mirror != null && mirror.ignoreNiceLoad;
        }
        if (idx > 0) {
            o.targetLoads = pick(script == null ? null : script.targetLoads,
                    mirror == null ? null : mirror.targetLoads);
        }
        o.minFreq = pick(script == null ? null : script.minFreq,
                mirror == null ? null : mirror.minFreq);
        o.maxFreq = pick(script == null ? null : script.maxFreq,
                mirror == null ? null : mirror.maxFreq);
        boolean[] src = script != null && script.hasOnline ? script.cores
                : mirror != null ? mirror.cores : null;
        if (src != null) System.arraycopy(src, 0, o.cores, 0, 8);
        return o;
    }

    private String pick(String a, String b) {
        return a != null && !a.isEmpty() ? a : b;
    }

    /** 一次 su 批量回读 4 个脚本并比对，返回不一致的文件数 */
    private int verifySaved(java.util.List<String> dirs) {
        StringBuilder cmd = new StringBuilder();
        for (int d = 0; d < dirs.size(); d++) {
            for (int i = 0; i < 4; i++) {
                cmd.append("echo '==CF=").append(d).append('_').append(FILES[i])
                        .append("='; cat '").append(dirs.get(d)).append("/").append(FILES[i]).append("'; ");
            }
        }
        RootShell.Result r = RootShell.exec(cmd.toString());
        if (!r.ok()) return 4;
        int bad = 0;
        for (int d = 0; d < dirs.size(); d++) {
            for (int i = 0; i < 4; i++) {
                String marker = "==CF=" + d + "_" + FILES[i] + "=";
                int s = r.out.indexOf(marker);
                if (s < 0) {
                    bad++;
                    continue;
                }
                int from = s + marker.length();
                int e = r.out.indexOf("==CF=", from);
                String content = e < 0 ? r.out.substring(from) : r.out.substring(from, e);
                GovernorConfig.Gov b = GovernorConfig.parse(content, i == 0);
                if (b == null || !sameGov(b, govs[i], i)) bad++;
            }
        }
        return bad;
    }

    /** 逐字段比较回读配置与保存时的配置（null/缺失 视为不一致） */
    private boolean sameGov(GovernorConfig.Gov a, GovernorConfig.Gov b, int idx) {
        if (!eqv(a.governor, b.governor)) return false;
        if (idx == 0) {
            if (!eqv(a.upThreshold, b.upThreshold)) return false;
            if (!eqv(a.downThreshold, b.downThreshold)) return false;
            if (!eqv(a.freqStep, b.freqStep)) return false;
            if (!eqv(a.samplingRate, b.samplingRate)) return false;
        }
        if (idx > 0) {
            if (!eqv(a.targetLoads, b.targetLoads)) return false;
        }
        // 限频字段：null/空/"0" 均视为"不限制"，等价比较（避免不限制时回读误报不符）
        if (!eqvF(a.minFreq, b.minFreq)) return false;
        if (!eqvF(a.maxFreq, b.maxFreq)) return false;
        return java.util.Arrays.equals(a.cores, b.cores);
    }

    /** 限频字段等价比较（null/空/"0" 视为不限制） */
    private boolean eqvF(String a, String b) {
        return normF(a).equals(normF(b));
    }

    private String normF(String s) {
        if (s == null) return "";
        s = s.trim();
        return "0".equals(s) ? "" : s;
    }

    private boolean eqv(String a, String b) {
        return a != null && a.equals(b);
    }

    // ==================== color.lax 导入导出（4 个模式全部参数） ====================

    /** 导入文件选择器请求码 */
    private static final int REQ_IMPORT = 7301;

    /** 导出全部 4 个模式的调速器参数（名称/数值/核心开关）到内部储存根目录 /storage/emulated/0/color.lax */
    private void exportLax() {
        if (loading || govs[0] == null) {
            Toast.makeText(this, "配置仍在加载中", Toast.LENGTH_SHORT).show();
            return;
        }
        collectInputs();
        LinkedHashMap<String, String> block = new LinkedHashMap<>();
        StringBuilder cs = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            GovernorConfig.Gov g = govs[i];
            block.put("gov." + i + ".name", g.governor);
            if (i == 0) {
                block.put("gov.0.up", g.upThreshold);
                block.put("gov.0.down", g.downThreshold);
                block.put("gov.0.step", g.freqStep);
                block.put("gov.0.rate", g.samplingRate);
            } else {
                block.put("gov." + i + ".loads", g.targetLoads);
            }
            cs.setLength(0);
            for (boolean c : g.cores) cs.append(c ? '1' : '0');
            block.put("gov." + i + ".cores", cs.toString());
            block.put("gov." + i + ".fmin", g.minFreq == null || g.minFreq.isEmpty() ? "0" : g.minFreq);
            block.put("gov." + i + ".fmax", g.maxFreq == null || g.maxFreq.isEmpty() ? "0" : g.maxFreq);
        }
        new Thread(() -> {
            RootShell.Result r = LaxStore.write(getCacheDir(), block);
            runOnUiThread(() -> Toast.makeText(this, r.ok()
                    ? "已导出 4 个模式到内部储存根目录 /storage/emulated/0/color.lax"
                    : "导出失败：" + r.err, Toast.LENGTH_LONG).show());
        }).start();
    }

    /** 打开系统文件选择器，任意路径选择 lax 文件导入调速器参数（导入后点保存生效） */
    private void importLax() {
        if (loading || govs[0] == null) {
            Toast.makeText(this, "配置仍在加载中", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        it.addCategory(Intent.CATEGORY_OPENABLE);
        it.setType("*/*");
        startActivityForResult(it, REQ_IMPORT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_IMPORT || resultCode != RESULT_OK
                || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        if (loading || govs[0] == null) {
            Toast.makeText(this, "配置仍在加载中", Toast.LENGTH_SHORT).show();
            return;
        }
        new Thread(() -> {
            LinkedHashMap<String, String> map = LaxStore.readUri(this, uri);
            runOnUiThread(() -> {
                if (map.isEmpty()) {
                    Toast.makeText(this, "无法读取所选文件", Toast.LENGTH_SHORT).show();
                    return;
                }
                int n = applyLax(map);
                if (n == 0) {
                    Toast.makeText(this, "文件中没有调速器参数", Toast.LENGTH_SHORT).show();
                    return;
                }
                fillInputs();
                Toast.makeText(this, "已导入 " + n + " 项（4 个模式），点击保存后生效",
                        Toast.LENGTH_LONG).show();
            });
        }).start();
    }

    /** 应用 lax 中的 gov.* 到内存配置（缺失的项保持原值），返回应用项数 */
    private int applyLax(Map<String, String> map) {
        int n = 0;
        for (int i = 0; i < 4; i++) {
            GovernorConfig.Gov g = govs[i];
            if (g == null) continue;
            String v;
            if ((v = map.get("gov." + i + ".name")) != null && !v.isEmpty()) {
                g.governor = v;
                n++;
            }
            if (i == 0) {
                if ((v = map.get("gov.0.up")) != null && !v.isEmpty()) {
                    g.upThreshold = v;
                    n++;
                }
                if ((v = map.get("gov.0.down")) != null && !v.isEmpty()) {
                    g.downThreshold = v;
                    n++;
                }
                if ((v = map.get("gov.0.step")) != null && !v.isEmpty()) {
                    g.freqStep = v;
                    n++;
                }
                if ((v = map.get("gov.0.rate")) != null && !v.isEmpty()) {
                    g.samplingRate = v;
                    n++;
                }
            } else {
                if ((v = map.get("gov." + i + ".loads")) != null && !v.isEmpty()) {
                    g.targetLoads = v;
                    n++;
                }
            }
            String cs = map.get("gov." + i + ".cores");
            if (cs != null && cs.length() == 8) {
                for (int c = 0; c < 8; c++) g.cores[c] = cs.charAt(c) == '1';
                n++;
            }
            if ((v = map.get("gov." + i + ".fmin")) != null && !v.isEmpty()) {
                g.minFreq = v;
                n++;
            }
            if ((v = map.get("gov." + i + ".fmax")) != null && !v.isEmpty()) {
                g.maxFreq = v;
                n++;
            }
        }
        return n;
    }
}
