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

import java.util.HashMap;

/**
 * 调速器配置：conservative.sh（省电）/ scx1.sh（均衡）/ scx2.sh（性能）/ scx3.sh（极速）
 * 每个模式调速器名称与参数可编辑，输入框内默认值可视化（conservative / scx）
 */
public class GovernorActivity extends Activity {

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_governor);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.saveBtn).setOnClickListener(v -> saveAll());

        buildCards();

        new Thread(() -> {
            for (int i = 0; i < 4; i++) {
                String content = RootShell.readFile(RootShell.GOV_DIR + "/" + FILES[i]);
                GovernorConfig.Gov g = GovernorConfig.parse(content, i == 0);
                if (g == null) g = new GovernorConfig.Gov();
                govs[i] = g;
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
        label.setTextColor(0xFF5D6B85);
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
        bg.setColor(on ? 0xFF0096C8 : 0xFFE2E8F0);
        chip.setTextColor(on ? 0xFFFFFFFF : 0xFF8A94A8);
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
        String[][] fields = {
                {"governor", "upThreshold", "downThreshold", "freqStep", "samplingRate"},
                {"governor", "targetLoads"},
                {"governor", "targetLoads"},
                {"governor"}
        };
        String[][] defaults = {
                {"conservative", "98", "93", "1", "14000"},
                {"scx", "90"},
                {"scx", "70"},
                {"scx"}
        };
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
            for (int j = 0; j < fields[i].length; j++) {
                String key = i + "." + fields[i][j];
                String v = readField(g, fields[i][j]);
                if (v == null || v.isEmpty()) v = defaults[i][j];
                if ("governor".equals(fields[i][j])) {
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

    /** 保存 4 个调速器脚本 */
    private void saveAll() {
        if (loading || govs[0] == null) {
            Toast.makeText(this, "配置仍在加载中", Toast.LENGTH_SHORT).show();
            return;
        }
        String[][] fields = {
                {"governor", "upThreshold", "downThreshold", "freqStep", "samplingRate"},
                {"governor", "targetLoads"},
                {"governor", "targetLoads"},
                {"governor"}
        };
        String[][] defaults = {
                {"conservative", "98", "93", "1", "14000"},
                {"scx", "90"},
                {"scx", "70"},
                {"scx"}
        };
        for (int i = 0; i < 4; i++) {
            // 芯片状态写回
            TextView[] chips = coreChips.get(String.valueOf(i));
            if (chips != null && govs[i] != null) {
                for (int c = 0; c < 8; c++) {
                    Object t = chips[c].getTag();
                    govs[i].cores[c] = t == null || (Boolean) t;
                }
            }
            for (int j = 0; j < fields[i].length; j++) {
                String f = fields[i][j];
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
                if (v.isEmpty()) v = defaults[i][j];
                writeField(govs[i], f, v);
            }
        }

        new Thread(() -> {
            String[] contents = {
                    GovernorConfig.generateConservative(govs[0]),
                    GovernorConfig.generateScx(govs[1]),
                    GovernorConfig.generateScx(govs[2]),
                    GovernorConfig.generateScx3(govs[3])
            };
            int ok = 0;
            StringBuilder err = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                RootShell.Result r = RootShell.writeFile(getCacheDir(), contents[i],
                        RootShell.GOV_DIR + "/" + FILES[i]);
                if (r.ok()) ok++;
                else err.append(FILES[i]).append(": ").append(r.err).append('\n');
            }
            final int okF = ok;
            final String errF = err.toString();
            runOnUiThread(() -> {
                if (okF == 4) {
                    Toast.makeText(this, "保存成功", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "保存 " + okF + "/4，失败：" + errF, Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }
}
