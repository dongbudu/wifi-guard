package com.wifiguard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 看门狗：闹钟每 60 秒叫它一次。
 *
 * 主服务每轮检测（2 秒）都会刷新心跳文件，所以：
 *   心跳正常  -> 什么都不做，只把下一次闹钟压上；
 *   心跳没了  -> 说明主服务/主进程挂了，重新把它拉起来。
 *
 * 这个广播由系统闹钟触发，进程没在跑也会被系统拉起来执行，
 * 所以是真正独立于应用自身状态的兜底。
 */
public class WatchdogReceiver extends BroadcastReceiver {

    public static final String ACTION_TICK = "com.wifiguard.action.WATCHDOG";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null) return;
        final Context app = context.getApplicationContext();

        if (!Prefs.enabled(app)) {
            AlarmScheduler.cancel(app);
            return;
        }

        final PendingResult pr = goAsync();
        new Thread(() -> {
            try {
                long age = Heartbeat.ageMs(app);
                if (age == 0L) {
                    String msg = "看门狗：还没有心跳，拉起主服务";
                    Logx.log(msg);
                    Heartbeat.crash(app, msg);
                    Prefs.countWatchdogRestart(app);
                    MonitorService.start(app);
                } else if (age > Heartbeat.STALE_MS) {
                    String msg = "看门狗：主服务心跳停了 " + (age / 1000) + " 秒，正在重新拉起";
                    Logx.log(msg);
                    Heartbeat.crash(app, msg);
                    Prefs.countWatchdogRestart(app);
                    MonitorService.start(app);
                }
            } catch (Throwable t) {
                Heartbeat.crash(app, "看门狗执行异常: " + t);
            } finally {
                try {
                    AlarmScheduler.schedule(app);
                } catch (Throwable ignored) {
                }
                pr.finish();
            }
        }, "wig-watchdog").start();
    }
}
