package Color.fc;

import android.app.Activity;
import android.app.Dialog;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import Color.fc.view.Warp;

/**
 * 悬浮窗管理器底部弹窗（照搬 Kin-app OverlayManagerSheet，仿 Metric）：
 * - 四个独立监视悬浮窗各自开关（负载/进程/迷你/温度），帧率记录器与源码一致暂时下线不显示
 * - 行样式与源码一致：44dp 圆角图标块（日间浅底/夜间主色 16%）+ 标题 + 描述 + 开关
 * - 开关落地 = Prefs(ovl_*) + MonitorService.show/hide；未授权悬浮窗权限时开关回弹，
 *   回调主页跳转权限页（主页授权返回后自动开启并同步开关状态）
 * - 弹窗期间窗内（✕/长按）关闭监视器 → 回到前台 syncStates() 重读 Prefs 同步开关
 */
public class OverlayManagerSheet {

    /** 主页回调：开关最终落地 / 需要跳转悬浮窗权限页 */
    public interface Host {
        void onOvlToggle(int type, boolean on);

        void onOvlNeedPermission(int type);
    }

    // 与 Kin OVL_SPECS 一致（FPS 暂时下线，不显示）
    private static final int[] ICONS = {
            R.drawable.ic_ovl_speed, R.drawable.ic_ovl_list,
            R.drawable.ic_ovl_circle, R.drawable.ic_ovl_thermo};
    private static final int[] TINTS = {0xFF0FA5A5, 0xFF34A853, 0xFFFF8A34, 0xFFF97316};
    private static final int[] CONTAINERS = {0xFFDFF4F2, 0xFFE4F4E8, 0xFFFFF0E2, 0xFFFFEDE3};
    private static final String[] TITLES = {"负载监视器", "进程监视器", "迷你监视器", "温度监视器"};
    private static final String[] DESCS = {
            "以悬浮窗显示当前设备负载",
            "以悬浮窗显示运行中的进程信息",
            "顶部居中细条显示性能概览(点击穿透)",
            "以悬浮窗显示设备温度"};

    private final Activity ctx;
    private final Host host;
    private final Dialog dialog;
    private final Switch[] switches = new Switch[MonitorService.N_TYPES];

    public OverlayManagerSheet(Activity ctx, Host host) {
        this.ctx = ctx;
        this.host = host;
        this.dialog = build();
    }

    public void show() {
        syncStates();
        dialog.show();
    }

    public void dismiss() {
        try {
            dialog.dismiss();
        } catch (Exception ignored) {
        }
    }

    public boolean isShowing() {
        return dialog.isShowing();
    }

    /** 从 Prefs 重读各开关状态（授权返回 / 窗内关闭后同步，照搬 Kin ovlEnabledFlow） */
    public void syncStates() {
        SharedPreferences p = ctx.getSharedPreferences("colorfc", Activity.MODE_PRIVATE);
        for (int i = 0; i < switches.length; i++) {
            if (switches[i] != null) {
                switches[i].setChecked(p.getBoolean(MonitorService.PREF_KEYS[i], false));
            }
        }
    }

    /** 开关切换：未授权时回弹并回调主页跳权限页 */
    private void toggle(int type, boolean on) {
        if (on && !Settings.canDrawOverlays(ctx)) {
            if (switches[type] != null) switches[type].setChecked(false);
            host.onOvlNeedPermission(type);
            return;
        }
        host.onOvlToggle(type, on);
    }

    // ==================== 弹窗构建 ====================

    private Dialog build() {
        boolean dark = ThemeStore.dark(ctx);
        int sheetBg = ctx.getResources().getColor(R.color.bg);
        int textPrimary = ctx.getResources().getColor(R.color.textPrimary);
        int textSecondary = ctx.getResources().getColor(R.color.textSecondary);

        Dialog d = new Dialog(ctx);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, dp(8), 0, dp(16));
        // 源码 ModalBottomSheet：surfaceContainerLow 底 + 顶部 28dp 圆角
        // （此 App 无该层级，用页面底色 bg 承托 bgCard 卡片行，层级关系与源码一致）
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(sheetBg);
        bg.setCornerRadii(new float[]{dp(28), dp(28), dp(28), dp(28), 0, 0, 0, 0});
        root.setBackground(bg);

        // 顶部拖动条
        View bar = new View(ctx);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(36), dp(4));
        blp.gravity = Gravity.CENTER_HORIZONTAL;
        bar.setLayoutParams(blp);
        bar.setBackgroundResource(R.drawable.bg_sheet_bar);
        root.addView(bar);

        // 标题行：悬浮窗管理器 / 选择要显示的悬浮窗 + 关闭
        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(20), dp(14), dp(20), dp(14));
        root.addView(head, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout titleBox = new LinearLayout(ctx);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(ctx);
        title.setText("悬浮窗管理器");
        title.setTextColor(textPrimary);
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        titleBox.addView(title);
        TextView sub = new TextView(ctx);
        sub.setText("选择要显示的悬浮窗");
        sub.setTextColor(textSecondary);
        sub.setTextSize(12);
        titleBox.addView(sub);
        head.addView(titleBox, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        ImageView close = new ImageView(ctx);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(34), dp(34));
        close.setLayoutParams(clp);
        close.setPadding(dp(6), dp(6), dp(6), dp(6));
        close.setImageResource(R.drawable.ic_back);
        close.setRotation(90);
        close.setColorFilter(textSecondary);
        // 主题涟漪背景（selectableItemBackgroundBorderless）
        int[] attrs = new int[]{android.R.attr.selectableItemBackgroundBorderless};
        android.content.res.TypedArray ta = ctx.getTheme().obtainStyledAttributes(attrs);
        close.setBackground(ta.getDrawable(0));
        ta.recycle();
        head.addView(close);

        // 四行监视器列表
        LinearLayout list = new LinearLayout(ctx);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(20), 0, dp(20), 0);
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        for (int t = 0; t < MonitorService.N_TYPES; t++) {
            list.addView(buildRow(t, dark, textPrimary, textSecondary));
        }

        d.setContentView(root);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(android.graphics.Color.TRANSPARENT));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
        }
        close.setOnClickListener(v -> d.dismiss());
        return d;
    }

    /** 单行监视器：图标块 + 标题/描述 + 开关（整行可点，照搬 Kin OverlayRow） */
    private View buildRow(final int type, boolean dark, int textPrimary, int textSecondary) {
        int tint = TINTS[type];

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(10);
        row.setLayoutParams(rlp);
        // 源码行底：surface + 18dp 圆角
        GradientDrawable rbg = new GradientDrawable();
        rbg.setColor(ctx.getResources().getColor(R.color.bgCard));
        rbg.setCornerRadius(dp(18));
        rbg.setStroke(dp(1), ctx.getResources().getColor(R.color.bgCardStroke));
        row.setBackground(rbg);
        Warp.press(row);

        // 图标块：44dp 圆角 13dp（日间浅色容器 / 夜间主色 16%）
        LinearLayout iconBox = new LinearLayout(ctx);
        iconBox.setGravity(Gravity.CENTER);
        iconBox.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(44)));
        GradientDrawable ibg = new GradientDrawable();
        ibg.setCornerRadius(dp(13));
        ibg.setColor(dark ? ((tint & 0x00FFFFFF) | 0x29000000) : CONTAINERS[type]);
        iconBox.setBackground(ibg);
        ImageView icon = new ImageView(ctx);
        icon.setLayoutParams(new LinearLayout.LayoutParams(dp(24), dp(24)));
        icon.setImageResource(ICONS[type]);
        icon.setColorFilter(tint);
        iconBox.addView(icon);
        row.addView(iconBox);

        // 标题 + 描述
        LinearLayout texts = new LinearLayout(ctx);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.setMarginStart(dp(12));
        texts.setLayoutParams(tlp);
        TextView name = new TextView(ctx);
        name.setText(TITLES[type]);
        name.setTextColor(textPrimary);
        name.setTextSize(15);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        texts.addView(name);
        TextView desc = new TextView(ctx);
        desc.setText(DESCS[type]);
        desc.setTextColor(textSecondary);
        desc.setTextSize(11.5f);
        desc.setMaxLines(2);
        texts.addView(desc);
        row.addView(texts);

        // 开关（theme 的 colorControlActivated=accent 自动着色；不单独可点，整行触发）
        Switch sw = new Switch(ctx);
        sw.setClickable(false);
        sw.setFocusable(false);
        row.addView(sw);
        switches[type] = sw;

        row.setOnClickListener(v -> {
            boolean now = !sw.isChecked();
            sw.setChecked(now);
            toggle(type, now);
        });
        return row;
    }

    private int dp(int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }
}
