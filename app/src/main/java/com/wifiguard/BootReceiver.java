package com.wifiguard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 开机自启 / 覆盖安装后自启。 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null) return;
        String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        if (!Prefs.enabled(context)) return;
        try {
            MonitorService.start(context);
            Logx.log("开机自启：监控已恢复");
        } catch (Throwable t) {
            Logx.log("开机自启失败: " + t);
        }
    }
}
