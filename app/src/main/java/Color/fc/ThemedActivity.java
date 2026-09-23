package Color.fc;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;

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
}
