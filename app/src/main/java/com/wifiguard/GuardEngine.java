package com.wifiguard;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

/**
 * 核心状态机。所有方法都在自己的 HandlerThread 上跑，探针是阻塞 IO，
 * 绝对不能放在主线程。
 *
 * 2 秒无网 -> 重连一次 -> 3 秒仍无网 -> 断开这张 WiFi（不关无线开关）
 *
 * IDLE --(连续 grace 秒无网)--> SUSPECT --(超时)--> RECONNECTING
 * RECONNECTING --(wait 秒仍无网)--> DISCONNECTED
 */
public final class GuardEngine {

    public enum State {
        OFF("未启用"),
        IDLE("网络正常"),
        SUSPECT("疑似断网"),
        RECONNECTING("正在重连"),
        DISCONNECTING("正在断开"),
        DISCONNECTED("已断开"),
        WAITING("等待接入"),
        MANUAL("需手动处理"),
        STOPPED("已停止");

        public final String label;

        State(String label) {
            this.label = label;
        }
    }

    public static final class Status {
        public State state = State.OFF;
        public String title = "未启用";
        public String detail = "";
        public String ssid = "";
        public boolean wifiEnabled;
        public boolean associated;
        public boolean online;
        public boolean root;
        public long checkedAt;
        public int reconnects;
        public int disconnects;

        public Status copy() {
            Status s = new Status();
            s.state = state;
            s.title = title;
            s.detail = detail;
            s.ssid = ssid;
            s.wifiEnabled = wifiEnabled;
            s.associated = associated;
            s.online = online;
            s.root = root;
            s.checkedAt = checkedAt;
            s.reconnects = reconnects;
            s.disconnects = disconnects;
            return s;
        }
    }

    public interface Callback {
        /** 在引擎线程回调，实现方自己切主线程。 */
        void onUpdate(Status s);

        /** 自动化手段全部失效，需要人工处理。 */
        void onManual(String reason);
    }

    /** 正常巡检：轻量 TCP 探针，1.5 秒一次。 */
    private static final long POLL_ONLINE_MS = 1500;
    /** 疑似/重连中：加快到 0.5 秒，保证 2 秒/3 秒的节奏准。 */
    private static final long POLL_ACTIVE_MS = 500;
    /** 断开或无线关闭后：慢速待命。 */
    private static final long POLL_IDLE_MS = 15000;
    /** 断开后系统若又把这张网连回来，最多再断几次。 */
    private static final int MAX_REKICK = 3;
    private static final long REKICK_GAP_MS = 10000;

    private final Context app;
    private final Callback cb;
    private final WifiManager wm;

    private HandlerThread thread;
    private Handler h;
    private volatile boolean running;

    private State state = State.OFF;
    private long suspectSince;
    private long deadline;
    private long nextKickAt;
    private int reKicks;
    private int reconnects;
    private int disconnects;

    private String fSsid = "";
    private boolean fWifi;
    private boolean fAssoc;
    private boolean fOnline;
    private boolean fRoot;

    private final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                tick();
            } catch (Throwable t) {
                Logx.log("检测异常: " + t);
                schedule(POLL_ACTIVE_MS);
            }
        }
    };

    public GuardEngine(Context context, Callback cb) {
        this.app = context.getApplicationContext();
        this.cb = cb;
        this.wm = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        thread = new HandlerThread("wig-guard");
        thread.start();
        h = new Handler(thread.getLooper());
        Logx.log("监控已启动");
        h.postDelayed(tickRunnable, 300);
    }

    public synchronized void stop() {
        if (!running) return;
        running = false;
        if (h != null) h.removeCallbacksAndMessages(null);
        if (thread != null) thread.quitSafely();
        h = null;
        thread = null;
        state = State.STOPPED;
        Logx.log("监控已停止");
    }

    public boolean isRunning() {
        return running;
    }

    /** 请求立刻检测一次。 */
    public void poke() {
        Handler handler = h;
        if (handler != null && running) handler.post(tickRunnable);
    }

    public Status snapshot() {
        Status s = new Status();
        s.state = state;
        s.title = state.label;
        s.ssid = fSsid;
        s.wifiEnabled = fWifi;
        s.associated = fAssoc;
        s.online = fOnline;
        s.root = fRoot;
        s.reconnects = reconnects;
        s.disconnects = disconnects;
        s.checkedAt = SystemClock.elapsedRealtime();
        return s;
    }

    // ------------------------------------------------------------------

    private void schedule(long delayMs) {
        if (!running) return;
        Handler handler = h;
        if (handler == null) return;
        handler.removeCallbacks(tickRunnable);
        handler.postDelayed(tickRunnable, Math.max(300, delayMs));
    }

    private void tick() {
        if (!running) return;

        long now = SystemClock.elapsedRealtime();
        int graceSec = Prefs.graceSec(app);
        long graceMs = graceSec * 1000L;
        long waitMs = Prefs.waitSec(app) * 1000L;

        fWifi = isWifiOn();
        fAssoc = fWifi && NetUtils.associated(wm);
        fSsid = fAssoc ? NetUtils.ssid(wm) : "";
        fRoot = Prefs.preferRoot(app) && NetUtils.hasRoot();

        if (!Prefs.enabled(app)) {
            state = State.OFF;
            publish("监控未启用", "打开开关后开始守护");
            return;
        }

        if (!fWifi) {
            fOnline = false;
            if (state == State.RECONNECTING) {
                if (now >= deadline) onReconnectTimeout();
                else publish("正在重连", "重连中，等待 WiFi 恢复");
                schedule(POLL_ACTIVE_MS);
                return;
            }
            if (state == State.DISCONNECTED || state == State.MANUAL) {
                publish(state == State.DISCONNECTED ? "已断开该 WiFi" : "需要手动断开 WiFi",
                        state == State.DISCONNECTED
                                ? "无线开关已关，等待你手动处理"
                                : "系统不允许应用断开 WiFi，请下拉控制中心操作");
                schedule(POLL_IDLE_MS);
                return;
            }
            state = State.OFF;
            publish("WiFi 已关闭", "打开 WiFi 后自动开始检测");
            schedule(POLL_IDLE_MS);
            return;
        }

        if (!fAssoc) {
            fOnline = false;
            if (state == State.RECONNECTING) {
                if (now >= deadline) onReconnectTimeout();
                else publish("正在重连", "重连中，等待重新关联热点");
                schedule(POLL_ACTIVE_MS);
                return;
            }
            if (state == State.DISCONNECTED) {
                publish("已断开该 WiFi", "无线保持开启，可点“恢复连接”重新启用");
                schedule(POLL_IDLE_MS);
                return;
            }
            state = State.WAITING;
            publish("等待接入 WiFi", "WiFi 已开启，正在等待连上热点");
            schedule(POLL_ONLINE_MS);
            return;
        }

        fOnline = probe(state == State.IDLE || state == State.WAITING);

        if (fOnline) {
            if (state == State.RECONNECTING) {
                Logx.log("重连成功，网络已恢复");
            } else if (state == State.SUSPECT) {
                Logx.log("网络恢复正常，取消本次处理");
            } else if (state == State.DISCONNECTED) {
                Logx.log("WiFi 已重新连接且能上网，恢复守护");
                Prefs.clearDisabled(app);
            }
            reKicks = 0;
            state = State.IDLE;
            publish("网络正常", "WiFi 有网络，持续守护中");
            schedule(POLL_ONLINE_MS);
            return;
        }

        // 已连上 WiFi，但确实上不了网
        switch (state) {
            case IDLE:
                state = State.SUSPECT;
                suspectSince = now;
                Logx.log("检测到 WiFi 无网络，等待 " + graceSec + " 秒确认");
                publish("检测到无网络", "确认 " + graceSec + " 秒后自动重连");
                schedule(graceMs);
                return;

            case SUSPECT:
                if (now - suspectSince >= graceMs) {
                    beginReconnect(waitMs);
                    schedule(POLL_ACTIVE_MS);
                    return;
                }
                publish("检测到无网络",
                        "再等 " + Math.max(1, (graceMs - (now - suspectSince)) / 1000) + " 秒后重连");
                schedule(POLL_ACTIVE_MS);
                return;

            case RECONNECTING:
                if (now >= deadline) {
                    onReconnectTimeout();
                    schedule(POLL_ACTIVE_MS);
                    return;
                }
                publish("正在重连", "重连中，最长再等 " + Math.max(1, (deadline - now) / 1000) + " 秒");
                schedule(POLL_ACTIVE_MS);
                return;

            case DISCONNECTED:
                // 系统有时会把刚断开的网又自动连回来，这张网依旧没网就再断一次
                if (now >= nextKickAt && reKicks < MAX_REKICK) {
                    reKicks++;
                    nextKickAt = now + REKICK_GAP_MS;
                    Logx.log("系统又自动连回了这张无网络的 WiFi，再次断开（第 " + reKicks + " 次）");
                    beginDisconnect();
                    schedule(POLL_ACTIVE_MS);
                    return;
                }
                if (reKicks >= MAX_REKICK) {
                    state = State.MANUAL;
                    publish("需要手动断开 WiFi", "系统反复自动重连这张无网 WiFi，已停止自动干预");
                    if (cb != null) {
                        try {
                            cb.onManual("这张 WiFi 没有网络，系统又一直自动重连。建议手动关闭 WiFi，或关闭该热点的自动连接。");
                        } catch (Throwable ignored) {
                        }
                    }
                    schedule(POLL_IDLE_MS);
                    return;
                }
                publish("已断开该 WiFi", "无线保持开启，可点“恢复连接”重新启用");
                schedule(POLL_IDLE_MS);
                return;

            case MANUAL:
                publish("需要手动断开 WiFi", "系统限制无法自动断开，请在设置中操作");
                schedule(POLL_IDLE_MS);
                return;

            default:
                state = State.SUSPECT;
                suspectSince = now;
                schedule(POLL_ACTIVE_MS);
        }
    }

    /**
     * 轻量探针先跑；deep=true 时失败后再做一次完整确认，避免误判。
     * 处于“疑似/重连中”时只跑轻量探针，把判定时间压到 0.5 秒内。
     */
    private boolean probe(boolean deep) {
        android.net.Network wifi = NetUtils.wifiNetwork(app);
        if (wifi == null) return false;
        if (NetUtils.quickOnline(wifi)) return true;
        return deep && NetUtils.isOnline(wifi);
    }

    private void beginReconnect(long waitMs) {
        long now = SystemClock.elapsedRealtime();
        state = State.RECONNECTING;
        deadline = now + waitMs;
        reconnects++;

        boolean ok = NetUtils.reassociate(wm, fRoot);
        if (ok) {
            Logx.log("第 " + reconnects + " 次自动重连：已发出重连请求");
        } else {
            Logx.log("第 " + reconnects + " 次自动重连：系统拒绝，无法重新关联");
        }
        publish("正在重连", "已触发重连，" + Math.max(1, waitMs / 1000) + " 秒后仍无网络就断开");
    }

    private void onReconnectTimeout() {
        if (!Prefs.disconnectOnFail(app)) {
            Logx.log("重连失败，当前设置为只重连不断开，稍后重试");
            state = State.SUSPECT;
            suspectSince = SystemClock.elapsedRealtime();
            return;
        }
        beginDisconnect();
    }

    /**
     * 只断开这张 WiFi，不动无线开关：
     * disconnect() 断掉当前连接，disableNetwork() 阻止系统立刻又连回去
     * （否则几秒后自动重连，等于没断开）。
     */
    private void beginDisconnect() {
        state = State.DISCONNECTING;
        Logx.log("重连后仍然无网络，开始断开这张 WiFi（不关无线开关）");
        int netId = NetUtils.netId(wm);

        boolean disabled = false;
        if (netId >= 0) {
            try {
                disabled = wm.disableNetwork(netId);
            } catch (Throwable ignored) {
            }
        }

        boolean kicked = false;
        try {
            kicked = wm.disconnect();
        } catch (Throwable ignored) {
        }

        if (netId >= 0) {
            Prefs.rememberDisabled(app, netId, fSsid);
        }
        Logx.log("断开动作：disconnect()=" + kicked + "，disableNetwork()=" + disabled);

        if (kicked || disabled) {
            disconnects++;
            state = State.DISCONNECTED;
            fOnline = false;
            nextKickAt = SystemClock.elapsedRealtime() + REKICK_GAP_MS;
            if (disabled) {
                Logx.log("已断开这张 WiFi，并暂停它的自动连接（无线开关保持开启）");
                publish("已断开该 WiFi", "无线保持开启，已改用移动网络；可点“恢复连接”重新启用");
            } else {
                Logx.log("已断开这张 WiFi，但系统不允许暂停自动连接，可能会自己连回来");
                publish("已断开该 WiFi", "无线保持开启；系统若自动连回来会再断一次");
            }
            return;
        }

        Logx.log("无法自动断开 WiFi，需要手动处理");
        state = State.MANUAL;
        publish("需要手动断开 WiFi", "系统不允许应用断开 WiFi，请下拉控制中心操作");
        if (cb != null) {
            try {
                cb.onManual("重连失败，但系统不允许自动断开 WiFi。请手动断开（或给本机 root 权限）。");
            } catch (Throwable ignored) {
            }
        }
    }

    private boolean isWifiOn() {
        try {
            return wm.isWifiEnabled();
        } catch (Throwable t) {
            return false;
        }
    }

    private void publish(String title, String detail) {
        Status s = new Status();
        s.state = state;
        s.title = title;
        s.detail = detail;
        s.ssid = fSsid;
        s.wifiEnabled = fWifi;
        s.associated = fAssoc;
        s.online = fOnline;
        s.root = fRoot;
        s.reconnects = reconnects;
        s.disconnects = disconnects;
        s.checkedAt = SystemClock.elapsedRealtime();
        if (cb != null) {
            try {
                cb.onUpdate(s.copy());
            } catch (Throwable ignored) {
            }
        }
    }
}
