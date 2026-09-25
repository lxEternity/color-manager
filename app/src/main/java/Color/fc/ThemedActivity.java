package Color.fc;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * 主题基类：所有页面继承，统一应用 日/夜间 + 背景沉浸 + 返回时刷新主题
 */
public class ThemedActivity extends Activity {

    /** 本进程已应用的主题版本（日/夜间切换会 +1，检测到不一致则重建页面刷新配色） */
    private static int sAppliedVer = -1;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(ThemeStore.wrap(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeStore.applyTheme(this);
        sAppliedVer = ThemeStore.themeVersion(this);
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (sAppliedVer != ThemeStore.themeVersion(this)) {
            sAppliedVer = ThemeStore.themeVersion(this);
            recreate();   // 日/夜间在别的页面被切换过，重建以刷新全套配色
            return;
        }
        ThemeStore.applyBackground(this);
    }

    /**
     * 底部导航栏：与 WEBUI 一致的样式。
     * 绑定四个入口（主页/调度参数/调速器/应用策略），并高亮当前页面。
     * @param activeId 当前页面对应的导航项容器 ID（R.id.navHome / navSchedule / navGovernor / navMode）
     */
    protected void setupBottomNav(int activeId) {
        int[][] items = {
                {R.id.navHome, R.id.navHomeIcon, R.id.navHomeText},
                {R.id.navSchedule, R.id.navScheduleIcon, R.id.navScheduleText},
                {R.id.navGovernor, R.id.navGovernorIcon, R.id.navGovernorText},
                {R.id.navMode, R.id.navModeIcon, R.id.navModeText}
        };
        Class<?>[] targets = {
                MainActivity.class,
                ScheduleActivity.class,
                GovernorActivity.class,
                ModeActivity.class
        };
        int accent = getResources().getColor(R.color.accent);
        int dim = getResources().getColor(R.color.textDim);
        for (int i = 0; i < items.length; i++) {
            View container = findViewById(items[i][0]);
            if (container == null) continue;
            ImageView icon = findViewById(items[i][1]);
            TextView text = findViewById(items[i][2]);
            boolean active = items[i][0] == activeId;
            if (icon != null) icon.setColorFilter(active ? accent : dim);
            if (text != null) {
                text.setTextColor(active ? accent : dim);
                text.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
            }
            final Class<?> target = targets[i];
            container.setOnClickListener(v -> {
                if (target.isInstance(this)) return;
                Intent it = new Intent(this, target);
                it.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(it);
                overridePendingTransition(0, 0);
            });
        }
    }
}

