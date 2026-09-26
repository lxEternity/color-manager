package Color.fc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 开机自启单应用负载限制服务。
 * 限制配置持久存在 /sdcard/Android/qingtd/单应用负载.conf，但执行服务(AppLimitService)
 * 不跨重启存活——重启后无人拉起 = 单应用限频全部失效，直到用户手动打开 APP
 * （表现为"限频时灵时不灵"）。开机广播按配置需要重新拉起服务。
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            // 防御：Android 15+（targetSdk 35+）部分场景会拒绝从开机广播启动
            // 前台服务（ForegroundServiceStartNotAllowedException）——
            // 拉起失败只是限频延后（用户打开 APP 时会重新 ensure），绝不能让进程崩溃
            try {
                AppLimitService.ensure(ctx);
            } catch (Throwable ignored) {
            }
        }
    }
}
