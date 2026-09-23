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

    /** 控件玻璃透明度 30-100（%），沉浸背景生效时应用到全部卡片/控件 */
    public static int glassAlpha(Context c) {
        int v = c.getSharedPreferences(SP, Context.MODE_PRIVATE).getInt("glass", 75);
        return Math.max(30, Math.min(100, v));
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

    public static void setGlass(Context c, int v) {
        c.getSharedPreferences(SP, Context.MODE_PRIVATE).edit()
                .putInt("glass", Math.max(30, Math.min(100, v))).commit();
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
        cachedTopBar = cachedBotBar = 0;
    }

    private static Bitmap rendered;
    private static String renderKey;
    /** 背景图上下边缘采样色，用于系统栏无缝衔接 */
    private static int cachedTopBar, cachedBotBar;

    /** 应用窗口背景 + 状态栏/导航栏，实现全局沉浸。每个页面 onResume 时调用 */
    public static void applyBackground(Activity a) {
        Window w = a.getWindow();
        boolean dark = dark(a);
        boolean img = imageBg(a) && bgFile(a).exists();
        // 两种背景模式互斥：自定义图优先，同时开启时忽略透桌面
        boolean transp = transparentBg(a) && !img;
        boolean immersive = img || transp;
        // API 30+：沉浸时窗口铺满整块物理屏幕，背景图与屏幕像素级对齐，
        // 系统栏区域由背景像素接管，不残留任何没盖到的缝隙
        boolean edge = android.os.Build.VERSION.SDK_INT >= 30;
        if (edge) setEdgeToEdge(w, immersive);

        if (img) {
            // 背景图渲染时已按透明度叠加在 App 底色上，窗口保持全实心
            BitmapDrawable d = new BitmapDrawable(a.getResources(), renderBg(a, dark));
            w.setBackgroundDrawable(d);
        } else if (transp) {
            // 全局透明：直接透出后面的桌面（不加壁纸标志，避免 ROM 壁纸层排序异常）
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        } else {
            int solid = dark ? 0xFF0D1220 : 0xFFF5F7FC;
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(solid));
        }
        // 统一不使用壁纸层
        w.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);

        if (immersive && edge) {
            // 窗口已铺满全屏：系统栏透明，背景像素直接延伸到屏幕边缘
            w.setStatusBarColor(Color.TRANSPARENT);
            w.setNavigationBarColor(Color.TRANSPARENT);
            if (img) {
                // 背景图：系统栏图标颜色按图片上缘亮度取深浅
                setLightStatusIcons(w, cachedTopBar != 0 ? isLightColor(cachedTopBar) : !dark);
            } else {
                setLightStatusIcons(w, !dark);
            }
        } else if (img) {
            // 旧系统（无 edge-to-edge）：系统栏用图片边缘采样色衔接
            int fb = dark ? 0xFF0D1220 : 0xFFF5F7FC;
            w.setStatusBarColor(cachedTopBar != 0 ? cachedTopBar : fb);
            w.setNavigationBarColor(cachedBotBar != 0 ? cachedBotBar : fb);
            setLightStatusIcons(w, cachedTopBar != 0 ? isLightColor(cachedTopBar) : !dark);
        } else {
            int bar = dark ? 0xFF0D1220 : 0xFFF5F7FC;
            w.setStatusBarColor(bar);
            w.setNavigationBarColor(bar);
            setLightStatusIcons(w, !dark);
        }
        // 沉浸模式：全局控件玻璃化——卡片/输入框等圆角背景半透明透出背景
        walkGlass(w.getDecorView(), immersive ? Math.round(glassAlpha(a) * 2.55f) : 255);
    }

    /** 亮度判断：系统栏取浅色还是深色图标 */
    private static boolean isLightColor(int color) {
        int r = (color >> 16) & 0xFF, g = (color >> 8) & 0xFF, b = color & 0xFF;
        return (0.299 * r + 0.587 * g + 0.114 * b) > 128;
    }

    /** 记录内容区原始 padding 的 tag key */
    private static final int TAG_BASE_PADDING = 0x51EED0F5;

    /**
     * API 30+ edge-to-edge：窗口铺满物理屏幕，内容区补上系统栏/输入法 insets，
     * 关闭时恢复系统默认 insets 处理
     */
    private static void setEdgeToEdge(Window w, boolean on) {
        View content = w.getDecorView().findViewById(android.R.id.content);
        if (content == null) return;
        if (on) {
            w.setDecorFitsSystemWindows(false);
            if (content.getTag(TAG_BASE_PADDING) == null) {
                content.setTag(TAG_BASE_PADDING, new int[]{
                        content.getPaddingLeft(), content.getPaddingTop(),
                        content.getPaddingRight(), content.getPaddingBottom()});
            }
            content.setOnApplyWindowInsetsListener((v, ins) -> {
                int[] base = (int[]) v.getTag(TAG_BASE_PADDING);
                android.graphics.Insets sys = ins.getInsets(android.view.WindowInsets.Type.systemBars());
                android.graphics.Insets ime = ins.getInsets(android.view.WindowInsets.Type.ime());
                // 内容避开状态栏/导航栏/输入法
                v.setPadding(base[0] + sys.left, base[1] + sys.top, base[2] + sys.right,
                        base[3] + Math.max(sys.bottom, ime.bottom));
                return ins;
            });
        } else {
            w.setDecorFitsSystemWindows(true);
            content.setOnApplyWindowInsetsListener(null);
            int[] base = (int[]) content.getTag(TAG_BASE_PADDING);
            if (base != null) {
                content.setPadding(base[0], base[1], base[2], base[3]);
            }
        }
    }

    /** 递归遍历控件树，把 GradientDrawable（shape 背景）调成玻璃透明度 */
    private static void walkGlass(View v, int alpha) {
        android.graphics.drawable.Drawable bg = v.getBackground();
        if (bg instanceof android.graphics.drawable.GradientDrawable) {
            bg.setAlpha(alpha);
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) walkGlass(g.getChildAt(i), alpha);
        }
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
                + "|" + bgScale(c) + "|" + bgOffX(c) + "|" + bgOffY(c) + "|" + bgAlpha(c)
                + "|" + dark;
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
        // 先铺 App 底色：图片调低透明度时是与底色混合，
        // 而不是把窗口变透明（半透明窗口会露出后面的桌面）
        cv.drawColor(dark ? 0xFF0D1220 : 0xFFF5F7FC);
        // 图片透明度画进位图里，窗口保持全实心
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        p.setAlpha(Math.round(bgAlpha(c) * 2.55f));
        cv.drawBitmap(src, m, p);
        if (dark) cv.drawColor(0x59000000);   // 夜间模式叠加暗色蒙层保证文字可读
        src.recycle();

        // 采样图片上下边缘色，系统栏用同色衔接，视觉上背景延伸进系统栏
        try {
            cachedTopBar = out.getPixel(sw / 2, Math.max(0, (int) (sh * 0.02f)));
            cachedBotBar = out.getPixel(sw / 2, Math.min(sh - 1, (int) (sh * 0.98f)));
        } catch (Exception e) {
            cachedTopBar = cachedBotBar = 0;
        }

        rendered = out;
        renderKey = key;
        return out;
    }
}
