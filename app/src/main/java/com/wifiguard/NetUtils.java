package com.wifiguard;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 网络探测 + root 辅助。
 * 探测全部绑定到 WiFi 网络，多个探针并行跑，谁先通用谁的结果，
 * 所以“没网”这种情况也能在 1 秒多一点内确定，而不是一个个超时叠加。
 */
public final class NetUtils {

    private static final String[] HTTP_PROBES = {
            "http://connect.rom.miui.com/generate_204",
            "http://www.baidu.com/",
            "http://connectivitycheck.gstatic.com/generate_204"
    };

    private static final String[] TCP_PROBES = {"223.5.5.5:53", "114.114.114.114:53"};

    /** 快速探针：只做 TCP 握手，用来 2 秒一次地轻量巡检。 */
    private static final String[] QUICK_PROBES = {"223.5.5.5:53", "114.114.114.114:53"};

    private static final ExecutorService POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "wig-probe");
        t.setDaemon(true);
        return t;
    });

    private static volatile Boolean rootCache;
    private static volatile long rootCheckedAt;

    private NetUtils() {
    }

    /** 当前 WiFi 对应的 Network 对象；WiFi 未连接或没有网络对象时返回 null。 */
    public static Network wifiNetwork(Context c) {
        ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return null;
        try {
            Network fallback = null;
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                if (nc == null || !nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue;
                if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return n;
                if (fallback == null) fallback = n;
            }
            return fallback;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 轻量巡检：两次 TCP 握手，预算 750ms。 */
    public static boolean quickOnline(Network wifi) {
        if (wifi == null) return false;
        List<Callable<Boolean>> tasks = new ArrayList<>(QUICK_PROBES.length);
        for (String hp : QUICK_PROBES) {
            final String host = hostOf(hp);
            final int port = portOf(hp);
            tasks.add(() -> tcpProbe(wifi, host, port, 420));
        }
        return anyOf(tasks, 480);
    }

    /** 完整探测：HTTP + TCP 并行，预算约 1.2 秒。 */
    public static boolean isOnline(Network wifi) {
        if (wifi == null) return false;
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (String url : HTTP_PROBES) {
            tasks.add(() -> httpProbe(wifi, url, 720));
        }
        for (String hp : TCP_PROBES) {
            final String host = hostOf(hp);
            final int port = portOf(hp);
            tasks.add(() -> tcpProbe(wifi, host, port, 720));
        }
        return anyOf(tasks, 820);
    }

    private static String hostOf(String hostPort) {
        int i = hostPort.indexOf(':');
        return i > 0 ? hostPort.substring(0, i) : hostPort;
    }

    private static int portOf(String hostPort) {
        int i = hostPort.indexOf(':');
        if (i <= 0) return 53;
        try {
            return Integer.parseInt(hostPort.substring(i + 1));
        } catch (Throwable t) {
            return 53;
        }
    }

    private static boolean anyOf(List<Callable<Boolean>> tasks, long budgetMs) {
        List<Future<Boolean>> futures = new ArrayList<>(tasks.size());
        try {
            for (Callable<Boolean> task : tasks) {
                futures.add(POOL.submit(task));
            }
            long deadline = System.nanoTime() + budgetMs * 1_000_000L;
            while (System.nanoTime() < deadline) {
                boolean allDone = true;
                for (Future<Boolean> f : futures) {
                    if (!f.isDone()) {
                        allDone = false;
                        continue;
                    }
                    try {
                        if (Boolean.TRUE.equals(f.get())) return true;
                    } catch (Throwable ignored) {
                    }
                }
                if (allDone) return false;
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return false;
        } finally {
            for (Future<Boolean> f : futures) {
                f.cancel(true);
            }
        }
    }

    private static boolean httpProbe(Network n, String url, int timeout) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) n.openConnection(new URL(url));
            conn.setConnectTimeout(timeout);
            conn.setReadTimeout(timeout);
            conn.setInstanceFollowRedirects(false);
            conn.setUseCaches(false);
            conn.setRequestProperty("User-Agent", "WifiGuard/1.1 (Android)");
            conn.setRequestProperty("Connection", "close");
            return conn.getResponseCode() > 0;
        } catch (Throwable t) {
            return false;
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static boolean tcpProbe(Network n, String host, int port, int timeout) {
        Socket s = new Socket();
        try {
            n.bindSocket(s);
            s.connect(new InetSocketAddress(host, port), timeout);
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            try {
                s.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 是否已经连上某个 WiFi（不论有没有网）。 */
    public static boolean associated(WifiManager wm) {
        try {
            WifiInfo wi = wm.getConnectionInfo();
            if (wi == null) return false;
            if (wi.getNetworkId() == -1) return false;
            SupplicantState st = wi.getSupplicantState();
            return st == SupplicantState.COMPLETED || st == SupplicantState.ASSOCIATED
                    || st == SupplicantState.FOUR_WAY_HANDSHAKE || st == SupplicantState.GROUP_HANDSHAKE;
        } catch (Throwable t) {
            return false;
        }
    }

    public static int netId(WifiManager wm) {
        try {
            WifiInfo wi = wm.getConnectionInfo();
            return wi == null ? -1 : wi.getNetworkId();
        } catch (Throwable t) {
            return -1;
        }
    }

    public static String ssid(WifiManager wm) {
        try {
            WifiInfo wi = wm.getConnectionInfo();
            if (wi == null) return "";
            String s = wi.getSSID();
            if (s == null) return "";
            if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length() - 1);
            if (s.equals("<unknown ssid>") || s.equals("0x")) return "";
            return s;
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * “重连一次”的具体动作：
     * 1) reconnect() 重新关联；2) 不行就 disable+enable 这张网强制重连；3) 有 root 再用 svc wifi reassociate。
     * 全是即时调用，不做 sleep，所以能在几秒窗口内完成。
     */
    public static boolean reassociate(WifiManager wm, boolean useRoot) {
        try {
            if (wm.reconnect()) return true;
        } catch (Throwable ignored) {
        }
        int id = netId(wm);
        if (id >= 0) {
            try {
                wm.disableNetwork(id);
                if (wm.enableNetwork(id, true)) return true;
            } catch (Throwable ignored) {
            }
        }
        return useRoot && rootExec("svc wifi reassociate");
    }

    // ---------------- root ----------------

    public static boolean hasRoot() {
        long now = System.currentTimeMillis();
        Boolean cached = rootCache;
        if (cached != null && now - rootCheckedAt < 5 * 60_000L) return cached;
        boolean ok = false;
        StringBuilder sb = new StringBuilder();
        try {
            ok = exec(new String[]{"su", "-c", "id"}, 4000, sb) && sb.toString().contains("uid=0");
        } catch (Throwable ignored) {
        }
        rootCache = ok;
        rootCheckedAt = now;
        return ok;
    }

    public static boolean rootExec(String cmd) {
        if (!hasRoot()) return false;
        StringBuilder sb = new StringBuilder();
        return exec(new String[]{"su", "-c", cmd}, 8000, sb);
    }

    private static boolean exec(String[] cmd, int timeoutSec, StringBuilder out) {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(cmd);
            final Process proc = p;
            final AtomicBoolean killed = new AtomicBoolean(false);
            Thread watchdog = new Thread(() -> {
                try {
                    Thread.sleep(timeoutSec * 1000L);
                    if (isRunning(proc)) {
                        killed.set(true);
                        proc.destroy();
                    }
                } catch (Throwable ignored) {
                }
            });
            watchdog.setDaemon(true);
            watchdog.start();
            out.append(readAll(p.getInputStream()));
            int code = p.waitFor();
            watchdog.interrupt();
            return !killed.get() && code == 0;
        } catch (Throwable t) {
            return false;
        } finally {
            if (p != null) {
                try {
                    p.destroy();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** API 23 上没有 Process.isAlive()，用 exitValue 反推。 */
    private static boolean isRunning(Process p) {
        try {
            p.exitValue();
            return false;
        } catch (IllegalThreadStateException e) {
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String readAll(InputStream in) {
        if (in == null) return "";
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int r;
            while ((r = in.read(buf)) > 0) bos.write(buf, 0, r);
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Throwable t) {
            return "";
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
    }

    public static List<String> wifiIfaces() {
        try {
            List<String> out = new ArrayList<>();
            for (java.net.NetworkInterface ni : Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                String name = ni.getName();
                if (name != null && name.startsWith("wlan")) out.add(name);
            }
            return out;
        } catch (Throwable t) {
            return Collections.emptyList();
        }
    }
}
