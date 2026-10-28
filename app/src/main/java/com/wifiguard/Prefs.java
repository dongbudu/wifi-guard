package com.wifiguard;

import android.content.Context;
import android.content.SharedPreferences;

/** 用户设置。默认值按“2 秒判定 / 3 秒等待”调好，装好即用。 */
public final class Prefs {

    private static final String NAME = "wig_prefs";

    public static final int DEF_GRACE_SEC = 2;    // 连续多久判定“无网络”
    public static final int DEF_WAIT_SEC = 3;     // 重连后等多久，仍无网则断开
    public static final boolean DEF_DISCONNECT = true;
    public static final boolean DEF_ROOT = true;

    private Prefs() {
    }

    public static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static boolean enabled(Context c) {
        return sp(c).getBoolean("enabled", false);
    }

    public static void setEnabled(Context c, boolean v) {
        sp(c).edit().putBoolean("enabled", v).apply();
    }

    public static int graceSec(Context c) {
        return clamp(sp(c).getInt("grace", DEF_GRACE_SEC), 1, 120);
    }

    public static int waitSec(Context c) {
        return clamp(sp(c).getInt("wait", DEF_WAIT_SEC), 2, 300);
    }

    public static boolean disconnectOnFail(Context c) {
        return sp(c).getBoolean("disconnect", DEF_DISCONNECT);
    }

    public static boolean preferRoot(Context c) {
        return sp(c).getBoolean("root", DEF_ROOT);
    }

    public static void save(Context c, boolean enabled, int grace, int wait,
                            boolean disconnect, boolean root) {
        sp(c).edit()
                .putBoolean("enabled", enabled)
                .putInt("grace", clamp(grace, 1, 120))
                .putInt("wait", clamp(wait, 2, 300))
                .putBoolean("disconnect", disconnect)
                .putBoolean("root", root)
                .apply();
    }

    // 记住被断开的那张网，方便“恢复连接”
    public static void rememberDisabled(Context c, int netId, String ssid) {
        sp(c).edit()
                .putInt("offNetId", netId)
                .putString("offSsid", ssid == null ? "" : ssid)
                .putLong("offAt", System.currentTimeMillis())
                .apply();
    }

    public static int disabledNetId(Context c) {
        return sp(c).getInt("offNetId", -1);
    }

    public static String disabledSsid(Context c) {
        return sp(c).getString("offSsid", "");
    }

    public static void clearDisabled(Context c) {
        sp(c).edit().putInt("offNetId", -1).remove("offSsid").apply();
    }

    /** 看门狗把主服务拉起来的次数，界面上显示出来便于判断稳不稳。 */
    public static int watchdogRestarts(Context c) {
        return sp(c).getInt("dogRestarts", 0);
    }

    public static void countWatchdogRestart(Context c) {
        sp(c).edit().putInt("dogRestarts", watchdogRestarts(c) + 1).apply();
    }

    // 自动检查更新的节流与“已下载待安装”的版本
    public static long lastUpdateCheck(Context c) {
        return sp(c).getLong("updCheckAt", 0L);
    }

    public static void setLastUpdateCheck(Context c, long at) {
        sp(c).edit().putLong("updCheckAt", at).apply();
    }

    public static int pendingUpdate(Context c) {
        return sp(c).getInt("updPending", 0);
    }

    public static void setPendingUpdate(Context c, int versionCode) {
        sp(c).edit().putInt("updPending", versionCode).apply();
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
