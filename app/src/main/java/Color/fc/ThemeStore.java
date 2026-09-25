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
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import java.io.File;

import Color.fc.view.LiquidDrawable;

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

    /** 液态玻璃：沉浸模式下为全部圆角控件叠加顶部高光 + 白描边的液态质感 */
    public static boolean liquidGlass(Context c) {
        return c.getSharedPreferences(SP, Context.MODE_PRIVATE).getBoolean("liquid", false);
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

    public static void setLiquid(Context c, boolean on) {
        c.getSharedPreferences(SP, Context.MODE_PRIVATE).edit()
                .putBoolean("liquid", on).commit();
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

    // ==================== 弹窗统一主题（圆角卡片，跟随日/夜与沉浸背景明暗） ====================

    /** 弹窗配色基底：沉浸模式按背景实际明暗（浅色主题+深色壁纸 → 深色弹窗），否则跟随日夜开关 */
    public static boolean dialogDarkBase(Context c) {
        boolean img = imageBg(c) && bgFile(c).exists();
        boolean transp = transparentBg(c) && !img;
        return (img || transp) ? immersiveDarkBase(c) : dark(c);
    }

    /** 弹窗 Context：Material 深/浅 Dialog 主题，标题/正文/列表/按钮文字配色整体跟随 */
    public static Context dialogCtx(Activity a) {
        return new android.view.ContextThemeWrapper(a, dialogDarkBase(a)
                ? android.R.style.Theme_Material_Dialog_Alert
                : android.R.style.Theme_Material_Light_Dialog_Alert);
    }

    /** 弹窗统一圆角卡片样式（在 show() 之后调用）：
     *  24dp 大圆角 + 卡片底色（沉浸时玻璃半透明）+ 轻描边 + 强调色按钮 + 宽度优化 */
    public static void styleDialog(Context c, android.app.AlertDialog d) {
        android.view.Window w = d.getWindow();
        if (w == null) return;
        boolean darkBase = dialogDarkBase(c);
        boolean immersive = (imageBg(c) && bgFile(c).exists()) || transparentBg(c);
        float dp = c.getResources().getDisplayMetrics().density;

        GradientDrawable g = new GradientDrawable();
        int base = darkBase ? 0xFF161D2F : 0xFFFFFFFF;   // bgCard 深浅变体
        int a = immersive ? Math.max(200, Math.round(glassAlpha(c) * 2.2f)) : 255;
        g.setColor((base & 0x00FFFFFF) | (a << 24));
        g.setCornerRadius(24 * dp);
        g.setStroke(Math.round(dp), darkBase ? 0xFF2A3550 : 0xFFDCE6F2);
        w.setBackgroundDrawable(g);
        try {
            w.setDimAmount(immersive ? 0.25f : 0.5f);
        } catch (Exception ignored) {
        }

        // 按钮统一强调色（按钮在 show() 后才存在）
        int accent = c.getResources().getColor(R.color.accent);
        android.widget.Button[] bs = {
                d.getButton(android.app.AlertDialog.BUTTON_POSITIVE),
                d.getButton(android.app.AlertDialog.BUTTON_NEGATIVE),
                d.getButton(android.app.AlertDialog.BUTTON_NEUTRAL)};
        for (android.widget.Button b : bs) if (b != null) b.setTextColor(accent);

        // 尺寸优化：宽度 = min(屏宽 - 32dp, 400dp)，高度自适应；避免默认过宽/贴边
        try {
            Point size = new Point();
            ((WindowManager) c.getSystemService(Context.WINDOW_SERVICE))
                    .getDefaultDisplay().getRealSize(size);
            int want = Math.min(size.x - Math.round(32 * dp), Math.round(400 * dp));
            if (want > 0) w.setLayout(want, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        } catch (Exception ignored) {
        }
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
        // 沉浸时窗口铺满整块物理屏幕，背景像素接管系统栏区域
        setEdgeToEdge(w, immersive);

        if (img) {
            // 背景图渲染时已按透明度叠加在 App 底色上，窗口保持全实心
            BitmapDrawable d = new BitmapDrawable(a.getResources(), renderBg(a, dark));
            w.setBackgroundDrawable(d);
        } else if (transp) {
            // 全局透明：直接透出后面的桌面
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        } else {
            int solid = dark ? 0xFF0D1220 : 0xFFF5F7FC;
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(solid));
        }
        // 统一不使用壁纸层
        w.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);

        if (immersive) {
            // 系统栏全透明：窗口已铺满全屏，状态栏/导航栏直接透出背景像素
            // （对比度遮罩在 styles.xml 里用 enforceXxxContrast=false 关闭）
            w.setStatusBarColor(Color.TRANSPARENT);
            w.setNavigationBarColor(Color.TRANSPARENT);
            // 背景图：系统栏图标颜色按图片上缘亮度取深浅
            setLightStatusIcons(w, img && cachedTopBar != 0 ? isLightColor(cachedTopBar) : !dark);
        } else {
            int bar = dark ? 0xFF0D1220 : 0xFFF5F7FC;
            w.setStatusBarColor(bar);
            w.setNavigationBarColor(bar);
            setLightStatusIcons(w, !dark);
        }
        // 沉浸模式：全局控件玻璃化——卡片/输入框等圆角背景半透明透出背景
        walkGlass(w.getDecorView(), immersive ? Math.round(glassAlpha(a) * 2.55f) : 255);
        // 液态玻璃：沉浸时叠加顶部高光 + 白描边的液态质感
        walkLiquid(w.getDecorView(), immersive && liquidGlass(a),
                a.getResources().getDisplayMetrics().density);
    }

    /** 亮度判断：系统栏取浅色还是深色图标 */
    public static boolean isLightColor(int color) {
        int r = (color >> 16) & 0xFF, g = (color >> 8) & 0xFF, b = color & 0xFF;
        return (0.299 * r + 0.587 * g + 0.114 * b) > 128;
    }

    /** 沉浸背景下应采用深色系还是浅色系弹层配色：
     *  背景图模式按渲染结果底部采样色判断；透壁纸模式采样系统壁纸底部区域；
     *  均不可用时回退日/夜间开关。弹窗/浮层沉浸配色用它（而非 dark 开关，
     *  因为浅色主题也可能配深色壁纸/深色背景图，此时浅色弹层会非常突兀） */
    public static boolean immersiveDarkBase(Context c) {
        boolean img = imageBg(c) && bgFile(c).exists();
        if (img) {
            if (cachedBotBar != 0) return !isLightColor(cachedBotBar);
            return dark(c);
        }
        try {
            android.app.WallpaperManager wm =
                    (android.app.WallpaperManager) c.getSystemService(Context.WALLPAPER_SERVICE);
            if (wm != null) {
                android.graphics.drawable.Drawable d = wm.getDrawable();
                if (d instanceof BitmapDrawable) {
                    Bitmap bm = ((BitmapDrawable) d).getBitmap();
                    if (bm != null && bm.getWidth() > 0 && bm.getHeight() > 0) {
                        long sum = 0;
                        int n = 0;
                        int stepX = Math.max(1, bm.getWidth() / 24);
                        int stepY = Math.max(1, bm.getHeight() / 24);
                        for (int y = (int) (bm.getHeight() * 0.7f); y < bm.getHeight(); y += stepY) {
                            for (int x = 0; x < bm.getWidth(); x += stepX) {
                                int p = bm.getPixel(x, y);
                                sum += Math.round(0.299f * ((p >> 16) & 0xFF)
                                        + 0.587f * ((p >> 8) & 0xFF) + 0.114f * (p & 0xFF));
                                n++;
                            }
                        }
                        if (n > 0) return (sum / n) <= 128;   // 底部偏暗 → 深色系
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return dark(c);
    }

    /** 记录内容区原始 padding 的 tag key */
    private static final int TAG_BASE_PADDING = 0x51EED0F5;

    /**
     * 窗口铺满物理屏幕（含系统栏区域），内容区补系统栏/输入法 insets；
     * 关闭时恢复系统默认 insets 处理。兼容 API 26+ 全部系统
     */
    private static void setEdgeToEdge(Window w, boolean on) {
        View decor = w.getDecorView();
        View content = decor.findViewById(android.R.id.content);
        if (content == null) return;
        int api = android.os.Build.VERSION.SDK_INT;

        if (on) {
            // 关键：声明由窗口自己绘制系统栏背景，setStatusBarColor(透明)才会生效，
            // 否则系统栏始终画主题默认色，看起来"不沉浸"
            w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            if (api >= 30) {
                w.setDecorFitsSystemWindows(false);
            } else {
                // Android 8-10：用布局标志把窗口铺到状态栏/导航栏后面
                int vis = decor.getSystemUiVisibility()
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
                decor.setSystemUiVisibility(vis);
            }
            if (content.getTag(TAG_BASE_PADDING) == null) {
                content.setTag(TAG_BASE_PADDING, new int[]{
                        content.getPaddingLeft(), content.getPaddingTop(),
                        content.getPaddingRight(), content.getPaddingBottom()});
            }
            content.setOnApplyWindowInsetsListener((v, ins) -> {
                int[] base = (int[]) v.getTag(TAG_BASE_PADDING);
                int l, t, r, b;
                if (api >= 30) {
                    android.graphics.Insets sys = ins.getInsets(android.view.WindowInsets.Type.systemBars());
                    android.graphics.Insets ime = ins.getInsets(android.view.WindowInsets.Type.ime());
                    l = sys.left;
                    t = sys.top;
                    r = sys.right;
                    b = Math.max(sys.bottom, ime.bottom);
                } else {
                    // 旧系统：系统栏+键盘都包含在 SystemWindowInsets（adjustResize）
                    l = ins.getSystemWindowInsetLeft();
                    t = ins.getSystemWindowInsetTop();
                    r = ins.getSystemWindowInsetRight();
                    b = ins.getSystemWindowInsetBottom();
                }
                // 内容避开状态栏/导航栏/输入法
                v.setPadding(base[0] + l, base[1] + t, base[2] + r, base[3] + b);
                return ins;
            });
        } else {
            if (api >= 30) {
                w.setDecorFitsSystemWindows(true);
            } else {
                int vis = decor.getSystemUiVisibility()
                        & ~(View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
                decor.setSystemUiVisibility(vis);
            }
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
        } else if (bg instanceof android.graphics.drawable.LayerDrawable) {
            // layer-list（如渐变保存按钮/胶囊）整体半透明，修复保存按钮不同步玻璃化
            bg.mutate().setAlpha(alpha);
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) walkGlass(g.getChildAt(i), alpha);
        }
    }

    /** 液态玻璃：为圆角背景控件叠加镜面高光覆盖层（foreground），关闭时移除 */
    private static void walkLiquid(View v, boolean on, float dp) {
        android.graphics.drawable.Drawable bg = v.getBackground();
        if (bg instanceof android.graphics.drawable.GradientDrawable
                || bg instanceof android.graphics.drawable.LayerDrawable) {
            if (on) {
                float r = 18f * dp;
                if (bg instanceof android.graphics.drawable.GradientDrawable) {
                    try {
                        float cr = ((android.graphics.drawable.GradientDrawable) bg).getCornerRadius();
                        if (cr > 0) r = cr;
                    } catch (Exception ignored) {
                        // 各角半径不同的 shape，回退默认
                    }
                }
                v.setForeground(new LiquidDrawable(r, dp));
            } else if (v.getForeground() instanceof LiquidDrawable) {
                v.setForeground(null);
            }
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) walkLiquid(g.getChildAt(i), on, dp);
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
            if (lightBg) {
                vis |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                        | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            } else {
                vis &= ~(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                        | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
            }
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
