package Color.fc;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 主题与背景：日/夜间、全透明背景（透壁纸）、自定义背景图（透明度/缩放/裁剪偏移）
 * 所有修改实时应用到当前页面，直接预览沉浸效果
 */
public class ThemeActivity extends ThemedActivity {

    private static final int REQ_PICK = 71;

    private TextView chipDay, chipNight, alphaValue, scaleValue, offXValue, offYValue;
    private Switch swTransparent, swImage;
    private ImageView preview;
    private TextView pickBtn, clearBtn;
    private LinearLayout imageGroup;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), 0, dp(12), dp(24));
        sv.addView(root);
        setContentView(sv);

        // 顶栏
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, dp(14), 0, dp(10));
        ImageView back = new ImageView(this);
        back.setImageResource(R.drawable.ic_back);
        back.setPadding(dp(8), dp(8), dp(8), dp(8));
        android.util.TypedValue tv = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true);
        back.setBackgroundResource(tv.resourceId);
        back.setOnClickListener(v -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(40), dp(40)));
        TextView title = new TextView(this);
        title.setText("主题与背景");
        title.setTextSize(19);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        title.setTextColor(getResources().getColor(R.color.textPrimary));
        header.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(header);

        // ===== 外观卡片：日/夜间 =====
        LinearLayout card1 = card();
        TextView l1 = label("外观");
        card1.addView(l1);
        LinearLayout seg = new LinearLayout(this);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(6);
        seg.setLayoutParams(slp);
        chipDay = segChip("日间");
        chipNight = segChip("夜间");
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        clp.rightMargin = dp(3);
        seg.addView(chipDay, clp);
        LinearLayout.LayoutParams clp2 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        clp2.leftMargin = dp(3);
        seg.addView(chipNight, clp2);
        card1.addView(seg);
        root.addView(card1);

        chipDay.setOnClickListener(v -> {
            if (!ThemeStore.dark(this)) return;
            ThemeStore.setDark(this, false);
        });
        chipNight.setOnClickListener(v -> {
            if (ThemeStore.dark(this)) return;
            ThemeStore.setDark(this, true);
        });

        // ===== 背景沉浸卡片 =====
        LinearLayout card2 = card();
        TextView l2 = label("背景沉浸");
        card2.addView(l2);

        // 全透明背景
        LinearLayout rowT = row("全透明背景", "不绘制任何底色，直接透出系统壁纸");
        swTransparent = new Switch(this);
        rowT.addView(swTransparent);
        card2.addView(rowT);
        swTransparent.setOnCheckedChangeListener((b, on) -> {
            ThemeStore.setTransparent(this, on);
            ThemeStore.applyBackground(this);
        });

        card2.addView(divider());

        // 自定义背景图开关
        LinearLayout rowI = row("自定义背景图片", "选择图片作为全部页面的沉浸背景");
        swImage = new Switch(this);
        rowI.addView(swImage);
        card2.addView(rowI);
        swImage.setOnCheckedChangeListener((b, on) -> {
            ThemeStore.setImageBg(this, on);
            ThemeStore.applyBackground(this);
            refreshPreview();
        });

        // 图片选择区
        imageGroup = new LinearLayout(this);
        imageGroup.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams iglp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        iglp.topMargin = dp(4);
        imageGroup.setLayoutParams(iglp);

        preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        preview.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(12));
            }
        });
        preview.setClipToOutline(true);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(132));
        imageGroup.addView(preview, plp);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(8);
        btns.setLayoutParams(blp);
        pickBtn = textBtn("选择图片", R.color.accent);
        clearBtn = textBtn("清除图片", R.color.red);
        LinearLayout.LayoutParams pb1 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        pb1.rightMargin = dp(3);
        btns.addView(pickBtn, pb1);
        LinearLayout.LayoutParams pb2 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        pb2.leftMargin = dp(3);
        btns.addView(clearBtn, pb2);
        imageGroup.addView(btns);
        card2.addView(imageGroup);

        pickBtn.setOnClickListener(v ->
                startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT), REQ_PICK));
        clearBtn.setOnClickListener(v -> {
            File f = ThemeStore.bgFile(this);
            if (f.exists() && f.delete()) {
                ThemeStore.setImageBg(this, false);
                ThemeStore.invalidate();
                ThemeStore.applyBackground(this);
                refreshPreview();
                Toast.makeText(this, "已清除背景图片", Toast.LENGTH_SHORT).show();
            }
        });

        // 透明度
        card2.addView(divider());
        LinearLayout rowA = row("背景透明度", "数值越小图片越淡");
        alphaValue = val();
        rowA.addView(alphaValue);
        card2.addView(rowA);
        card2.addView(seek(5, 100, ThemeStore.bgAlpha(this), (sb, p) -> {
            ThemeStore.setBgAlpha(this, p);
            alphaValue.setText(p + "%");
            ThemeStore.applyBackground(this);
        }));

        // 缩放
        LinearLayout rowS = row("背景缩放", "放大图片后再按偏移裁剪取景");
        scaleValue = val();
        rowS.addView(scaleValue);
        card2.addView(rowS);
        card2.addView(seek(100, 300, ThemeStore.bgScale(this), (sb, p) -> {
            ThemeStore.setBgScale(this, p);
            scaleValue.setText(p + "%");
            ThemeStore.applyBackground(this);
        }));

        // 偏移 X
        LinearLayout rowX = row("取景偏移 左右", "配合缩放裁剪图片左右区域");
        offXValue = val();
        rowX.addView(offXValue);
        card2.addView(rowX);
        card2.addView(seek(-50, 50, ThemeStore.bgOffX(this), (sb, p) -> {
            ThemeStore.setBgOffX(this, p);
            offXValue.setText(p + "%");
            ThemeStore.applyBackground(this);
        }));

        // 偏移 Y
        LinearLayout rowY = row("取景偏移 上下", "配合缩放裁剪图片上下区域");
        offYValue = val();
        rowY.addView(offYValue);
        card2.addView(rowY);
        card2.addView(seek(-50, 50, ThemeStore.bgOffY(this), (sb, p) -> {
            ThemeStore.setBgOffY(this, p);
            offYValue.setText(p + "%");
            ThemeStore.applyBackground(this);
        }));

        root.addView(card2);

        // 底部说明
        TextView tip = new TextView(this);
        tip.setText("日/夜间与背景对所有页面生效；修改即时预览，无需保存");
        tip.setTextSize(11);
        tip.setTextColor(getResources().getColor(R.color.textDim));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(10);
        root.addView(tip, tlp);

        refreshAll();
    }

    /** 选择图片 → 压缩保存为应用私有 bg.jpg */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK || resultCode != Activity.RESULT_OK || data == null
                || data.getData() == null) return;
        Uri uri = data.getData();
        new Thread(() -> {
            boolean ok = saveBg(uri);
            runOnUiThread(() -> {
                if (!ok) {
                    Toast.makeText(this, "图片读取失败，请换一张", Toast.LENGTH_SHORT).show();
                    return;
                }
                ThemeStore.setImageBg(this, true);
                ThemeStore.invalidate();
                ThemeStore.applyBackground(this);
                refreshPreview();
                Toast.makeText(this, "背景已更新", Toast.LENGTH_SHORT).show();
            });
        }).start();
    }

    /** 解码原图（2560 上限抽样）后保存为 JPEG */
    private boolean saveBg(Uri uri) {
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(is, null, o);
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= 2560) sample *= 2;
            try (InputStream is2 = getContentResolver().openInputStream(uri)) {
                BitmapFactory.Options o2 = new BitmapFactory.Options();
                o2.inSampleSize = sample;
                Bitmap bmp = BitmapFactory.decodeStream(is2, null, o2);
                if (bmp == null) return false;
                File out = ThemeStore.bgFile(this);
                try (OutputStream os = java.nio.file.Files.newOutputStream(out.toPath())) {
                    bmp.compress(Bitmap.CompressFormat.JPEG, 90, os);
                }
                bmp.recycle();
                return true;
            }
        } catch (Exception e) {
            return false;
        }
    }

    /** 刷新全部控件状态 */
    private void refreshAll() {
        boolean dark = ThemeStore.dark(this);
        styleChip(chipDay, !dark);
        styleChip(chipNight, dark);
        swTransparent.setChecked(ThemeStore.transparentBg(this));
        swImage.setChecked(ThemeStore.imageBg(this) && ThemeStore.bgFile(this).exists());
        alphaValue.setText(ThemeStore.bgAlpha(this) + "%");
        scaleValue.setText(ThemeStore.bgScale(this) + "%");
        offXValue.setText(ThemeStore.bgOffX(this) + "%");
        offYValue.setText(ThemeStore.bgOffY(this) + "%");
        refreshPreview();
    }

    private void refreshPreview() {
        boolean has = ThemeStore.bgFile(this).exists();
        imageGroup.setVisibility(has ? View.VISIBLE : View.GONE);
        if (has) {
            File f = ThemeStore.bgFile(this);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 4;
            Bitmap bmp = BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            preview.setImageBitmap(bmp);
        } else {
            preview.setImageDrawable(null);
        }
    }

    // ==================== UI 构造 ====================

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundResource(R.drawable.bg_card);
        c.setPadding(dp(12), dp(10), dp(12), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        c.setLayoutParams(lp);
        return c;
    }

    private TextView label(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(14);
        v.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        v.setTextColor(getResources().getColor(R.color.textPrimary));
        return v;
    }

    private LinearLayout row(String title, String sub) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        row.setLayoutParams(lp);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(13);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setTextColor(getResources().getColor(R.color.textPrimary));
        box.addView(t);
        TextView s = new TextView(this);
        s.setText(sub);
        s.setTextSize(10.5f);
        s.setTextColor(getResources().getColor(R.color.textSecondary));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sl.topMargin = dp(1);
        box.addView(s, sl);
        row.addView(box, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private TextView val() {
        TextView v = new TextView(this);
        v.setTextSize(12);
        v.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        v.setTextColor(getResources().getColor(R.color.accent));
        return v;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(getResources().getColor(R.color.divider));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1);
        lp.topMargin = dp(10);
        lp.bottomMargin = dp(2);
        v.setLayoutParams(lp);
        return v;
    }

    private interface OnSeek {
        void onSeek(SeekBar sb, int progress);
    }

    private SeekBar seek(int min, int max, int cur, OnSeek cb) {
        SeekBar sb = new SeekBar(this);
        sb.setMax(max - min);
        sb.setProgress(cur - min);
        try {
            sb.getProgressDrawable().setColorFilter(
                    getResources().getColor(R.color.accent), android.graphics.PorterDuff.Mode.SRC_IN);
            sb.getThumb().setColorFilter(
                    getResources().getColor(R.color.accent), android.graphics.PorterDuff.Mode.SRC_IN);
        } catch (Exception ignored) {
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(2);
        sb.setLayoutParams(lp);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser) cb.onSeek(s, min + p);
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        return sb;
    }

    /** 日/夜间分段选择芯片 */
    private TextView segChip(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setGravity(Gravity.CENTER);
        v.setTextSize(13);
        v.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        v.setBackgroundResource(R.drawable.bg_tab);
        v.setPadding(0, dp(9), 0, dp(9));
        return v;
    }

    private void styleChip(TextView v, boolean on) {
        v.setBackgroundResource(on ? R.drawable.bg_tab_sel : R.drawable.bg_tab);
        v.setTextColor(getResources().getColor(on ? R.color.accent : R.color.textSecondary));
    }

    /** 圆角文字按钮 */
    private TextView textBtn(String t, int colorRes) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setGravity(Gravity.CENTER);
        v.setTextSize(13);
        v.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        v.setTextColor(getResources().getColor(colorRes));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(11));
        bg.setColor(getResources().getColor(R.color.bgInput));
        bg.setStroke(dp(1), getResources().getColor(colorRes));
        v.setBackground(bg);
        v.setPadding(0, dp(9), 0, dp(9));
        Color.fc.view.Beam.press(v);
        return v;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
