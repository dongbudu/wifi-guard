package com.wifiguard;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity implements MonitorService.StatusListener, Logx.Sink {

    private static final int REQ_PERMS = 1001;

    private TextView tvState;
    private TextView tvDetail;
    private TextView tvSsid;
    private TextView tvMeta;
    private TextView tvLog;
    private ScrollView logScroll;
    private View dot;

    private Switch swEnable;
    private Switch swDisconnect;
    private Switch swRoot;
    private EditText etGrace;
    private EditText etWait;
    private Button btnRestore;
    private Button btnUpdate;
    private ProgressDialog downloadDialog;

    private boolean suppress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvState = findViewById(R.id.tv_state);
        tvDetail = findViewById(R.id.tv_detail);
        tvSsid = findViewById(R.id.tv_ssid);
        tvMeta = findViewById(R.id.tv_meta);
        tvLog = findViewById(R.id.tv_log);
        logScroll = findViewById(R.id.log_scroll);
        dot = findViewById(R.id.dot);

        swEnable = findViewById(R.id.sw_enable);
        swDisconnect = findViewById(R.id.sw_disconnect);
        swRoot = findViewById(R.id.sw_root);
        etGrace = findViewById(R.id.et_grace);
        etWait = findViewById(R.id.et_wait);

        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(0xFF94A3B8);
        dot.setBackground(g);

        loadSettings();
        wire();
        renderLog();

        Button btnCheck = findViewById(R.id.btn_check);
        btnCheck.setOnClickListener(v -> {
            if (!Prefs.enabled(this)) {
                toast("请先打开“启用监控”");
                return;
            }
            applySettings();
            MonitorService.checkNow(this);
            toast("已触发一次检测");
        });

        Button btnSettings = findViewById(R.id.btn_settings);
        btnSettings.setOnClickListener(v -> openWifiSettings());

        Button btnClear = findViewById(R.id.btn_clear);
        btnClear.setOnClickListener(v -> {
            Logx.clear();
            tvLog.setText("");
            toast("日志已清空");
        });

        Button btnBattery = findViewById(R.id.btn_battery);
        btnBattery.setOnClickListener(v -> requestIgnoreBattery());

        btnRestore = findViewById(R.id.btn_restore);
        btnRestore.setOnClickListener(v -> restoreNetwork());

        btnUpdate = findViewById(R.id.btn_update);
        btnUpdate.setOnClickListener(v -> checkForUpdate(true));
    }

    @Override
    protected void onResume() {
        super.onResume();
        MonitorService.addListener(this);
        Logx.addSink(this);
        updateRestoreLabel();
        maybeAutoCheckUpdate();
        GuardEngine.Status s = MonitorService.lastStatus();
        if (s != null) {
            onStatus(s);
        } else if (!Prefs.enabled(this)) {
            tvState.setText("未启用");
            tvDetail.setText("打开下面的开关开始守护");
        }
        renderLog();
    }

    @Override
    protected void onPause() {
        MonitorService.removeListener(this);
        Logx.removeSink(this);
        super.onPause();
    }

    // ------------------------------------------------------------------

    private void loadSettings() {
        suppress = true;
        swEnable.setChecked(Prefs.enabled(this));
        swDisconnect.setChecked(Prefs.disconnectOnFail(this));
        swRoot.setChecked(Prefs.preferRoot(this));
        etGrace.setText(String.valueOf(Prefs.graceSec(this)));
        etWait.setText(String.valueOf(Prefs.waitSec(this)));
        suppress = false;
    }

    private void wire() {
        swEnable.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (suppress) return;
            applySettings();
            Prefs.setEnabled(this, isChecked);
            if (isChecked) {
                requestNeededPermissions();
                try {
                    MonitorService.start(this);
                    tvState.setText("正在启动");
                    tvDetail.setText("监控服务启动中…");
                } catch (Throwable t) {
                    toast("启动失败: " + t);
                }
            } else {
                MonitorService.stop(this);
                tvState.setText("已停止");
                tvDetail.setText("监控已关闭");
                tvSsid.setText("SSID: —");
                tvMeta.setText("");
                ((GradientDrawable) dot.getBackground()).setColor(0xFF94A3B8);
            }
        });

        swDisconnect.setOnCheckedChangeListener((b, v) -> {
            if (!suppress) applySettings();
        });
        swRoot.setOnCheckedChangeListener((b, v) -> {
            if (!suppress) applySettings();
        });
    }

    private void applySettings() {
        int grace = parseInt(etGrace.getText().toString(), Prefs.DEF_GRACE_SEC);
        int wait = parseInt(etWait.getText().toString(), Prefs.DEF_WAIT_SEC);
        grace = Math.max(1, Math.min(120, grace));
        wait = Math.max(2, Math.min(300, wait));
        etGrace.setText(String.valueOf(grace));
        etWait.setText(String.valueOf(wait));
        Prefs.save(this, Prefs.enabled(this), grace, wait,
                swDisconnect.isChecked(), swRoot.isChecked());
    }

    private int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable t) {
            return def;
        }
    }

    private void requestNeededPermissions() {
        List<String> need = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (!need.isEmpty()) {
            try {
                requestPermissions(need.toArray(new String[0]), REQ_PERMS);
            } catch (Throwable ignored) {
            }
        }
    }

    private void openWifiSettings() {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startActivity(new Intent("android.settings.panel.action.WIFI"));
            } else {
                startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
            }
        } catch (Throwable t) {
            try {
                startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
            } catch (Throwable ignored) {
                toast("无法打开 WiFi 设置");
            }
        }
    }

    /** 把被断开的网络重新启用（断开时 disableNetwork 过，需要显式恢复）。 */
    private void restoreNetwork() {
        int netId = Prefs.disabledNetId(this);
        if (netId < 0) {
            toast("没有待恢复的网络，打开 WiFi 设置手动连接");
            openWifiSettings();
            return;
        }
        boolean ok = false;
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            ok = wm != null && wm.enableNetwork(netId, true) && wm.reconnect();
        } catch (Throwable ignored) {
        }
        if (ok) {
            Prefs.clearDisabled(this);
            toast("已恢复该 WiFi 的自动连接");
            if (Prefs.enabled(this)) MonitorService.checkNow(this);
        } else {
            toast("恢复失败，请在 WiFi 设置里手动点一下该网络");
        }
        updateRestoreLabel();
    }

    private void updateRestoreLabel() {
        if (btnRestore == null) return;
        if (Prefs.disabledNetId(this) < 0) {
            btnRestore.setText("恢复连接");
            return;
        }
        String ssid = Prefs.disabledSsid(this);
        btnRestore.setText(ssid == null || ssid.isEmpty() ? "恢复连接" : "恢复连接（" + ssid + "）");
    }

    // ---------------- 检查更新 ----------------

    private void maybeAutoCheckUpdate() {
        if (!Config.updateSourceReady()) return;
        if (System.currentTimeMillis() - Prefs.lastUpdateCheck(this) < Config.AUTO_CHECK_INTERVAL_MS) return;
        Prefs.setLastUpdateCheck(this, System.currentTimeMillis());
        checkForUpdate(false);
    }

    private void checkForUpdate(final boolean manual) {
        if (!Config.updateSourceReady()) {
            if (manual) toast("更新源未配置：改 Config.java 里的 GITHUB_REPO 后重新打包");
            return;
        }
        if (manual) toast("正在检查更新…");
        UpdateChecker.checkAsync(this, (error, info) -> runOnUiThread(() -> {
            if (isFinishing()) return;
            if (error != null) {
                if (manual) toast(error);
            } else if (info == null) {
                if (manual) toast("已是最新版本 v" + UpdateChecker.currentVersionName(this));
            } else {
                showUpdateDialog(info);
            }
        }));
    }

    private void showUpdateDialog(final UpdateChecker.Info info) {
        String notes = info.notes == null ? "" : info.notes.trim();
        if (notes.length() > 600) notes = notes.substring(0, 600) + "…";
        String msg = "当前 v" + UpdateChecker.currentVersionName(this)
                + " → 新版本 v" + info.versionName
                + (notes.isEmpty() ? "" : "\n\n" + notes);
        new AlertDialog.Builder(this)
                .setTitle("发现新版本")
                .setMessage(msg)
                .setPositiveButton("下载并安装", (d, w) -> downloadAndInstall(info))
                .setNegativeButton("以后再说", null)
                .show();
    }

    private void downloadAndInstall(final UpdateChecker.Info info) {
        if (!ApkInstaller.canInstall(this)) {
            new AlertDialog.Builder(this)
                    .setTitle("需要授权")
                    .setMessage("安装更新要允许本应用“安装未知应用”。点“去授权”打开开关，"
                            + "回来后再点一次“下载并安装”即可。")
                    .setPositiveButton("去授权", (d, w) -> ApkInstaller.openUnknownSourcesSetting(this))
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        downloadDialog = new ProgressDialog(this);
        downloadDialog.setMessage("正在下载 v" + info.versionName + "…");
        downloadDialog.setCancelable(false);
        downloadDialog.show();
        UpdateChecker.downloadAsync(this, info.url, (error, file) -> runOnUiThread(() -> {
            if (downloadDialog != null && downloadDialog.isShowing()) downloadDialog.dismiss();
            downloadDialog = null;
            if (error != null) {
                toast(error);
                return;
            }
            Prefs.setPendingUpdate(MainActivity.this, info.versionCode);
            if (!ApkInstaller.install(MainActivity.this)) {
                toast("调起安装器失败，请到文件管理器手动安装");
            }
        }));
    }

    private void requestIgnoreBattery() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
            toast("已经在忽略电池优化");
            return;
        }
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Throwable t) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Throwable t2) {
                toast("请手动在电池设置中允许后台运行");
            }
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------

    @Override
    public void onStatus(GuardEngine.Status s) {
        tvState.setText(s.title);
        tvDetail.setText(s.detail);
        tvSsid.setText("SSID: " + (TextUtils.isEmpty(s.ssid) ? "—（未连接或缺少定位权限）" : s.ssid));
        StringBuilder meta = new StringBuilder();
        meta.append("累计重连 ").append(s.reconnects).append(" 次");
        meta.append(" · 断开 ").append(s.disconnects).append(" 次");
        meta.append(" · ").append(s.root ? "已有 root" : "无 root");
        if (s.online) meta.append(" · 链路已通");
        int dog = Prefs.watchdogRestarts(this);
        meta.append(" · 看门狗").append(dog > 0 ? "拉起过 " + dog + " 次" : "已布防");
        meta.append(" · v").append(UpdateChecker.currentVersionName(this));
        tvMeta.setText(meta.toString());
        ((GradientDrawable) dot.getBackground()).setColor(colorFor(s.state));
    }

    private int colorFor(GuardEngine.State st) {
        switch (st) {
            case IDLE:
                return 0xFF16A34A;
            case SUSPECT:
            case RECONNECTING:
            case DISCONNECTING:
                return 0xFFEA580C;
            case DISCONNECTED:
            case MANUAL:
                return 0xFFDC2626;
            case WAITING:
                return 0xFF0EA5E9;
            default:
                return 0xFF94A3B8;
        }
    }

    @Override
    public void onLine(final String line) {
        renderLog();
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void renderLog() {
        List<String> lines = Logx.snapshot();
        StringBuilder sb = new StringBuilder();
        int from = Math.max(0, lines.size() - 300);
        for (int i = from; i < lines.size(); i++) {
            sb.append(lines.get(i)).append('\n');
        }
        tvLog.setText(sb.toString());
    }
}
