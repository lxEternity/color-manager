package Color.fc.view;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;

/**
 * 全屏扫荡光束转场：点击入口控件后一道光束横扫全屏，随后进入下一页面
 */
public class Beam {

    /** 点击控件出发光束转场进入下一页面 */
    public static void go(Activity act, Intent intent) {
        FrameLayout decor = (FrameLayout) act.getWindow().getDecorView();
        int w = decor.getWidth(), h = decor.getHeight();
        if (w == 0 || h == 0) {
            act.startActivity(intent);
            return;
        }
        final BeamView beam = new BeamView(act);
        decor.addView(beam, new FrameLayout.LayoutParams(-1, -1));
        beam.setClickable(true);   // 挡住转场期间的重复点击

        ValueAnimator an = ValueAnimator.ofFloat(0f, 1f);
        an.setDuration(430);
        an.setInterpolator(new DecelerateInterpolator());
        an.addUpdateListener(a -> {
            beam.prog = (float) a.getAnimatedValue();
            beam.invalidate();
        });
        an.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator a) {
                decor.removeView(beam);
            }
        });
        an.start();
        // 光束扫过一半时启动下一页面，转场衔接更连贯
        new Handler(Looper.getMainLooper()).postDelayed(() -> act.startActivity(intent), 160);
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

    /** 光束本体：青晕 + 白核的横向扫描光带 */
    private static class BeamView extends View {
        float prog;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        BeamView(android.content.Context c) {
            super(c);
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            float bw = w * 0.55f;
            float x = -bw + prog * (w + 2 * bw);
            // 入场 18% 淡入，收尾 28% 淡出
            float a = Math.min(1f, Math.min(prog / 0.18f, (1f - prog) / 0.28f));
            if (a <= 0) return;
            int glow = (Math.round(0x77 * a) << 24) | 0x00E5FF;   // 青色光晕
            int soft = (Math.round(0x22 * a) << 24) | 0x00E5FF;   // 外缘淡晕
            int core = (Math.round(0xE8 * a) << 24) | 0xFFFFFF;   // 白色光核
            p.setShader(new LinearGradient(x, 0, x + bw, 0,
                    new int[]{0x00000000, soft, glow, core, glow, soft, 0x00000000},
                    new float[]{0f, 0.12f, 0.3f, 0.5f, 0.7f, 0.88f, 1f},
                    Shader.TileMode.CLAMP));
            c.drawRect(x, 0, x + bw, h, p);
        }
    }
}
