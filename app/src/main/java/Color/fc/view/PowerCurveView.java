package Color.fc.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

/**
 * 实时功耗曲线（视觉照搬 opbatt 电池工具包 trend 页）：
 * 双 Y 轴（左功率 W / 右温度 ℃），放电段绿线 #67d98a、充电段橙线 #ffb02e、
 * 温度红线 #ff8086，末点胶囊数值标签，30 点滚动。
 */
public class PowerCurveView extends View {

    private static final int MAX_PTS = 30;
    public static final int C_DISCHARGE = 0xFF67D98A;
    public static final int C_CHARGE = 0xFFFFB02E;
    public static final int C_TEMP = 0xFFFF8086;
    private static final int C_GRID = 0x0E2D8CFF;
    private static final int C_TEXT = 0xFF7F8896;
    private static final int C_TOOLTIP = 0xF0141F38;

    private static final class Pt {
        final float w, temp;
        final boolean chg;
        Pt(float w, float temp, boolean chg) { this.w = w; this.temp = temp; this.chg = chg; }
    }

    private final Deque<Pt> pts = new ArrayDeque<>();
    private float yWMax = 10f, yTMin = 20f, yTMax = 40f;

    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tip = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tipText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF pillRect = new RectF();
    private final RectF tipRect = new RectF();

    public PowerCurveView(Context c, AttributeSet a) {
        super(c, a);
        line.setStrokeWidth(dp(2));
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        grid.setColor(C_GRID);
        grid.setStrokeWidth(dp(0.7f));
        grid.setStyle(Paint.Style.STROKE);
        label.setColor(C_TEXT);
        label.setTextSize(sp(9));
        pill.setTextSize(sp(9));
        pill.setFakeBoldText(true);
        tip.setColor(C_TOOLTIP);
        tip.setTextSize(sp(10));
        tip.setFakeBoldText(true);
        tipText.setColor(Color.WHITE);
        tipText.setTextSize(sp(10));
        tipText.setFakeBoldText(true);
    }

    /** 喂入一次采样：功率 W、温度 ℃、是否充电 */
    public void push(float w, float temp, boolean chg) {
        pts.addLast(new Pt(w, temp, chg));
        while (pts.size() > MAX_PTS) pts.removeFirst();
        rescale();
        invalidate();
    }

    public boolean hasData() { return !pts.isEmpty(); }

    private void rescale() {
        float mx = 0.1f, tmn = 45f, tmx = 20f;
        boolean hasTemp = false;
        for (Pt p : pts) {
            if (p.w > mx) mx = p.w;
            if (p.temp > 0) {
                hasTemp = true;
                if (p.temp < tmn) tmn = p.temp;
                if (p.temp > tmx) tmx = p.temp;
            }
        }
        yWMax = Math.max(10f, (float) Math.ceil(mx * 1.15));
        if (hasTemp) {
            yTMin = Math.max(15f, (float) Math.floor(tmn - 2));
            yTMax = Math.max(40f, (float) Math.ceil(tmx + 2));
            if (yTMax - yTMin < 1) yTMax = yTMin + 1;
        }
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }
    private float sp(float v) { return v * getResources().getDisplayMetrics().scaledDensity; }

    @Override
    protected void onDraw(Canvas cv) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float padT = dp(6), padR = dp(28), padB = dp(4), padL = dp(4);
        float cw = w - padL - padR, ch = h - padT - padB;

        // 网格与轴刻度
        for (int i = 0; i <= 3; i++) {
            float y = padT + ch * i / 3f;
            cv.drawLine(padL, y, padL + cw, y, grid);
            label.setTextAlign(Paint.Align.LEFT);
            cv.drawText(String.valueOf((int) (yWMax * (3 - i) / 3f)), padL + dp(2), y - dp(2), label);
            label.setTextAlign(Paint.Align.RIGHT);
            cv.drawText(String.valueOf((int) (yTMin + (yTMax - yTMin) * (3 - i) / 3f)) + "°", w - dp(2), y - dp(2), label);
        }
        if (pts.isEmpty()) {
            tip.getFontMetrics();
            String s = "等待采样…";
            float tw = tip.measureText(s);
            cv.drawText(s, (w - tw) / 2f, h / 2f, tipText);
            return;
        }

        int n = pts.size();
        Pt[] arr = pts.toArray(new Pt[0]);
        float step = cw / Math.max(n - 1, 1);
        float x0 = padL;

        // 温度线
        line.setColor(C_TEMP);
        drawSeries(cv, arr, x0, step, padT, ch, true);

        // 功率线：按充放电分色分段
        for (int i = 0; i < n - 1; i++) {
            line.setColor(arr[i + 1].chg ? C_CHARGE : C_DISCHARGE);
            float x1 = x0 + step * i, x2 = x0 + step * (i + 1);
            float y1 = padT + ch * (1f - clamp(arr[i].w / yWMax)), y2 = padT + ch * (1f - clamp(arr[i + 1].w / yWMax));
            cv.drawLine(x1, y1, x2, y2, line);
        }

        // 末点胶囊标签
        Pt last = arr[n - 1];
        float lx = x0 + step * (n - 1), ly = padT + ch * (1f - clamp(last.w / yWMax));
        String s = String.format(Locale.US, "%.1f W", last.w);
        pill.setColor(last.chg ? C_CHARGE : C_DISCHARGE);
        drawPill(cv, s, lx - dp(2), ly - dp(14), pill, pillRect, true);
        String s2 = String.format(Locale.US, "%.1f°", last.temp);
        pill.setColor(C_TEMP);
        float tly = padT + ch * (1f - clamp((last.temp - yTMin) / (yTMax - yTMin)));
        drawPill(cv, s2, lx - dp(2), tly + dp(6), pill, pillRect, false);
    }

    private void drawSeries(Canvas cv, Pt[] arr, float x0, float step, float padT, float ch, boolean temp) {
        for (int i = 0; i < arr.length - 1; i++) {
            float y1, y2;
            if (temp) {
                y1 = padT + ch * (1f - clamp((arr[i].temp - yTMin) / (yTMax - yTMin)));
                y2 = padT + ch * (1f - clamp((arr[i + 1].temp - yTMin) / (yTMax - yTMin)));
            } else {
                y1 = padT + ch * (1f - clamp(arr[i].w / yWMax));
                y2 = padT + ch * (1f - clamp(arr[i + 1].w / yWMax));
            }
            cv.drawLine(x0 + step * i, y1, x0 + step * (i + 1), y2, line);
        }
    }

    private void drawPill(Canvas cv, String s, float cx, float cy, Paint p, RectF r, boolean above) {
        float tw = p.measureText(s);
        float ph = dp(15), pw = tw + dp(10);
        cx = Math.min(Math.max(cx, pw / 2f), getWidth() - pw / 2f);
        if (above) {
            r.set(cx - pw / 2f, cy - ph, cx + pw / 2f, cy);
        } else {
            r.set(cx - pw / 2f, cy, cx + pw / 2f, cy + ph);
        }
        cv.drawRoundRect(r, ph / 2f, ph / 2f, p);
        p.setColor(Color.WHITE);
        p.setTextAlign(Paint.Align.CENTER);
        cv.drawText(s, cx, r.centerY() - (p.ascent() + p.descent()) / 2f, p);
    }

    private static float clamp(float v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }
}
