package Color.fc;

import android.app.Activity;
import android.app.Dialog;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 底部收纳式应用选择菜单：可搜索、带图标、显示已配置状态
 */
public class AppPickerDialog {

    public interface PickCb { void on(String pkg, String label); }

    public static void show(Activity ctx, final Map<String, String> existing, final PickCb cb) {
        final List<ResolveInfo> all = new ArrayList<>();
        try {
            android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_MAIN);
            i.addCategory(android.content.Intent.CATEGORY_LAUNCHER);
            all.addAll(ctx.getPackageManager().queryIntentActivities(i, 0));
        } catch (Exception ignored) {
        }
        Collections.sort(all, (a, b) -> String.valueOf(a.loadLabel(ctx.getPackageManager()))
                .toLowerCase(Locale.getDefault())
                .compareTo(String.valueOf(b.loadLabel(ctx.getPackageManager()))
                        .toLowerCase(Locale.getDefault())));

        final Dialog d = new Dialog(ctx);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setContentView(R.layout.dialog_app_picker);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (int) (ctx.getResources().getDisplayMetrics().heightPixels * 0.78f));
            w.setGravity(Gravity.BOTTOM);
        }

        final List<ResolveInfo> shown = new ArrayList<>(all);
        final ListView list = d.findViewById(R.id.appList);
        final EditText search = d.findViewById(R.id.searchInput);
        d.findViewById(R.id.btnClose).setOnClickListener(v -> d.dismiss());

        final java.util.HashMap<String, Drawable> iconCache = new java.util.HashMap<>();

        android.widget.BaseAdapter adapter = new android.widget.BaseAdapter() {
            @Override public int getCount() { return shown.size(); }
            @Override public Object getItem(int pos) { return shown.get(pos); }
            @Override public long getItemId(int pos) { return pos; }
            @Override
            public View getView(int pos, View cv, ViewGroup parent) {
                final ResolveInfo ri = shown.get(pos);
                if (cv == null) {
                    LinearLayout row = new LinearLayout(ctx);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(dp(ctx, 16), dp(ctx, 9), dp(ctx, 16), dp(ctx, 9));
                    cv = row;

                    ImageView icon = new ImageView(ctx);
                    LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(ctx, 40), dp(ctx, 40));
                    icon.setLayoutParams(ilp);
                    icon.setId(10);
                    row.addView(icon);

                    LinearLayout info = new LinearLayout(ctx);
                    info.setOrientation(LinearLayout.VERTICAL);
                    LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                    flp.leftMargin = dp(ctx, 12);
                    info.setLayoutParams(flp);
                    TextView name = new TextView(ctx);
                    name.setId(11);
                    name.setTextColor(0xFF1B2540);
                    name.setTextSize(14.5f);
                    name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                    info.addView(name);
                    TextView pkg = new TextView(ctx);
                    pkg.setId(12);
                    pkg.setTextColor(0xFF5D6B85);
                    pkg.setTextSize(11f);
                    info.addView(pkg);
                    row.addView(info);

                    TextView badge = new TextView(ctx);
                    LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                    blp.leftMargin = dp(ctx, 10);
                    badge.setLayoutParams(blp);
                    badge.setId(13);
                    badge.setTextSize(11.5f);
                    badge.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                    badge.setPadding(dp(ctx, 8), dp(ctx, 3), dp(ctx, 8), dp(ctx, 3));
                    row.addView(badge);
                }
                ImageView icon = cv.findViewById(10);
                TextView name = cv.findViewById(11);
                TextView pkg = cv.findViewById(12);
                TextView badge = cv.findViewById(13);

                final String label = String.valueOf(ri.loadLabel(ctx.getPackageManager()));
                final String pkgn = ri.activityInfo.packageName;
                name.setText(label);
                pkg.setText(pkgn);

                Drawable ic = iconCache.get(pkgn);
                if (ic == null) {
                    try { ic = ri.loadIcon(ctx.getPackageManager()); } catch (Exception e) { ic = null; }
                    if (ic == null) ic = new ColorDrawable(0xFFDCE4F0);
                    iconCache.put(pkgn, ic);
                }
                icon.setImageDrawable(ic);

                String mode = existing == null ? null : existing.get(pkgn);
                if (mode != null) {
                    badge.setVisibility(View.VISIBLE);
                    badge.setText(mode);
                    android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
                    bg.setColor(0xFFE5F7FD);
                    bg.setCornerRadius(dp(ctx, 9));
                    bg.setStroke(1, 0xFF0096C8);
                    badge.setBackground(bg);
                    badge.setTextColor(0xFF0096C8);
                } else {
                    badge.setVisibility(View.GONE);
                }

                cv.setOnClickListener(v -> {
                    d.dismiss();
                    cb.on(pkgn, label);
                });
                return cv;
            }
        };
        list.setAdapter(adapter);

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                String q = s.toString().trim().toLowerCase(Locale.getDefault());
                shown.clear();
                for (ResolveInfo ri : all) {
                    String label = String.valueOf(ri.loadLabel(ctx.getPackageManager())).toLowerCase(Locale.getDefault());
                    if (q.isEmpty() || label.contains(q)
                            || ri.activityInfo.packageName.toLowerCase(Locale.getDefault()).contains(q)) {
                        shown.add(ri);
                    }
                }
                adapter.notifyDataSetChanged();
            }
        });

        d.show();
    }

    private static int dp(android.content.Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }
}
