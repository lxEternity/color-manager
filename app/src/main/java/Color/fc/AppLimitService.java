package Color.fc;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * 单应用负载限制执行服务：独立于监视器运行，确保负载/占用限制始终生效。
 * 生命周期：配置任一限制即启动；配置清空自动退出（残留自动恢复）。
 */
public class AppLimitService extends Service {

    public static boolean running = false;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final Runnable loop = new Runnable() {
        @Override
        public void run() {
            new Thread(() -> {
                try {
                    AppFreqLimiter.tick(AppLimitService.this);
                } catch (Exception ignored) {
                }
                if (AppFreqLimiter.idle()) stopSelf();   // 配置清空：恢复现场并退出
                else ui.postDelayed(this, 1000);
            }).start();
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        startForeground(2, notif());
        ui.postDelayed(loop, 500);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        ui.removeCallbacksAndMessages(null);
        AppFreqLimiter.restoreAll();   // 退出前恢复频率与占用
        super.onDestroy();
    }

    /** 配置了任一限制且服务未运行 → 启动 */
    static void ensure(Context ctx) {
        if (running) return;
        new Thread(() -> {
            if (!AppFreqLimiter.anyLimitConfigured()) return;
            try {
                ctx.startForegroundService(new Intent(ctx, AppLimitService.class));
            } catch (Exception ignored) {
            }
        }).start();
    }

    private Notification notif() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel("applimit",
                "单应用负载限制", NotificationManager.IMPORTANCE_MIN);
        nm.createNotificationChannel(ch);
        return new Notification.Builder(this, "applimit")
                .setContentTitle("单应用负载限制运行中")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .build();
    }
}
