package Color.fc.view;

import android.view.MotionEvent;
import android.view.View;
import android.view.animation.OvershootInterpolator;

/**
 * 控件按压动效：按下缩放 + 半透明，松手弹性回弹（不影响原点击事件）
 * 全屏转场特效已按需求移除，页面切换为直接进入
 */
public class Warp {

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
}
