package Color.fc.view;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * 液态玻璃覆盖层 v2：底色镜面渐变 + 斜向流光 + 亮度随边分布的立体描边 +
 * 顶部液滴反光弧 + 底部回光，叠在圆角控件上形成光影流动的玻璃质感
 */
public class LiquidDrawable extends Drawable {

    private final float radius;
    private final float strokeW;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bottomRim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF buf = new RectF();

    public LiquidDrawable(float radiusPx, float density) {
        this.radius = radiusPx;
        this.strokeW = 1.3f * density;
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(strokeW);
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeWidth(strokeW * 2.1f);
        arc.setStrokeCap(Paint.Cap.ROUND);
        bottomRim.setStyle(Paint.Style.STROKE);
        bottomRim.setStrokeWidth(strokeW);
        bottomRim.setColor(0x36FFFFFF);
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        if (b.isEmpty()) return;
        buf.set(b);
        path.reset();
        path.addRoundRect(buf, radius, radius, Path.Direction.CW);

        // 1. 底色镜面：顶部亮、中部渐隐的纵向渐变
        fill.setShader(new LinearGradient(0, b.top, 0, b.bottom,
                new int[]{0x4DFFFFFF, 0x1AE6FFFF, 0x00FFFFFF},
                new float[]{0f, 0.42f, 1f}, Shader.TileMode.CLAMP));
        c.drawPath(path, fill);

        // 2. 斜向流光：左上向右下的对角高光，光泽随角度流动更"液态"
        fill.setShader(new LinearGradient(b.left, b.top, b.right, b.top + b.height() * 0.7f,
                new int[]{0x36FFFFFF, 0x0FFFFFFF, 0x00FFFFFF, 0x14FFFFFF},
                new float[]{0f, 0.32f, 0.62f, 1f}, Shader.TileMode.CLAMP));
        c.drawPath(path, fill);

        // 3. 立体描边：亮度按上下分布（顶边最亮、底边最暗），模拟玻璃厚度折射
        rim.setShader(new LinearGradient(0, b.top, 0, b.bottom,
                new int[]{0x96FFFFFF, 0x4FFFFFFF, 0x26FFFFFF},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        c.drawPath(path, rim);

        // 4. 顶部液滴反光弧：随弧线位置亮度渐变，两端柔和收尾
        arc.setShader(new LinearGradient(b.left, b.top, b.right, b.top,
                new int[]{0x00FFFFFF, 0xC8FFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        c.drawArc(b.left + strokeW * 2, b.top + strokeW,
                b.right - strokeW * 2, b.top + Math.max(strokeW * 2, b.height() * 0.5f),
                -55f, 110f, false, arc);

        // 5. 底部回光：玻璃底边透出的微弱环境光
        c.drawArc(b.left + strokeW, b.bottom - Math.max(strokeW * 3, b.height() * 0.28f),
                b.right - strokeW, b.bottom - strokeW,
                35f, 110f, false, bottomRim);
    }

    @Override
    public void setAlpha(int alpha) {
        fill.setAlpha(alpha);
        rim.setAlpha(alpha);
        arc.setAlpha(alpha);
        bottomRim.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(android.graphics.ColorFilter cf) {
        // 不支持滤镜
    }

    @Override
    public int getOpacity() {
        return android.graphics.PixelFormat.TRANSLUCENT;
    }
}
