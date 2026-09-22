package Color.fc.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;

/**
 * 实时功耗迷你曲线图
 */
public class SparkView extends View {

    private final ArrayList<Double> data = new ArrayList<>();
    private double peak = 0.001;
    private double lastW = 0;

    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public SparkView(Context context, AttributeSet attrs) {
        super(context, attrs);
        linePaint.setColor(0xFF0096C8);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(dp(2));
        gridPaint.setColor(0xFFE9EEF6);
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(1);
        dotPaint.setColor(0xFF0096C8);
    }

    public void push(double watts) {
        lastW = watts;
        data.add(watts);
        if (data.size() > 90) data.remove(0);
        if (watts > peak) peak = watts;
        if (System.currentTimeMillis() % 8 == 0) refreshPeak();
        invalidate();
    }

    public double getLast() {
        return lastW;
    }

    public double getPeak() {
        return peak;
    }

    private void refreshPeak() {
        double m = 0.001;
        for (double v : data) if (v > m) m = v;
        peak = m;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();

        // 网格
        for (int i = 1; i <= 3; i++) {
            float y = h * i / 4f;
            canvas.drawLine(0, y, w, y, gridPaint);
        }

        int n = data.size();
        if (n < 2) return;

        float stepX = w / (float) (90 - 1);
        float startX = w - (n - 1) * stepX;

        Path line = new Path();
        Path fill = new Path();
        for (int i = 0; i < n; i++) {
            double v = data.get(i);
            float x = startX + i * stepX;
            float y = h - (float) (v / peak) * (h - dp(6)) - dp(3);
            if (i == 0) {
                line.moveTo(x, y);
                fill.moveTo(x, h);
                fill.lineTo(x, y);
            } else {
                line.lineTo(x, y);
                fill.lineTo(x, y);
            }
        }
        fill.lineTo(startX + (n - 1) * stepX, h);
        fill.lineTo(startX, h);
        fill.close();

        fillPaint.setShader(new LinearGradient(0, 0, 0, h,
                0x4022D3EE, 0x0022D3EE, Shader.TileMode.CLAMP));
        fillPaint.setStyle(Paint.Style.FILL);
        canvas.drawPath(fill, fillPaint);
        canvas.drawPath(line, linePaint);

        // 末端点
        float lastX = startX + (n - 1) * stepX;
        float lastY = h - (float) (data.get(n - 1) / peak) * (h - dp(6)) - dp(3);
        canvas.drawCircle(lastX, lastY, dp(3), dotPaint);
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
