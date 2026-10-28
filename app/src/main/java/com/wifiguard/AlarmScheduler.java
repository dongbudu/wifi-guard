package com.wifiguard;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;

/**
 * 后手之一：系统级闹钟。它由系统持有，和应用进程无关，
 * 所以主进程被杀/崩溃后，闹钟照样把看门狗叫起来。
 */
public final class AlarmScheduler {

    /** 60 秒一次；只是读一个文件时间戳，开销很小。 */
    private static final long INTERVAL_MS = 60_000L;
    private static final int REQ_CODE = 7001;

    private AlarmScheduler() {
    }

    public static void schedule(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = pending(c);
        if (pi == null) return;
        long trigger = SystemClock.elapsedRealtime() + INTERVAL_MS;
        try {
            am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, INTERVAL_MS, pi);
        } catch (Throwable t) {
            try {
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi);
            } catch (Throwable ignored) {
            }
        }
    }

    public static void cancel(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = pending(c);
        if (pi == null) return;
        try {
            am.cancel(pi);
        } catch (Throwable ignored) {
        }
        try {
            pi.cancel();
        } catch (Throwable ignored) {
        }
    }

    private static PendingIntent pending(Context c) {
        Intent i = new Intent(c, WatchdogReceiver.class).setAction(WatchdogReceiver.ACTION_TICK);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        try {
            return PendingIntent.getBroadcast(c, REQ_CODE, i, flags);
        } catch (Throwable t) {
            return null;
        }
    }
}
