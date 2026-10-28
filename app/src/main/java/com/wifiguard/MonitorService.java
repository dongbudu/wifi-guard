package com.wifiguard;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.util.concurrent.CopyOnWriteArrayList;

/** 前台服务，保证锁屏/切后台时状态机还在跑。 */
public class MonitorService extends Service implements GuardEngine.Callback {

    public static final String ACTION_START = "com.wifiguard.action.START";
    public static final String ACTION_CHECK = "com.wifiguard.action.CHECK";
    public static final String ACTION_STOP = "com.wifiguard.action.STOP";

    private static final String CHANNEL_ID = "wig_monitor";
    private static final int NOTIF_ID = 0x7701;
    private static final int ALERT_ID = 0x7702;

    public interface StatusListener {
        void onStatus(GuardEngine.Status s);
    }

    private static final CopyOnWriteArrayList<StatusListener> LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile GuardEngine.Status lastStatus;

    private final Handler main = new Handler(Looper.getMainLooper());
    private GuardEngine engine;
    private String lastNotifTitle;
    private String lastNotifText;

    public static void start(Context c) {
        Intent i = new Intent(c, MonitorService.class).setAction(ACTION_START);
        startCompat(c, i);
    }

    public static void checkNow(Context c) {
        Intent i = new Intent(c, MonitorService.class).setAction(ACTION_CHECK);
        startCompat(c, i);
    }

    public static void stop(Context c) {
        c.stopService(new Intent(c, MonitorService.class));
    }

    private static void startCompat(Context c, Intent i) {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                c.startForegroundService(i);
            } else {
                c.startService(i);
            }
        } catch (Throwable t) {
            Heartbeat.crash(c, "启动服务失败: " + t);
        }
    }

    public static void addListener(StatusListener l) {
        if (l != null && !LISTENERS.contains(l)) LISTENERS.add(l);
    }

    public static void removeListener(StatusListener l) {
        LISTENERS.remove(l);
    }

    public static GuardEngine.Status lastStatus() {
        return lastStatus;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIF_ID, buildNotification("正在启动", "初始化监控…", true));
        Heartbeat.beat(this);
        AlarmScheduler.schedule(this);
        engine = new GuardEngine(this, this);
        engine.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();

        if (ACTION_STOP.equals(action)) {
            Prefs.setEnabled(this, false);
            AlarmScheduler.cancel(this);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_CHECK.equals(action)) {
            if (engine != null) engine.poke();
            return START_STICKY;
        }

        if (!Prefs.enabled(this)) {
            AlarmScheduler.cancel(this);
            stopSelf();
            return START_NOT_STICKY;
        }

        // 被看门狗/系统拉起时，onCreate 已经建好引擎，这里只需确保闹钟还在
        AlarmScheduler.schedule(this);
        if (engine == null) {
            engine = new GuardEngine(this, this);
            engine.start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (engine != null) {
            engine.stop();
            engine = null;
        }
        // 用户主动关闭才撤掉闹钟；被系统杀掉时要留着，让看门狗能拉起来
        if (!Prefs.enabled(this)) {
            AlarmScheduler.cancel(this);
        }
        try {
            stopForeground(true);
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---------------- 引擎回调（引擎线程） ----------------

    @Override
    public void onUpdate(final GuardEngine.Status s) {
        lastStatus = s;
        Heartbeat.beat(this);
        main.post(() -> {
            for (StatusListener l : LISTENERS) {
                try {
                    l.onStatus(s);
                } catch (Throwable ignored) {
                }
            }
            String ssid = s.ssid == null || s.ssid.isEmpty() ? "" : s.ssid;
            updateNotification(s.title, ssid.isEmpty() ? s.detail : ssid);
        });
    }

    @Override
    public void onManual(final String reason) {
        main.post(() -> {
            try {
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (nm == null) return;
                nm.notify(ALERT_ID, buildNotification("需要手动断开 WiFi", reason, false));
            } catch (Throwable ignored) {
            }
        });
    }

    // ---------------- 通知 ----------------

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription(getString(R.string.channel_desc));
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private void updateNotification(String title, String text) {
        if (title.equals(lastNotifTitle) && text.equals(lastNotifText)) return;
        lastNotifTitle = title;
        lastNotifText = text;
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;
            nm.notify(NOTIF_ID, buildNotification(title, text, true));
        } catch (Throwable ignored) {
        }
    }

    private Notification buildNotification(String title, String text, boolean ongoing) {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 1, open, flags);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        b.setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text)
                .setOnlyAlertOnce(true)
                .setOngoing(ongoing)
                .setShowWhen(false)
                .setContentIntent(pi);

        if (ongoing) {
            Intent stopIntent = new Intent(this, MonitorService.class).setAction(ACTION_STOP);
            PendingIntent stopPi = PendingIntent.getService(this, 2, stopIntent, flags);
            b.addAction(new Notification.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel, "停止监控", stopPi).build());
            b.setPriority(Notification.PRIORITY_LOW);
        } else {
            b.setAutoCancel(true);
            b.setPriority(Notification.PRIORITY_DEFAULT);
        }
        return b.build();
    }
}
