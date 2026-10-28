package com.wifiguard;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 存活指示 + 崩溃记录。看门狗通过心跳文件的修改时间判断主服务是否还活着，
 * 这样即使主进程崩了，独立进程/闹钟也能知道“该拉一把了”。
 */
public final class Heartbeat {

    public static final long STALE_MS = 15_000L;

    private Heartbeat() {
    }

    private static File beatFile(Context c) {
        return new File(c.getFilesDir(), "guard.hb");
    }

    public static void beat(Context c) {
        try {
            File f = beatFile(c);
            long now = System.currentTimeMillis();
            if (!f.exists()) {
                if (!f.createNewFile()) return;
            }
            //noinspection ResultOfMethodCallIgnored
            f.setLastModified(now);
        } catch (Throwable ignored) {
        }
    }

    /** 距离上次心跳多少毫秒；0 表示还没有心跳。 */
    public static long ageMs(Context c) {
        try {
            long t = beatFile(c).lastModified();
            if (t <= 0) return 0L;
            long age = System.currentTimeMillis() - t;
            return age < 0 ? 0L : age;
        } catch (Throwable t) {
            return 0L;
        }
    }

    public static boolean stale(Context c) {
        long age = ageMs(c);
        return age == 0L || age > STALE_MS;
    }

    // ---------------- 崩溃记录 ----------------

    private static File crashFile(Context c) {
        return new File(c.getFilesDir(), "crash.log");
    }

    public static void crash(Context c, String what) {
        String line = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date())
                + "  " + what + "\n";
        Log.e("WifiGuard", what);
        try {
            File f = crashFile(c);
            if (f.exists() && f.length() > 128 * 1024) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
            FileOutputStream fos = new FileOutputStream(f, true);
            try {
                fos.write(line.getBytes("UTF-8"));
            } finally {
                fos.close();
            }
        } catch (Throwable ignored) {
        }
    }

    public static String crashLog(Context c) {
        try {
            File f = crashFile(c);
            if (!f.exists()) return "";
            byte[] buf = new byte[(int) Math.min(f.length(), 20000)];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            try {
                int n = in.read(buf);
                return n <= 0 ? "" : new String(buf, 0, n, "UTF-8");
            } finally {
                in.close();
            }
        } catch (Throwable t) {
            return "";
        }
    }
}
