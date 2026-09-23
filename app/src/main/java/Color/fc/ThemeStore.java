package Color.fc;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.drawable.BitmapDrawable;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import java.io.File;

/**
 * 主题存储：日/夜间、全透明背景（透壁纸）、自定义背景图（透明度/缩放/裁剪偏移）
 * 所有页面通过 ThemedActivity 统一应用，实现全局沉浸
 */
public class ThemeStore {

    private static final String SP = "theme";

    // ==================== 读取 ====================

    public static boolean dark(Context c) {
        return c.getSharedPreferences(SP, Context.MODE_PRIVATE).getBoolean("dark", false);
    }

    public static boolean transparentBg(Context c) {
        return c.getSharedPreferences(SP, Context.MODE_PRIVATE).getBoolean("transparent", false);
    }

    /** 自定义背景图开关 */
    public static boolean imageBg(Context c) {
        return c.getSharedPreferences(SP, Context.MODE_PRIVATE).getBoolean("imageBg", false);
    }

    /** 背景图透明度 5-100（%），越小图越淡 */
    public static int bgAlpha(Context c) {
        return c.getSharedPreferences(SP, Context.MODE_PRIVATE).getInt("bgAlpha", 100);
    }

    /** 背景图缩放 100-300（%），放大后再按偏移裁剪 */
    public static int bgScale(Context c) {
        return Math.max(100, c.getSharedPreferences(SP, Context.MODE_PRIVATE).getInt("bgScale", 100));
    }

    /** 水平偏移 -50..50（%屏宽），配合缩放实现裁剪定位 */
    public static int bgOffX(Context c) {
        return c.getSharedPreferences(SP, Context.MODE_PRIVATE).getInt("bgOffX", 0);
    }

    /** 垂直偏移 -50..50（%屏高） */
    public static int bgOffY(Context c) {
        return c.getSharedPreferences(SP, Context.MODE_PRIVATE).getInt("bgOffY", 0);
    }

    /** 主题版本号：日/夜间切换时 +1，ThemedActivity 检测到变化后重建页面 */
    public static int themeVersion(Context c) {
        return c.getSharedPreferences(SP, Context.MODE_PRIVATE).getInt("ver", 0);
    }

    public static File bgFile(Context c) {
        return new File(c.getFilesDir(), "bg.jpg");
    }

    // ==================== 写入 ====================

    public static void setDark(Context c, boolean on) {
        c.getSharedPreferences(SP, Context.MODE_PRIVATE).edit()
                .putBoolean("dark", on).putInt("ver", themeVersion(c) + 1).commit();
    }

    public static void setTransparent(Context c, boolean on) {
        c.getSharedPreferences(SP, Context.MODE_PRIVATE).edit()
                .putBoolean("transparent", on).commit();
    }

    public static void setImageBg(Context c, boolean on) {
        c.getSharedPreferences(SP, Context.MODE_PRIVATE).edit()
                .putBoolean("imageBg", on).commit();
    }

    public static void setBgAlpha(Context c, int v) {
        c.getSharedPreferences(SP, Context.MODE_PRIVATE).edit()
                .putInt("bgAlpha", Math.max(5, Math.min(100, v))).commit();
    }

    public static void setBgScale(Context c, int v) {
        c.getSharedPreferences(SP, Context.MODE_PRIVATE).edit()
                .putInt("bgScale", Math.max(100, Math.min(300, v))).commit();
    }

    public static void setBgOffX(Context c, int v) {
        c.getSharedPreferences(SP, Context.MODE_PRIVATE).edit()
                .putInt("bgOffX", Math.max(-50, Math.min(50, v))).commit();
    }

    public static void setBgOffY(Context c, int v) {
        c.getSharedPreferences(SP, Context.MODE_PRIVATE).edit()
                .putInt("bgOffY", Math.max(-50, Math.min(50, v))).commit();
    }

    // ==================== 应用 ====================

    /** 按当前设置包装 Context（夜间模式应用 -night 资源配色） */
    public static Context wrap(Context base) {
        if (!dark(base)) return base;
        Configuration cfg = new Configuration(base.getResources().getConfiguration());
        cfg.uiMode = (cfg.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | Configuration.UI_MODE_NIGHT_YES;
        return base.createConfigurationContext(cfg);
    }

    /** 在 super.onCreate 前调用：切换日/夜基础主题 */
    public static void applyTheme(Activity a) {
        a.setTheme(dark(a) ? R.style.AppTheme_Dark : R.style.AppTheme);
    }

    /** 渲染缓存失效（任何背景设置变化后调用） */
    public static void invalidate() {
        rendered = null;
        renderKey = null;
    }

    private static Bitmap rendered;
    private static String renderKey;

    /** 应用窗口背景 + 状态栏/导航栏，实现全局沉浸。每个页面 onResume 时调用 */
    public static void applyBackground(Activity a) {
        Window w = a.getWindow();
        boolean dark = dark(a);
        boolean img = imageBg(a) && bgFile(a).exists();
        boolean transp = transparentBg(a);
        boolean immersive = img || transp;

        if (img) {
            BitmapDrawable d = new BitmapDrawable(a.getResources(), renderBg(a, dark));
            d.setAlpha(Math.round(bgAlpha(a) * 2.55f));
            w.setBackgroundDrawable(d);
            w.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);
        } else if (transp) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            w.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);
        } else {
            int solid = dark ? 0xFF0D1220 : 0xFFF5F7FC;
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(solid));
            w.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);
        }

        if (immersive) {
            w.setStatusBarColor(Color.TRANSPARENT);
            w.setNavigationBarColor(Color.TRANSPARENT);
        } else {
            int bar = dark ? 0xFF0D1220 : 0xFFF5F7FC;
            w.setStatusBarColor(bar);
            w.setNavigationBarColor(bar);
        }
        setLightStatusIcons(w, !dark);
    }

    /** 状态栏图标颜色：亮背景用深色图标，暗背景用浅色图标 */
    private static void setLightStatusIcons(Window w, boolean lightBg) {
        View decor = w.getDecorView();
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            w.getInsetsController().setSystemBarsAppearance(
                    lightBg
                            ? android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                            : 0,
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        } else {
            int vis = decor.getSystemUiVisibility();
            if (lightBg) vis |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            else vis &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            decor.setSystemUiVisibility(vis);
        }
    }

    /** 把背景图渲染为屏幕尺寸位图：cover 铺满 × 缩放 × 偏移裁剪（结果缓存） */
    private static synchronized Bitmap renderBg(Context c, boolean dark) {
        File f = bgFile(c);
        android.graphics.Point size = new android.graphics.Point();
        ((WindowManager) c.getSystemService(Context.WINDOW_SERVICE))
                .getDefaultDisplay().getRealSize(size);
        int sw = Math.max(1, size.x), sh = Math.max(1, size.y);
        String key = f.getAbsolutePath() + "|" + f.lastModified() + "|" + sw + "x" + sh
                + "|" + bgScale(c) + "|" + bgOffX(c) + "|" + bgOffY(c) + "|" + dark;
        if (rendered != null && key.equals(renderKey)) return rendered;

        // 先读尺寸，按 2560 上限抽样解码，控制内存
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= 2560) sample *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        Bitmap src = BitmapFactory.decodeFile(f.getAbsolutePath(), o2);
        if (src == null) {
            rendered = null;
            renderKey = null;
            return Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
        }

        float cover = Math.max(sw / (float) src.getWidth(), sh / (float) src.getHeight());
        float zoom = cover * bgScale(c) / 100f;
        Matrix m = new Matrix();
        m.postScale(zoom, zoom);
        float bw = src.getWidth() * zoom, bh = src.getHeight() * zoom;
        // 居中 + 偏移：正偏移向右/下移动图片（相当于裁剪左/上区域）
        m.postTranslate((sw - bw) / 2f + bgOffX(c) / 100f * sw,
                (sh - bh) / 2f + bgOffY(c) / 100f * sh);

        Bitmap out = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        if (dark) cv.drawColor(0x59000000);   // 夜间模式叠加暗色蒙层保证文字可读
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        cv.drawBitmap(src, m, p);

        rendered = out;
        renderKey = key;
        return out;
    }
}
