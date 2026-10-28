package com.wifiguard;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

/** 调起系统安装器。 */
public final class ApkInstaller {

    private ApkInstaller() {
    }

    /** Android 8+ 需要用户给本应用“安装未知应用”的授权。 */
    public static boolean canInstall(Context c) {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                return c.getPackageManager().canRequestPackageInstalls();
            } catch (Throwable t) {
                return false;
            }
        }
        return true;
    }

    public static void openUnknownSourcesSetting(Context c) {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + c.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Throwable t) {
            try {
                c.startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Throwable ignored) {
            }
        }
    }

    public static boolean install(Context c) {
        if (!ApkProvider.apkFile(c).exists()) return false;
        Uri uri = ApkProvider.uri();
        try {
            // 显式给系统安装器授权（部分 ROM 的安装器不是 ACTION_VIEW 的默认处理者）
            c.grantUriPermission("com.android.packageinstaller", uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Throwable ignored) {
        }
        try {
            Intent i = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, ApkProvider.MIME)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            return true;
        } catch (Throwable t) {
            Heartbeat.crash(c, "调起安装器失败: " + t);
            return false;
        }
    }
}
