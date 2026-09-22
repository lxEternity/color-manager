package Color.fc;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.RotateAnimation;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
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
    private final HashMap<String, Spinner> spinners = new HashMap<>();
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
        int[] colors = {0xFF10B981, 0xFF3B82F6, 0xFFF59E0B, 0xFFEF4444};

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
                sb.getProgressDrawable().setColorFilter(0xFF00B8D4, android.graphics.PorterDuff.Mode.SRC_IN);
                sb.getThumb().setColorFilter(0xFF00B8D4, android.graphics.PorterDuff.Mode.SRC_IN);
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

    /** 预设调速器下拉选择（不支持手动输入） */
    private void addGovernorSelector(LinearLayout box, String key, String label, String def) {
        View row = getLayoutInflater().inflate(R.layout.governor_row, box, false);
        ((TextView) row.findViewById(R.id.label)).setText(label);
        Spinner sp = row.findViewById(R.id.spinner);
        ArrayAdapter<String> ad = new ArrayAdapter<>(this, R.layout.gov_spinner_item, GOV_PRESETS);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(ad);
        selectGov(sp, def);
        spinners.put(key, sp);
        box.addView(row);
    }

    /** 选中指定调速器；脚本读到的值不在预设列表时追加显示，保证回显真实 */
    private void selectGov(Spinner sp, String v) {
        if (v == null || v.isEmpty()) v = GOV_PRESETS[0];
        @SuppressWarnings("unchecked")
        ArrayAdapter<String> ad = (ArrayAdapter<String>) sp.getAdapter();
        int pos = ad.getPosition(v);
        if (pos < 0) {
            ad.add(v);
            pos = ad.getPosition(v);
        }
        sp.setSelection(pos, false);
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
            for (int j = 0; j < fields[i].length; j++) {
                String key = i + "." + fields[i][j];
                String v = readField(g, fields[i][j]);
                if (v == null || v.isEmpty()) v = defaults[i][j];
                if ("governor".equals(fields[i][j])) {
                    Spinner sp = spinners.get(key);
                    if (sp != null) selectGov(sp, v);
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
            for (int j = 0; j < fields[i].length; j++) {
                String f = fields[i][j];
                if ("governor".equals(f)) {
                    Spinner sp = spinners.get(i + "." + f);
                    if (sp != null && sp.getSelectedItem() != null) {
                        writeField(govs[i], f, sp.getSelectedItem().toString());
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

        Toast.makeText(this, "正在写入调速器脚本…", Toast.LENGTH_SHORT).show();
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
                    Toast.makeText(this, "调速器配置已全部保存（4/4）", Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, "保存 " + okF + "/4，失败：" + errF, Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }
}
