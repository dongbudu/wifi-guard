package com.wifiguard;

import android.content.Context;
import android.content.pm.PackageInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 读 GitHub Releases 的最新版本，和本机 versionCode 比较。
 * 版本号约定：1.2.3 -> 10203（主*10000 + 次*100 + 修）。
 */
public final class UpdateChecker {

    public static final class Info {
        public int versionCode;
        public String versionName = "";
        public String url = "";
        public String notes = "";
    }

    public interface Callback {
        /** error 非空表示失败；info 为 null 表示已经是最新。 */
        void onDone(String error, Info info);
    }

    private UpdateChecker() {
    }

    public static void checkAsync(final Context ctx, final Callback cb) {
        new Thread(() -> {
            String error = null;
            Info info = null;
            try {
                Info remote = fetchLatest();
                int current = currentVersionCode(ctx);
                if (remote.versionCode > current) {
                    info = remote;
                }
            } catch (Throwable t) {
                error = describe(t);
            }
            try {
                cb.onDone(error, info);
            } catch (Throwable ignored) {
            }
        }, "wig-update").start();
    }

    public static void downloadAsync(final Context ctx, final String url, final Callback2 cb) {
        new Thread(() -> {
            String error = null;
            java.io.File out = new java.io.File(ctx.getCacheDir(), "update.apk");
            try {
                download(url, out);
            } catch (Throwable t) {
                error = "下载失败：" + t.getMessage();
            }
            try {
                cb.onDone(error, out);
            } catch (Throwable ignored) {
            }
        }, "wig-download").start();
    }

    public interface Callback2 {
        void onDone(String error, java.io.File file);
    }

    // ------------------------------------------------------------------

    private static Info fetchLatest() throws Exception {
        if (!Config.updateSourceReady()) {
            throw new IllegalStateException("未配置更新源，请修改 Config.GITHUB_REPO");
        }
        URL u = new URL("https://api.github.com/repos/" + Config.GITHUB_REPO + "/releases/latest");
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        try {
            c.setConnectTimeout(6000);
            c.setReadTimeout(6000);
            c.setRequestProperty("Accept", "application/vnd.github+json");
            c.setRequestProperty("User-Agent", "WiFiGuard");
            int code = c.getResponseCode();
            if (code != 200) throw new Exception("GitHub 返回 HTTP " + code);

            String body = readAll(c.getInputStream());
            JSONObject o = new JSONObject(body);

            Info info = new Info();
            String tag = o.optString("tag_name", "");
            info.versionName = tag.replaceAll("^[vV]", "");
            info.versionCode = versionCodeOf(info.versionName);
            info.notes = o.optString("body", "");

            JSONArray assets = o.optJSONArray("assets");
            for (int i = 0; assets != null && i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                String name = a.optString("name", "").toLowerCase();
                if (name.endsWith(".apk")) {
                    info.url = a.optString("browser_download_url", "");
                    break;
                }
            }
            if (info.url.isEmpty()) throw new Exception("这个 Release 里没有 .apk 附件");
            return info;
        } finally {
            c.disconnect();
        }
    }

    private static void download(String url, java.io.File out) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", "WiFiGuard");
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code);
            InputStream in = c.getInputStream();
            java.io.FileOutputStream fos = new java.io.FileOutputStream(out);
            try {
                byte[] buf = new byte[8192];
                int r;
                while ((r = in.read(buf)) > 0) fos.write(buf, 0, r);
            } finally {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
                fos.close();
            }
        } finally {
            c.disconnect();
        }
    }

    public static int versionCodeOf(String name) {
        try {
            String[] p = name.split("[.\\-]");
            int major = p.length > 0 ? Integer.parseInt(p[0].trim()) : 0;
            int minor = p.length > 1 ? Integer.parseInt(p[1].trim()) : 0;
            int patch = p.length > 2 ? Integer.parseInt(p[2].trim()) : 0;
            return major * 10000 + minor * 100 + patch;
        } catch (Throwable t) {
            return 0;
        }
    }

    public static int currentVersionCode(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return pi.versionCode;
        } catch (Throwable t) {
            return 0;
        }
    }

    public static String currentVersionName(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return pi.versionName;
        } catch (Throwable t) {
            return "";
        }
    }

    private static String describe(Throwable t) {
        String m = t.getMessage();
        if (t instanceof java.net.UnknownHostException) return "网络不可用（解析不到 api.github.com）";
        if (t instanceof java.net.SocketTimeoutException) return "连接 GitHub 超时";
        return "检查更新失败：" + (m == null ? t.toString() : m);
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int r;
        while ((r = in.read(buf)) > 0) bos.write(buf, 0, r);
        return new String(bos.toByteArray(), "UTF-8");
    }
}
