package com.wifiguard;

import android.app.Application;
import android.util.Log;

/** 进程入口：把崩溃原因记下来，交给看门狗去收拾。 */
public class WigApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        final Thread.UncaughtExceptionHandler def = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                Heartbeat.crash(this, "崩溃 in " + t.getName() + ": " + Log.getStackTraceString(e));
            } catch (Throwable ignored) {
            }
            if (def != null) def.uncaughtException(t, e);
        });
    }
}
