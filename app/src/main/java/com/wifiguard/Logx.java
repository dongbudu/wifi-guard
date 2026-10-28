package com.wifiguard;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/** 进程内运行日志：既是 logcat，也是界面上能看的列表。 */
public final class Logx {

    public interface Sink {
        void onLine(String line);
    }

    private static final int MAX = 400;
    private static final ArrayDeque<String> LINES = new ArrayDeque<>();
    private static final CopyOnWriteArrayList<Sink> SINKS = new CopyOnWriteArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Logx() {
    }

    public static void log(String msg) {
        String line = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + "  " + msg;
        synchronized (LINES) {
            LINES.addLast(line);
            while (LINES.size() > MAX) LINES.removeFirst();
        }
        Log.i("WifiGuard", msg);
        MAIN.post(() -> {
            for (Sink s : SINKS) {
                try {
                    s.onLine(line);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    public static List<String> snapshot() {
        synchronized (LINES) {
            return new ArrayList<>(LINES);
        }
    }

    public static void clear() {
        synchronized (LINES) {
            LINES.clear();
        }
    }

    public static void addSink(Sink s) {
        if (s != null && !SINKS.contains(s)) SINKS.add(s);
    }

    public static void removeSink(Sink s) {
        SINKS.remove(s);
    }
}
