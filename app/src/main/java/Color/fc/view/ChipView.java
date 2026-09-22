package Color.fc.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * 主页 SOC 芯片可视化
 */
public class ChipView extends View {

    private String mainText = "SOC";
    private String subText = "--";

    private final Paint chipPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pinPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint innerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint subPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public ChipView(Context context, AttributeSet attrs) {
        super(context, attrs);
        pinPaint.setColor(0xFF7E93AA);
        innerPaint.setColor(0x33FFFFFF);
        textPaint.setColor(Color.WHITE);
        subPaint.setColor(0xCCFFFFFF);
    }

    public void setChip(String main, String sub) {
        mainText = main == null ? "" : main;
        subText = sub == null ? "" : sub;
        invalidate();
    }

    @Override
    protected void onMeasure(int wms, int hms) {
        int size = dp(96);
        setMeasuredDimension(size, size);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        float pinLen = dp(7);
        float pad = pinLen + dp(4);

        LinearGradient g = new LinearGradient(0, 0, w, h,
                0xFF22D3EE, 0xFF6366F1, Shader.TileMode.CLAMP);
        chipPaint.setShader(g);
        RectF body = new RectF(pad, pad, w - pad, h - pad);
        canvas.drawRoundRect(body, dp(12), dp(12), chipPaint);

        // 引脚：上下各4根，左右各3根
        pinPaint.setColor(0xFF8FA6BD);
        for (int i = 0; i < 4; i++) {
            float x = pad + (w - 2 * pad) * (i + 0.5f) / 4;
            canvas.drawRect(x - dp(1.5f), 0, x + dp(1.5f), pinLen, pinPaint);
            canvas.drawRect(x - dp(1.5f), h - pinLen, x + dp(1.5f), h, pinPaint);
        }
        for (int i = 0; i < 3; i++) {
            float y = pad + (h - 2 * pad) * (i + 0.5f) / 3;
            canvas.drawRect(0, y - dp(1.5f), pinLen, y + dp(1.5f), pinPaint);
            canvas.drawRect(w - pinLen, y - dp(1.5f), w, y + dp(1.5f), pinPaint);
        }

        // 内描边
        innerPaint.setStyle(Paint.Style.STROKE);
        innerPaint.setStrokeWidth(dp(1));
        RectF inner = new RectF(pad + dp(5), pad + dp(5), w - pad - dp(5), h - pad - dp(5));
        canvas.drawRoundRect(inner, dp(8), dp(8), innerPaint);

        // 文字
        textPaint.setTextSize(dp(13));
        textPaint.setFakeBoldText(true);
        float tw = textPaint.measureText(mainText);
        canvas.drawText(mainText, (w - tw) / 2, h / 2f - dp(1), textPaint);

        subPaint.setTextSize(dp(9));
        float sw = subPaint.measureText(subText);
        canvas.drawText(subText, (w - sw) / 2, h / 2f + dp(13), subPaint);
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
