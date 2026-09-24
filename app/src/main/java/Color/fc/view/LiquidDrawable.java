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
 * 液态玻璃覆盖层：顶部高光渐变 + 白色描边高光，叠在圆角控件上形成
 * 光泽流动的"液态玻璃"质感（作为 foreground 绘制在玻璃化背景之上）
 */
public class LiquidDrawable extends Drawable {

    private final float radius;
    private final float strokeW;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF buf = new RectF();

    public LiquidDrawable(float radiusPx, float density) {
        this.radius = radiusPx;
        this.strokeW = 1.4f * density;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(strokeW);
        stroke.setColor(0x5CFFFFFF);
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        if (b.isEmpty()) return;
        buf.set(b);
        path.reset();
        path.addRoundRect(buf, radius, radius, Path.Direction.CW);
        // 顶部亮、底部微暗的镜面渐变：液态高光
        fill.setShader(new LinearGradient(0, b.top, 0, b.bottom,
                new int[]{0x45FFFFFF, 0x17FFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        c.drawPath(path, fill);
        // 全周白色高光描边
        c.drawPath(path, stroke);
        // 顶部弧线加强：液滴反光
        stroke.setStrokeWidth(strokeW * 1.8f);
        c.drawArc(b.left + strokeW, b.top + strokeW,
                b.right - strokeW, b.top + b.height() * 0.5f, -60f, 120f, false, stroke);
        stroke.setStrokeWidth(strokeW);
    }

    @Override
    public void setAlpha(int alpha) {
        fill.setAlpha(alpha);
        stroke.setAlpha(alpha);
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
