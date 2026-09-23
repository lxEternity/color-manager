package Color.fc;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
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

    private final GovernorConfig.Gov[] govs = new GovernorConfig.Gov[4];
    private final HashMap<String, EditText> inputs = new HashMap<>();
    private final HashMap<String, TextView> spinners = new HashMap<>();
    /** 每模式的 8 个核心芯片（key=模式索引） */
    private final HashMap<String, TextView[]> coreChips = new HashMap<>();
    private boolean loading = true;

    /** 预设调速器列表（不再支持手动输入） */
    private static final String[] GOV_PRESETS = {
            "conservative", "walt", "ips", "sugov_next", "scx",
            "hmbird", "powersave", "performance", "schedutil"
    };

    private static final String[] FILES = {"conservative.sh", "scx1.sh", "scx2.sh", "scx3.sh"};
    private static final String[] NAMES = {"省电模式", "均衡模式", "性能模式", "极速模式"};
    private static final String[] DESCS = {
            "省电模式调速器参数（CPU0-7）",
            "均衡模式调速器参数（CPU 0/3/5/7）",
            "性能模式调速器参数（CPU 0/3/5/7）",
            "极速模式全核调速器参数（CPU0-7）"
    };
    /** 每模式的参数字段（fillInputs / collectInputs / lax 共用） */
    private static final String[][] FIELDS = {
            {"governor", "upThreshold", "downThreshold", "freqStep", "samplingRate"},
            {"governor", "targetLoads"},
            {"governor", "targetLoads"},
            {"governor", "targetLoads"}
    };
    private static final String[][] DEFAULTS = {
            {"conservative", "98", "93", "1", "14000"},
            {"scx", "90"},
            {"scx", "70"},
            {"scx", "70"}
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
        findViewById(R.id.saveBtn).setOnClickListener(v -> saveAll());
        findViewById(R.id.btnImport).setOnClickListener(v -> importLax());
        findViewById(R.id.btnExport).setOnClickListener(v -> exportLax());

        buildCards();

        new Thread(() -> {
            GovernorConfig.Gov[] mirror = loadGovMirror();
            for (int i = 0; i < 4; i++) {
                String content = RootShell.readFile(RootShell.GOV_DIR + "/" + FILES[i]);
                GovernorConfig.Gov g = GovernorConfig.parse(content, i == 0);
                govs[i] = mergeGov(g, mirror[i], i);
            }
            runOnUiThread(() -> {
                loading = false;
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
                    "切换预设调速器（写入 scaling_governor）", i == 0 ? "conservative" : "scx");
            if (i == 0) {
                addParam(box, idx + ".upThreshold", "up_threshold 升频阈值（% 负载超过即升频）", "98", true);
                addParam(box, idx + ".downThreshold", "down_threshold 降频阈值（% 负载低于即降频）", "93", true);
                addParam(box, idx + ".freqStep", "freq_step 每次调频步进（%）", "1", true);
                addParam(box, idx + ".samplingRate", "sampling_rate 采样周期（µs）", "14000", false);
            } else {
                addParam(box, idx + ".targetLoads", "target_loads 目标负载（%）",
                        i == 1 ? "90" : "70", true);
            }
            container.addView(card);
        }
    }

    /** 启用核心选择行：8 个可点击芯片，选中=启用该核心 */
    private void addCoreSelector(LinearLayout box, int idx) {
        TextView label = new TextView(this);
        label.setText("启用核心（点击开关，未选中=该模式下关闭此核）");
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
            chip.setOnClickListener(v -> {
                if (loading) return;
                boolean on = !Boolean.TRUE.equals(chip.getTag());
                chip.setTag(on);
                styleChip(chip, on);
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

    /** 上下滑动选择弹窗：当前值高亮，预设外值追加显示 */
    private void showGovPicker(String key, TextView val) {
        String cur = val.getText().toString();
        String[] list = new String[GOV_PRESETS.length];
        int checked = -1;
        for (int i = 0; i < GOV_PRESETS.length; i++) {
            list[i] = GOV_PRESETS[i];
            if (GOV_PRESETS[i].equals(cur)) checked = i;
        }
        final String[] items = list;
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("选择调速器")
                .setSingleChoiceItems(items, checked, (d, w) -> {
                    val.setText(items[w]);
                    d.dismiss();
                })
                .setNegativeButton("取消", null)
                .create();
        if (dlg.getWindow() != null) {
            dlg.getWindow().setBackgroundDrawableResource(R.drawable.bg_dialog);
        }
        dlg.show();
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
                EditText et = inputs.get(i + "." + f);
                if (et == null) continue;
                String v = et.getText().toString().trim();
                if (v.isEmpty()) v = DEFAULTS[i][j];
                writeField(govs[i], f, v);
            }
        }
    }

    /** 保存 4 个调速器脚本 */
    private void saveAll() {
        if (loading || govs[0] == null) {
            Toast.makeText(this, "配置仍在加载中", Toast.LENGTH_SHORT).show();
            return;
        }
        collectInputs();

        new Thread(() -> {
            saveGovMirror();
            String[] contents = {
                    GovernorConfig.generateConservative(govs[0]),
                    GovernorConfig.generateScx(govs[1]),
                    GovernorConfig.generateScx(govs[2]),
                    GovernorConfig.generateScx3(govs[3])
            };
            String[] targets = new String[4];
            for (int i = 0; i < 4; i++) targets[i] = RootShell.GOV_DIR + "/" + FILES[i];
            // 一次 su 完成 4 个主文件写入
            boolean ok = RootShell.writeFiles(getCacheDir(), contents, targets);
            // 一次 su 完成镜像位置同步（best-effort，失败不影响保存结果）
            if (ok) {
                StringBuilder mc = new StringBuilder();
                for (String dir : MIRROR_DIRS) {
                    mc.append("mkdir -p '").append(dir).append("'; ");
                    for (int i = 0; i < 4; i++) {
                        mc.append("cp '").append(targets[i]).append("' '")
                                .append(dir).append("/").append(FILES[i]).append("'; ");
                    }
                }
                RootShell.exec(mc.toString());
            }
            // 写后回读校验，发现被外部回滚立即提示（而不是下次进页面静默回显默认值）
            String verify = "";
            if (ok) {
                try { Thread.sleep(800); } catch (InterruptedException ignored) { }
                int bad = verifySaved();
                if (bad > 0) verify = " · " + bad + " 个文件回读不符(被外部修改?)";
            }
            final boolean okF = ok;
            final String verifyF = verify;
            runOnUiThread(() -> {
                if (okF) {
                    Toast.makeText(this, "保存成功" + verifyF, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "保存失败：脚本写入不成功，请重试", Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    // ==================== 配置镜像（SharedPreferences，回显兜底） ====================

    /** 保存当前 4 模式配置镜像，脚本读取失败/字段缺失时用它兜底回显 */
    private void saveGovMirror() {
        try {
            JSONArray arr = new JSONArray();
            for (int i = 0; i < 4; i++) {
                GovernorConfig.Gov g = govs[i];
                JSONObject o = new JSONObject();
                o.put("name", g.governor);
                if (i == 0) {
                    o.put("up", g.upThreshold);
                    o.put("down", g.downThreshold);
                    o.put("step", g.freqStep);
                    o.put("rate", g.samplingRate);
                } else {
                    o.put("loads", g.targetLoads);
                }
                StringBuilder cs = new StringBuilder();
                for (boolean c : g.cores) cs.append(c ? '1' : '0');
                o.put("cores", cs.toString());
                arr.put(o);
            }
            getSharedPreferences("colorfc", MODE_PRIVATE).edit()
                    .putString("govMirror", arr.toString()).commit();
        } catch (Exception ignored) {
        }
    }

    /** 读取上次保存的配置镜像 */
    private GovernorConfig.Gov[] loadGovMirror() {
        GovernorConfig.Gov[] out = new GovernorConfig.Gov[4];
        try {
            JSONArray arr = new JSONArray(getSharedPreferences("colorfc", MODE_PRIVATE)
                    .getString("govMirror", "[]"));
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
                } else {
                    if (o.has("loads")) g.targetLoads = o.getString("loads");
                }
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
        }
        if (idx > 0) {
            o.targetLoads = pick(script == null ? null : script.targetLoads,
                    mirror == null ? null : mirror.targetLoads);
        }
        boolean[] src = script != null && script.hasOnline ? script.cores
                : mirror != null ? mirror.cores : null;
        if (src != null) System.arraycopy(src, 0, o.cores, 0, 8);
        return o;
    }

    private String pick(String a, String b) {
        return a != null && !a.isEmpty() ? a : b;
    }

    /** 一次 su 批量回读 4 个脚本并比对，返回不一致的文件数 */
    private int verifySaved() {
        StringBuilder cmd = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            cmd.append("echo '==CF=").append(FILES[i]).append("='; cat '")
                    .append(RootShell.GOV_DIR).append("/").append(FILES[i]).append("'; ");
        }
        RootShell.Result r = RootShell.exec(cmd.toString());
        if (!r.ok()) return 4;
        int bad = 0;
        for (int i = 0; i < 4; i++) {
            String marker = "==CF=" + FILES[i] + "=";
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
        return java.util.Arrays.equals(a.cores, b.cores);
    }

    private boolean eqv(String a, String b) {
        return a != null && a.equals(b);
    }

    // ==================== color.lax 导入导出（4 个模式全部参数） ====================

    /** 导出全部 4 个模式的调速器参数（名称/数值/核心开关）到 Download/color.lax */
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
        }
        new Thread(() -> {
            RootShell.Result r = LaxStore.write(getCacheDir(), block);
            runOnUiThread(() -> Toast.makeText(this, r.ok()
                    ? "已导出 4 个模式到 Download/color.lax"
                    : "导出失败：" + r.err, Toast.LENGTH_LONG).show());
        }).start();
    }

    /** 从 Download/color.lax 导入调速器参数（全部模式，导入后点保存生效） */
    private void importLax() {
        if (loading || govs[0] == null) {
            Toast.makeText(this, "配置仍在加载中", Toast.LENGTH_SHORT).show();
            return;
        }
        new Thread(() -> {
            LinkedHashMap<String, String> map = LaxStore.read();
            runOnUiThread(() -> {
                if (map.isEmpty()) {
                    Toast.makeText(this, "未找到 Download/color.lax", Toast.LENGTH_SHORT).show();
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
        }
        return n;
    }
}
