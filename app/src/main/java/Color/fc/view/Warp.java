package Color.fc.view;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;

/**
 * 量子涟漪转场：点击后从触点迸发能量环（青→紫渐变主环 + 白色拖尾环 + 径向辉光），
 * 涟漪覆盖约六成屏幕时跃迁进入下一页面，替换原全屏扫荡光束
 */
public class Warp {

    /** 点击控件触发涟漪转场进入下一页面 */
    public static void go(Activity act, Intent intent) {
        go(act, intent, null);
    }

    public static void go(Activity act, Intent intent, View anchor) {
        FrameLayout decor = (FrameLayout) act.getWindow().getDecorView();
        int w = decor.getWidth(), h = decor.getHeight();
        if (w == 0 || h == 0) {
            act.startActivity(intent);
            return;
        }
        int cx = w / 2, cy = h / 2;
        if (anchor != null && anchor.getWidth() > 0) {
            int[] loc = new int[2];
            anchor.getLocationInWindow(loc);
            cx = loc[0] + anchor.getWidth() / 2;
            cy = loc[1] + anchor.getHeight() / 2;
        }
        final WarpView v = new WarpView(act, cx, cy, (float) Math.hypot(w, h));
        decor.addView(v, new FrameLayout.LayoutParams(-1, -1));
        v.setClickable(true);   // 挡住转场期间的重复点击

        ValueAnimator an = ValueAnimator.ofFloat(0f, 1f);
        an.setDuration(360);
        an.setInterpolator(new DecelerateInterpolator(1.8f));
        an.addUpdateListener(a -> {
            v.prog = (float) a.getAnimatedValue();
            v.invalidate();
        });
        an.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator a) {
                decor.removeView(v);
            }
        });
        an.start();
        // 涟漪推进到六成时启动下一页面，衔接连贯
        new Handler(Looper.getMainLooper()).postDelayed(() -> act.startActivity(intent), 200);
    }

    /** 控件按下动态反馈：缩放 + 半透明，松手弹性回弹（不影响原点击事件） */
    public static void press(View v) {
        v.setOnTouchListener((b, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    b.animate().scaleX(0.95f).scaleY(0.95f).alpha(0.85f)
                            .setDuration(90).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    b.animate().scaleX(1f).scaleY(1f).alpha(1f)
                            .setDuration(200)
                            .setInterpolator(new OvershootInterpolator(2.2f)).start();
                    break;
            }
            return false;
        });
    }

    /** 涟漪本体：双能量环 + 径向辉光 + 触点闪光 */
    private static class WarpView extends View {
        float prog;
        private final int cx, cy;
        private final float maxR, dp;

        WarpView(android.content.Context c, int cx, int cy, float maxR) {
            super(c);
            this.cx = cx;
            this.cy = cy;
            this.maxR = maxR;
            this.dp = getResources().getDisplayMetrics().density;
        }

        @Override
        protected void onDraw(Canvas c) {
            // 整体淡出：最后 25% 渐隐
            float fade = prog > 0.75f ? (1f - prog) / 0.25f : 1f;
            if (fade <= 0) return;

            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

            // 径向辉光：触点为中心的能量场
            float r0 = Math.max(1f, prog * maxR * 0.55f);
            p.setStyle(Paint.Style.FILL);
            p.setShader(new RadialGradient(cx, cy, r0,
                    new int[]{(Math.round(0x2E * fade) << 24) | 0x00E5FF, 0x00000000},
                    null, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, r0, p);

            // 主能量环：青→紫渐变描边，粗→细
            float r1 = prog * maxR;
            p.setShader(null);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(2f * dp, (26f - 24f * prog) * dp));
            p.setShader(new LinearGradient(cx - r1, cy - r1, cx + r1, cy + r1,
                    new int[]{(Math.round(0xCC * fade) << 24) | 0x00E5FF,
                            (Math.round(0xCC * fade) << 24) | 0xA02CF0},
                    null, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, r1, p);

            // 拖尾环：白色细环滞后 18%
            float p2 = prog - 0.18f;
            if (p2 > 0) {
                p.setShader(null);
                p.setStrokeWidth(2f * dp);
                p.setColor((Math.round(0xB0 * fade * (1f - p2)) << 24) | 0xFFFFFF);
                c.drawCircle(cx, cy, p2 * maxR, p);
            }

            // 触点闪光：十字能量迸发，前 40% 消失
            if (prog < 0.4f) {
                float f = 1f - prog / 0.4f;
                p.setShader(null);
                p.setStrokeWidth(2.5f * dp);
                p.setColor((Math.round(0xE0 * f) << 24) | 0xFFFFFF);
                float l = (10f + 26f * prog) * dp;
                c.drawLine(cx - l, cy, cx + l, cy, p);
                c.drawLine(cx, cy - l, cx, cy + l, p);
            }
        }
    }
}
