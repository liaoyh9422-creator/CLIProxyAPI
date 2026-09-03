package com.cliproxy.app;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.cliproxy.app.config.AppConfig;
import com.cliproxy.app.tabs.AboutTab;
import com.cliproxy.app.tabs.MetricsTab;
import com.cliproxy.app.tabs.ServiceTab;
import com.cliproxy.app.tabs.TailscaleTab;
import com.cliproxy.app.tabs.TunnelTab;
import com.cliproxy.app.ui.UiTheme;
import com.cliproxy.app.ui.WebPanelManager;
import com.cliproxy.core.download.BinaryDownloadManager;
import com.cliproxy.core.proxy.ProxyTester;
import com.cliproxy.core.service.ProxyService;
import com.cliproxy.core.cache.SmartCacheProxy;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * MainActivity: CLIProxyAPI 宿主主入口与全局调度中心
 * 
 * 架构规范：
 * 1. 宿主 Activity 负责全局生命周期、沉浸式状态栏、权限请求与系统服务绑定
 * 2. 5 个业务 Tab 模块彻底拆分为独立文件：
 *    - ServiceTab: Tab 0 服务控制台与 127.0.0.1 轮播端点
 *    - TunnelTab: Tab 1 外网穿透控制台 (Cloudflare + 外网共享API管理)
 *    - TailscaleTab: Tab 2 Tailscale 虚拟局域网控制台
 *    - MetricsTab: Tab 3 流量与运维监控、智能缓存5ms秒回与审计详情
 *    - AboutTab: Tab 4 关于软件界面
 * 3. 统一全局后台服务调度与日志分发
 */
public class MainActivity extends Activity {
    private static final String TAG = "MainActivity";

    private AppConfig appConfig;
    private SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private View mainRootView;
    private WebPanelManager webPanelManager;

    // 5 个 Tab 页面组件引用
    private int currentTab = 0;
    private ServiceTab serviceTab;
    private TunnelTab tunnelTab;
    private TailscaleTab tailscaleTab;
    private MetricsTab metricsTab;
    private AboutTab aboutTab;

    private View pageService;
    private View pageTunnel;
    private View pageTailscale;
    private View pageMetrics;
    private View pageAbout;

    private LinearLayout tabServicePill, tabTunnelPill, tabTailscalePill, tabMetricsPill, tabAboutPill;
    private ImageView tabServiceIcon, tabTunnelIcon, tabTailscaleIcon, tabMetricsIcon, tabAboutIcon;

    private boolean isServerRunning = false;
    private String currentTunnelDomain = "";

    // 端点轮播下标
    private int lanEndpointIndex = 0;
    private int tunnelEndpointIndex = 0;

    // ==================== 1. 生命周期与权限配置 ====================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupImmersiveStatusBar();

        appConfig = new AppConfig(this);
        prefs = getSharedPreferences(AppConfig.PREFS_NAME, MODE_PRIVATE);
        webPanelManager = new WebPanelManager(this, () -> setContentView(mainRootView));

        setupUI();

        initGuestPolicies();

        prefs.edit().putBoolean("running", false)
                    .putBoolean("tunnel_running", false)
                    .putBoolean("tailscale_running", false)
                    .apply();
        updateUI(false);
        updateTunnelUI(false);
        updateTailscaleUI(false, "");
        loadExistingLogs();

        requestNotificationPermission();
        requestIgnoreBatteryOptimization();
        requestManageStoragePermission();

        startPeriodicMetricsUpdater();
    }

    private void setupImmersiveStatusBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Window window = getWindow();
            window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.setStatusBarColor(Color.parseColor(UiTheme.C_BG));
            window.setNavigationBarColor(Color.parseColor(UiTheme.C_SURFACE));
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            View decor = getWindow().getDecorView();
            decor.setSystemUiVisibility(decor.getSystemUiVisibility() & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1001);
            }
        }
    }

    private void requestIgnoreBatteryOptimization() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            }
        } catch (Exception e) {
            Log.w(TAG, "电池优化豁免申请失败: " + e.getMessage());
        }
    }

    private void requestManageStoragePermission() {
        if (Build.VERSION.SDK_INT >= 30) {
            if (!android.os.Environment.isExternalStorageManager()) {
                Toast.makeText(this, "需要「所有文件访问」权限以保存配置到 Download 目录", Toast.LENGTH_LONG).show();
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } catch (Exception ignored) {}
            }
        }
    }

    // ==================== 2. 主界面布局与 5-Tab 导航 ====================

    private void setupUI() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor(UiTheme.C_BG));
        root.setFitsSystemWindows(true);

        FrameLayout contentContainer = new FrameLayout(this);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        contentContainer.setLayoutParams(clp);

        // Tab 0: 服务页面
        serviceTab = new ServiceTab(this);
        pageService = serviceTab.createView();
        contentContainer.addView(pageService);

        // Tab 1: 外网穿透页面
        tunnelTab = new TunnelTab(this);
        pageTunnel = tunnelTab.createView();
        pageTunnel.setVisibility(View.GONE);
        contentContainer.addView(pageTunnel);

        // Tab 2: Tailscale 虚拟内网页面
        tailscaleTab = new TailscaleTab(this);
        pageTailscale = tailscaleTab.createView();
        pageTailscale.setVisibility(View.GONE);
        contentContainer.addView(pageTailscale);

        // Tab 3: 流量与运维仪表盘页面
        metricsTab = new MetricsTab(this);
        pageMetrics = metricsTab.createView();
        pageMetrics.setVisibility(View.GONE);
        contentContainer.addView(pageMetrics);

        // Tab 4: 关于页面
        aboutTab = new AboutTab(this);
        pageAbout = aboutTab.createView();
        pageAbout.setVisibility(View.GONE);
        contentContainer.addView(pageAbout);

        root.addView(contentContainer);
        root.addView(buildBottomBar());

        mainRootView = root;
        setContentView(mainRootView);
    }

    private View buildBottomBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Color.parseColor(UiTheme.C_SURFACE));
        bar.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UiTheme.dp(this, 50)));

        // Tab 0: 服务
        LinearLayout tab0 = new LinearLayout(this);
        tab0.setGravity(Gravity.CENTER);
        tab0.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        tabServicePill = new LinearLayout(this);
        tabServicePill.setGravity(Gravity.CENTER);
        tabServicePill.setLayoutParams(new LinearLayout.LayoutParams(UiTheme.dp(this, 46), UiTheme.dp(this, 26)));
        tabServiceIcon = new ImageView(this);
        tabServiceIcon.setImageResource(R.drawable.ic_tab_service);
        tabServiceIcon.setLayoutParams(new ViewGroup.LayoutParams(UiTheme.dp(this, 19), UiTheme.dp(this, 19)));
        tabServicePill.addView(tabServiceIcon);
        tab0.addView(tabServicePill);
        tab0.setOnClickListener(v -> selectTab(0));
        bar.addView(tab0);

        // Tab 1: 外网
        LinearLayout tab1 = new LinearLayout(this);
        tab1.setGravity(Gravity.CENTER);
        tab1.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        tabTunnelPill = new LinearLayout(this);
        tabTunnelPill.setGravity(Gravity.CENTER);
        tabTunnelPill.setLayoutParams(new LinearLayout.LayoutParams(UiTheme.dp(this, 46), UiTheme.dp(this, 26)));
        tabTunnelIcon = new ImageView(this);
        tabTunnelIcon.setImageResource(R.drawable.ic_tab_tunnel);
        tabTunnelIcon.setLayoutParams(new ViewGroup.LayoutParams(UiTheme.dp(this, 19), UiTheme.dp(this, 19)));
        tabTunnelPill.addView(tabTunnelIcon);
        tab1.addView(tabTunnelPill);
        tab1.setOnClickListener(v -> selectTab(1));
        bar.addView(tab1);

        // Tab 2: 内网 (Tailscale)
        LinearLayout tab2 = new LinearLayout(this);
        tab2.setGravity(Gravity.CENTER);
        tab2.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        tabTailscalePill = new LinearLayout(this);
        tabTailscalePill.setGravity(Gravity.CENTER);
        tabTailscalePill.setLayoutParams(new LinearLayout.LayoutParams(UiTheme.dp(this, 46), UiTheme.dp(this, 26)));
        tabTailscaleIcon = new ImageView(this);
        tabTailscaleIcon.setImageResource(R.drawable.ic_tab_tailscale);
        tabTailscaleIcon.setLayoutParams(new ViewGroup.LayoutParams(UiTheme.dp(this, 19), UiTheme.dp(this, 19)));
        tabTailscalePill.addView(tabTailscaleIcon);
        tab2.addView(tabTailscalePill);
        tab2.setOnClickListener(v -> selectTab(2));
        bar.addView(tab2);

        // Tab 3: 仪表 (Metrics)
        LinearLayout tab3 = new LinearLayout(this);
        tab3.setGravity(Gravity.CENTER);
        tab3.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        tabMetricsPill = new LinearLayout(this);
        tabMetricsPill.setGravity(Gravity.CENTER);
        tabMetricsPill.setLayoutParams(new LinearLayout.LayoutParams(UiTheme.dp(this, 46), UiTheme.dp(this, 26)));
        tabMetricsIcon = new ImageView(this);
        tabMetricsIcon.setImageResource(R.drawable.ic_tab_metrics);
        tabMetricsIcon.setLayoutParams(new ViewGroup.LayoutParams(UiTheme.dp(this, 19), UiTheme.dp(this, 19)));
        tabMetricsPill.addView(tabMetricsIcon);
        tab3.addView(tabMetricsPill);
        tab3.setOnClickListener(v -> selectTab(3));
        bar.addView(tab3);

        // Tab 4: 关于 (About)
        LinearLayout tab4 = new LinearLayout(this);
        tab4.setGravity(Gravity.CENTER);
        tab4.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        tabAboutPill = new LinearLayout(this);
        tabAboutPill.setGravity(Gravity.CENTER);
        tabAboutPill.setLayoutParams(new LinearLayout.LayoutParams(UiTheme.dp(this, 46), UiTheme.dp(this, 26)));
        tabAboutIcon = new ImageView(this);
        tabAboutIcon.setImageResource(R.drawable.ic_tab_about);
        tabAboutIcon.setLayoutParams(new ViewGroup.LayoutParams(UiTheme.dp(this, 19), UiTheme.dp(this, 19)));
        tabAboutPill.addView(tabAboutIcon);
        tab4.addView(tabAboutPill);
        tab4.setOnClickListener(v -> selectTab(4));
        bar.addView(tab4);

        updateTabIcons(0);
        return bar;
    }

    private void selectTab(int index) {
        currentTab = index;
        pageService.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        pageTunnel.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        pageTailscale.setVisibility(index == 2 ? View.VISIBLE : View.GONE);
        pageMetrics.setVisibility(index == 3 ? View.VISIBLE : View.GONE);
        pageAbout.setVisibility(index == 4 ? View.VISIBLE : View.GONE);
        updateTabIcons(index);

        if (index == 3 && metricsTab != null) {
            metricsTab.refreshMetricsView();
        }
    }

    private void updateTabIcons(int activeIndex) {
        boolean a0 = (activeIndex == 0);
        tabServiceIcon.setColorFilter(Color.parseColor(a0 ? UiTheme.C_BLUE : UiTheme.C_DIM));
        tabServicePill.setBackground(a0 ? UiTheme.roundRect(this, "#1F2B3E", null, 0, 13) : null);

        boolean a1 = (activeIndex == 1);
        tabTunnelIcon.setColorFilter(Color.parseColor(a1 ? UiTheme.C_BLUE : UiTheme.C_DIM));
        tabTunnelPill.setBackground(a1 ? UiTheme.roundRect(this, "#1F2B3E", null, 0, 13) : null);

        boolean a2 = (activeIndex == 2);
        tabTailscaleIcon.setColorFilter(Color.parseColor(a2 ? UiTheme.C_BLUE : UiTheme.C_DIM));
        tabTailscalePill.setBackground(a2 ? UiTheme.roundRect(this, "#1F2B3E", null, 0, 13) : null);

        boolean a3 = (activeIndex == 3);
        tabMetricsIcon.setColorFilter(Color.parseColor(a3 ? UiTheme.C_BLUE : UiTheme.C_DIM));
        tabMetricsPill.setBackground(a3 ? UiTheme.roundRect(this, "#1F2B3E", null, 0, 13) : null);

        boolean a4 = (activeIndex == 4);
        tabAboutIcon.setColorFilter(Color.parseColor(a4 ? UiTheme.C_BLUE : UiTheme.C_DIM));
        tabAboutPill.setBackground(a4 ? UiTheme.roundRect(this, "#1F2B3E", null, 0, 13) : null);
    }

    // ==================== 3. 轮换定时器与客用配额同步 ====================

    private void startPeriodicMetricsUpdater() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                // 1. 每 3 秒自动轮换变换请求端点
                lanEndpointIndex = (lanEndpointIndex + 1) % 3;
                tunnelEndpointIndex = (tunnelEndpointIndex + 1) % 3;
                if (serviceTab != null) serviceTab.updateCyclingEndpoint(lanEndpointIndex);
                if (tunnelTab != null) tunnelTab.updateCyclingEndpoint(tunnelEndpointIndex);

                // 2. 如果当前在仪表 Tab，刷新指标数据
                if (currentTab == 3 && metricsTab != null) {
                    metricsTab.refreshMetricsView();
                }

                handler.postDelayed(this, 3000);
            }
        }, 3000);
    }

    public void syncGuestKeysToProxyAndConfig() {
        String json = prefs.getString("guest_keys_json", "[]");
        JSONArray arr;
        try { arr = new JSONArray(json); } catch (Exception e) { arr = new JSONArray(); }
        List<SmartCacheProxy.GuestPolicy> policies = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject obj = arr.optJSONObject(i);
            if (obj != null) {
                String k = obj.optString("key");
                if (!k.isEmpty()) {
                    keys.add(k);
                    policies.add(new SmartCacheProxy.GuestPolicy(
                            k,
                            obj.optString("remark", "访客"),
                            obj.optString("mode", "TOKEN"),
                            obj.optLong("quotaRemaining", 10_000_000L),
                            obj.optLong("quotaTotal", 10_000_000L),
                            obj.optInt("rpm", 5)
                    ));
                }
            }
        }
        SmartCacheProxy.setGuestPolicies(policies);

        new Thread(() -> {
            try {
                File configFile = getSecureConfigFile();
                if (configFile.exists()) {
                    FileInputStream fis = new FileInputStream(configFile);
                    byte[] b = new byte[(int) configFile.length()];
                    fis.read(b);
                    fis.close();
                    String yaml = new String(b, StandardCharsets.UTF_8);

                    int idx = yaml.indexOf("api-keys:");
                    if (idx != -1) {
                        int nextSection = yaml.indexOf("\n\n", idx);
                        if (nextSection == -1) nextSection = yaml.length();

                        StringBuilder sb = new StringBuilder();
                        sb.append("api-keys:\n");
                        String masterKey = appConfig.getApiKey();
                        if (masterKey != null && !masterKey.isEmpty()) {
                            sb.append("  - \"").append(masterKey).append("\"\n");
                        }
                        for (String gk : keys) {
                            sb.append("  - \"").append(gk).append("\"\n");
                        }

                        String newYaml = yaml.substring(0, idx) + sb.toString() + yaml.substring(nextSection);
                        FileOutputStream fos = new FileOutputStream(configFile);
                        fos.write(newYaml.getBytes(StandardCharsets.UTF_8));
                        fos.flush();
                        fos.close();
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to write guest keys to config.yaml", e);
            }
        }).start();
    }

    public void initGuestPolicies() {
        syncGuestKeysToProxyAndConfig();
    }

    public File getSecureConfigFile() {
        File configFile = new File(getFilesDir(), "config.yaml");
        if (!configFile.exists() || configFile.length() == 0) {
            try {
                File oldExternalDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "CLIProxyAPI");
                File oldConfigFile = new File(oldExternalDir, "config.yaml");
                if (oldConfigFile.exists() && oldConfigFile.length() > 0) {
                    FileInputStream in = new FileInputStream(oldConfigFile);
                    FileOutputStream out = new FileOutputStream(configFile);
                    byte[] buf = new byte[4096];
                    int len;
                    while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
                    in.close();
                    out.close();
                }
            } catch (Exception ignored) {}
        }
        return configFile;
    }

    // ==================== 4. 服务控制 (CLIProxy, Tunnel, Tailscale) ====================

    private void showBinaryDownloadDialog(String compName, String compKey, Runnable onSuccessAction) {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(false);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackground(UiTheme.roundRect(this, UiTheme.C_SURFACE, UiTheme.C_BORDER, 1, 10));
        layout.setPadding(UiTheme.dp(this, 18), UiTheme.dp(this, 18), UiTheme.dp(this, 18), UiTheme.dp(this, 18));

        TextView title = new TextView(this);
        title.setText("📦 组件按需下载 · " + compName);
        title.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        title.setTextSize(15f);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        title.setPadding(0, 0, 0, UiTheme.dp(this, 8));
        layout.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("当前为 Lite 极速轻量安装包，底层核心二进制将从国内 Gitee 镜像高速下载并自动配置。");
        subtitle.setTextColor(Color.parseColor(UiTheme.C_DIM));
        subtitle.setTextSize(12f);
        subtitle.setPadding(0, 0, 0, UiTheme.dp(this, 14));
        layout.addView(subtitle);

        ProgressBar progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        progressBar.setIndeterminate(false);
        layout.addView(progressBar);

        TextView tvProgress = new TextView(this);
        tvProgress.setText("准备连接下载节点...");
        tvProgress.setTextColor(Color.parseColor(UiTheme.C_BLUE));
        tvProgress.setTextSize(11f);
        tvProgress.setPadding(0, UiTheme.dp(this, 8), 0, UiTheme.dp(this, 4));
        layout.addView(tvProgress);

        TextView tvStatus = new TextView(this);
        tvStatus.setText("正在连接 Gitee 节点...");
        tvStatus.setTextColor(Color.parseColor(UiTheme.C_DIM));
        tvStatus.setTextSize(10f);
        tvStatus.setPadding(0, 0, 0, UiTheme.dp(this, 12));
        layout.addView(tvStatus);

        LinearLayout btnBar = new LinearLayout(this);
        btnBar.setOrientation(LinearLayout.HORIZONTAL);
        btnBar.setGravity(Gravity.RIGHT);

        TextView btnCancel = UiTheme.createButton(this, "取消", UiTheme.C_DIM, "#252B33", UiTheme.C_BORDER, 5);
        btnCancel.setOnClickListener(v -> dialog.dismiss());
        btnBar.addView(btnCancel);
        layout.addView(btnBar);

        dialog.setContentView(layout);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.88f), WindowManager.LayoutParams.WRAP_CONTENT);
            w.setBackgroundDrawableResource(android.R.color.transparent);
        }
        dialog.show();

        BinaryDownloadManager.downloadComponent(this, compKey, new BinaryDownloadManager.DownloadListener() {
            @Override
            public void onProgress(long bytesRead, long totalBytes, int percent, double speedMBs) {
                handler.post(() -> {
                    progressBar.setProgress(percent);
                    String readStr = String.format(Locale.getDefault(), "%.1f", bytesRead / 1024.0 / 1024.0);
                    String totalStr = totalBytes > 0 ? String.format(Locale.getDefault(), "%.1f", totalBytes / 1024.0 / 1024.0) : "未知";
                    String speedStr = String.format(Locale.getDefault(), "%.1f", speedMBs);
                    tvProgress.setText("已下载: " + readStr + " MB / " + totalStr + " MB (" + percent + "%) · " + speedStr + " MB/s");
                });
            }

            @Override
            public void onStatus(String statusText) {
                handler.post(() -> tvStatus.setText(statusText));
            }

            @Override
            public void onSuccess(File targetDir) {
                handler.post(() -> {
                    Toast.makeText(MainActivity.this, "✅ " + compName + " 下载并就绪！", Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                    if (onSuccessAction != null) {
                        onSuccessAction.run();
                    }
                });
            }

            @Override
            public void onFailure(String errorMessage) {
                handler.post(() -> {
                    tvStatus.setText("❌ 下载失败: " + errorMessage);
                    tvStatus.setTextColor(Color.parseColor(UiTheme.C_RED));
                    btnCancel.setText("关闭");
                    Toast.makeText(MainActivity.this, "下载失败: " + errorMessage, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    public void startProxyServer() {
        if (!BinaryDownloadManager.isGlibcReady(this)) {
            showBinaryDownloadDialog("Glibc 基础运行库", BinaryDownloadManager.KEY_GLIBC, this::startProxyServer);
            return;
        }
        if (!BinaryDownloadManager.isCliproxyReady(this)) {
            showBinaryDownloadDialog("CLIProxy 核心代理", BinaryDownloadManager.KEY_CLIPROXY, this::startProxyServer);
            return;
        }
        ProxyService.start(this);
        prefs.edit().putBoolean("running", true).apply();
        updateUI(true);
        startLogReader();
    }

    public void stopProxyServer() {
        Intent intent = new Intent(this, ProxyService.class);
        intent.setAction(ProxyService.ACTION_STOP_SERVER);
        startService(intent);
        prefs.edit().putBoolean("running", false).apply();
        updateUI(false);
    }

    public void checkHealth() {
        new Thread(() -> {
            try {
                URL url = new URL("http://localhost:" + appConfig.getPort() + "/");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(2000);
                conn.setReadTimeout(2000);
                int code = conn.getResponseCode();
                handler.post(() -> Toast.makeText(this, "端口响应正常: HTTP " + code, Toast.LENGTH_SHORT).show());
                conn.disconnect();
            } catch (Exception e) {
                handler.post(() -> Toast.makeText(this, "服务未响应: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    public void openWebPanel(String url) {
        View panelView = webPanelManager.createWebPanelView(url);
        setContentView(panelView);
    }

    public void startTunnel() {
        if (!BinaryDownloadManager.isGlibcReady(this)) {
            showBinaryDownloadDialog("Glibc 基础运行库", BinaryDownloadManager.KEY_GLIBC, this::startTunnel);
            return;
        }
        if (!BinaryDownloadManager.isCloudflaredReady(this)) {
            showBinaryDownloadDialog("Cloudflare 穿透组件", BinaryDownloadManager.KEY_CLOUDFLARED, this::startTunnel);
            return;
        }
        Intent intent = new Intent(this, ProxyService.class);
        intent.setAction(ProxyService.ACTION_START_TUNNEL);
        intent.putExtra(ProxyService.EXTRA_TUNNEL_MODE, "cloudflare");
        intent.putExtra(ProxyService.EXTRA_SERVER_PORT, appConfig.getPort());
        startService(intent);
        prefs.edit().putBoolean("tunnel_running", true).apply();
        updateTunnelUI(true);
        startTunnelLogReader();
    }

    public void stopTunnel() {
        Intent intent = new Intent(this, ProxyService.class);
        intent.setAction(ProxyService.ACTION_STOP_TUNNEL);
        startService(intent);
        prefs.edit().putBoolean("tunnel_running", false).apply();
        updateTunnelUI(false);
    }

    public void startTailscale(String authKey, String hostname) {
        if (!BinaryDownloadManager.isTailscaleReady(this)) {
            showBinaryDownloadDialog("Tailscale 组网组件", BinaryDownloadManager.KEY_TAILSCALE, () -> startTailscale(authKey, hostname));
            return;
        }
        prefs.edit().putString("ts_auth_key", authKey)
                    .putString("ts_hostname", hostname)
                    .putBoolean("tailscale_running", true)
                    .apply();

        Intent intent = new Intent(this, ProxyService.class);
        intent.setAction(ProxyService.ACTION_START_TAILSCALE);
        intent.putExtra(ProxyService.EXTRA_TAILSCALE_AUTHKEY, authKey);
        intent.putExtra(ProxyService.EXTRA_TAILSCALE_HOSTNAME, hostname);
        startService(intent);

        updateTailscaleUI(true, "");
        startTailscaleLogReader();
    }

    public void stopTailscale() {
        Intent intent = new Intent(this, ProxyService.class);
        intent.setAction(ProxyService.ACTION_STOP_TAILSCALE);
        startService(intent);
        prefs.edit().putBoolean("tailscale_running", false).apply();
        updateTailscaleUI(false, "");
    }

    private void updateUI(boolean running) {
        this.isServerRunning = running;
        if (serviceTab != null) {
            serviceTab.updateServiceUI(running);
        }
    }

    private void updateTunnelUI(boolean running) {
        if (!running) {
            currentTunnelDomain = "";
        }
        if (tunnelTab != null) {
            tunnelTab.updateTunnelUI(running);
        }
        if (serviceTab != null) {
            serviceTab.refreshAddressSection();
        }
    }

    private void updateTailscaleUI(boolean running, String ip) {
        if (tailscaleTab != null) {
            tailscaleTab.updateTailscaleUI(running, ip);
        }
    }

    public void showOutboundProxyDialog() {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(UiTheme.roundRect(this, UiTheme.C_BG, UiTheme.C_BORDER, 1, 8));
        root.setPadding(UiTheme.dp(this, 16), UiTheme.dp(this, 14), UiTheme.dp(this, 16), UiTheme.dp(this, 14));

        TextView tvTitle = new TextView(this);
        tvTitle.setText("配置出站代理 (Outbound Proxy)");
        tvTitle.setTextSize(14);
        tvTitle.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(tvTitle);

        TextView tvSub = new TextView(this);
        tvSub.setText("解决海外 AI 厂商访问受限与穿透连接超时");
        tvSub.setTextSize(10.5f);
        tvSub.setTextColor(Color.parseColor(UiTheme.C_DIM));
        tvSub.setPadding(0, UiTheme.dp(this, 2), 0, UiTheme.dp(this, 10));
        root.addView(tvSub);

        final boolean[] proxyEnabledHolder = {prefs.getBoolean("outbound_proxy_enabled", false)};

        LinearLayout switchRow = new LinearLayout(this);
        switchRow.setOrientation(LinearLayout.HORIZONTAL);
        switchRow.setGravity(Gravity.CENTER_VERTICAL);
        switchRow.setPadding(0, UiTheme.dp(this, 2), 0, UiTheme.dp(this, 6));

        TextView tvSwitchLabel = new TextView(this);
        tvSwitchLabel.setText("启用出站代理: ");
        tvSwitchLabel.setTextSize(12);
        tvSwitchLabel.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        tvSwitchLabel.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        switchRow.addView(tvSwitchLabel);

        TextView btnToggle = new TextView(this);
        btnToggle.setTextSize(11);
        btnToggle.setTypeface(Typeface.DEFAULT_BOLD);
        btnToggle.setPadding(UiTheme.dp(this, 10), UiTheme.dp(this, 4), UiTheme.dp(this, 10), UiTheme.dp(this, 4));

        Runnable updateToggleBtn = () -> {
            boolean en = proxyEnabledHolder[0];
            btnToggle.setText(en ? "● 已启用" : "○ 已禁用");
            btnToggle.setTextColor(Color.parseColor(en ? UiTheme.C_GREEN : UiTheme.C_DIM));
            btnToggle.setBackground(UiTheme.roundRect(this, en ? "#0D2818" : UiTheme.C_SURFACE_ALT,
                    en ? UiTheme.C_GREEN : UiTheme.C_BORDER, 1, 4));
        };
        updateToggleBtn.run();
        btnToggle.setOnClickListener(v -> {
            proxyEnabledHolder[0] = !proxyEnabledHolder[0];
            updateToggleBtn.run();
        });
        switchRow.addView(btnToggle);
        root.addView(switchRow);

        TextView tvUrlLabel = new TextView(this);
        tvUrlLabel.setText("代理监听地址 (HTTP / SOCKS5):");
        tvUrlLabel.setTextSize(11);
        tvUrlLabel.setTextColor(Color.parseColor(UiTheme.C_BLUE));
        tvUrlLabel.setPadding(0, UiTheme.dp(this, 4), 0, UiTheme.dp(this, 2));
        root.addView(tvUrlLabel);

        String currentUrl = prefs.getString("outbound_proxy_url", "http://127.0.0.1:7890");
        if (currentUrl.isEmpty()) currentUrl = "http://127.0.0.1:7890";
        EditText inputUrl = new EditText(this);
        inputUrl.setText(currentUrl);
        inputUrl.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        inputUrl.setTextSize(12.5f);
        inputUrl.setTypeface(Typeface.MONOSPACE);
        inputUrl.setBackground(UiTheme.roundRect(this, UiTheme.C_SURFACE, UiTheme.C_BORDER, 1, 4));
        inputUrl.setPadding(UiTheme.dp(this, 10), UiTheme.dp(this, 6), UiTheme.dp(this, 10), UiTheme.dp(this, 6));
        root.addView(inputUrl);

        LinearLayout presetRow = new LinearLayout(this);
        presetRow.setOrientation(LinearLayout.HORIZONTAL);
        presetRow.setPadding(0, UiTheme.dp(this, 4), 0, UiTheme.dp(this, 6));

        String[][] presets = new String[][]{
                {"7890 (Clash)", "http://127.0.0.1:7890"},
                {"10809 (v2ray)", "http://127.0.0.1:10809"},
                {"10808 (SOCKS)", "socks5://127.0.0.1:10808"}
        };
        for (String[] preset : presets) {
            TextView pBtn = UiTheme.createButton(this, preset[0], UiTheme.C_CYAN, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 3);
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            plp.rightMargin = UiTheme.dp(this, 4);
            pBtn.setLayoutParams(plp);
            pBtn.setTextSize(10);
            pBtn.setOnClickListener(v -> inputUrl.setText(preset[1]));
            presetRow.addView(pBtn);
        }
        root.addView(presetRow);

        LinearLayout testRow = new LinearLayout(this);
        testRow.setOrientation(LinearLayout.HORIZONTAL);
        testRow.setGravity(Gravity.CENTER_VERTICAL);
        testRow.setPadding(0, UiTheme.dp(this, 2), 0, UiTheme.dp(this, 4));

        TextView btnTest = UiTheme.createButton(this, "⚡ 测试连通性", UiTheme.C_YELLOW, "#2B240B", UiTheme.C_YELLOW, 3);
        testRow.addView(btnTest);

        TextView tvTestStatus = new TextView(this);
        tvTestStatus.setText(" ● 未测试");
        tvTestStatus.setTextSize(10.5f);
        tvTestStatus.setTextColor(Color.parseColor(UiTheme.C_DIM));
        tvTestStatus.setTypeface(Typeface.MONOSPACE);
        LinearLayout.LayoutParams tslp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tslp.leftMargin = UiTheme.dp(this, 6);
        tvTestStatus.setLayoutParams(tslp);
        testRow.addView(tvTestStatus);
        root.addView(testRow);

        btnTest.setOnClickListener(v -> {
            String testTarget = inputUrl.getText().toString().trim();
            if (testTarget.isEmpty()) {
                tvTestStatus.setText(" ● 请先输入代理地址");
                tvTestStatus.setTextColor(Color.parseColor(UiTheme.C_RED));
                return;
            }
            tvTestStatus.setText(" ● 正在连接探测...");
            tvTestStatus.setTextColor(Color.parseColor(UiTheme.C_CYAN));
            btnTest.setEnabled(false);

            ProxyTester.testProxy(testTarget, (success, cfLatency, aiLatency, msg) -> {
                btnTest.setEnabled(true);
                tvTestStatus.setText(msg);
                tvTestStatus.setTextColor(Color.parseColor(success ? UiTheme.C_GREEN : UiTheme.C_RED));
            });
        });

        TextView tvBypassLabel = new TextView(this);
        tvBypassLabel.setText("直连绕过名单 (NO_PROXY):");
        tvBypassLabel.setTextSize(11);
        tvBypassLabel.setTextColor(Color.parseColor(UiTheme.C_DIM));
        tvBypassLabel.setPadding(0, UiTheme.dp(this, 6), 0, UiTheme.dp(this, 2));
        root.addView(tvBypassLabel);

        String currentBypass = prefs.getString("outbound_proxy_bypass",
                "localhost,127.0.0.1,192.168.0.0/16,10.0.0.0/8,172.16.0.0/12,*.cn,*.gitee.com,gitee.com");
        EditText inputBypass = new EditText(this);
        inputBypass.setText(currentBypass);
        inputBypass.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        inputBypass.setTextSize(11);
        inputBypass.setTypeface(Typeface.MONOSPACE);
        inputBypass.setBackground(UiTheme.roundRect(this, UiTheme.C_SURFACE, UiTheme.C_BORDER, 1, 4));
        inputBypass.setPadding(UiTheme.dp(this, 8), UiTheme.dp(this, 6), UiTheme.dp(this, 8), UiTheme.dp(this, 6));
        inputBypass.setMinLines(2);
        inputBypass.setMaxLines(3);
        root.addView(inputBypass);

        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams arlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        arlp.topMargin = UiTheme.dp(this, 12);
        actionRow.setLayoutParams(arlp);

        TextView btnCancel = UiTheme.createButton(this, "取消", UiTheme.C_DIM, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 3);
        btnCancel.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        btnCancel.setOnClickListener(v -> dialog.dismiss());
        actionRow.addView(btnCancel);

        View spacer = new View(this);
        actionRow.addView(spacer, new LinearLayout.LayoutParams(UiTheme.dp(this, 8), 1));

        TextView btnSave = UiTheme.createButton(this, "保存配置", UiTheme.C_CYAN, "#0A2328", UiTheme.C_CYAN, 3);
        btnSave.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        btnSave.setOnClickListener(v -> {
            boolean en = proxyEnabledHolder[0];
            String u = inputUrl.getText().toString().trim();
            String bp = inputBypass.getText().toString().trim();

            prefs.edit()
                    .putBoolean("outbound_proxy_enabled", en)
                    .putString("outbound_proxy_url", u)
                    .putString("outbound_proxy_bypass", bp)
                    .apply();

            if (serviceTab != null) serviceTab.updateProxyBadge();
            if (tunnelTab != null) tunnelTab.updateTunnelProxyText();
            dialog.dismiss();

            if (isServerRunning) {
                Toast.makeText(this, "出站代理已更新，重启服务后完全生效", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "出站代理设置已保存", Toast.LENGTH_SHORT).show();
            }
        });
        actionRow.addView(btnSave);
        root.addView(actionRow);

        dialog.setContentView(root);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.92f), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
    }

    // ==================== 5. 日志监听线程与恢复 ====================

    private void startLogReader() {
        new Thread(() -> {
            try {
                File logFile = new File(getFilesDir(), "cliproxy.log");
                int wait = 0;
                while (!logFile.exists() && wait < 60) {
                    Thread.sleep(500);
                    wait++;
                }
                if (!logFile.exists()) return;

                BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(logFile)));
                String line;
                while (true) {
                    line = reader.readLine();
                    if (line != null) {
                        final String raw = line;
                        handler.post(() -> {
                            if (serviceTab != null) serviceTab.appendLog(raw);
                        });
                    } else {
                        Thread.sleep(300);
                    }
                }
            } catch (Exception ignored) {}
        }).start();
    }

    private void startTunnelLogReader() {
        new Thread(() -> {
            try {
                File logFile = new File(getFilesDir(), "tunnel.log");
                int wait = 0;
                while (!logFile.exists() && wait < 40) {
                    Thread.sleep(500);
                    wait++;
                }
                if (!logFile.exists()) return;

                BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(logFile)));
                String line;
                while (true) {
                    line = reader.readLine();
                    if (line != null) {
                        final String raw = line;
                        handler.post(() -> {
                            if (tunnelTab != null) tunnelTab.appendLog(raw);
                            if (raw.contains(".trycloudflare.com")) {
                                int s = raw.indexOf("https://");
                                if (s != -1) {
                                    int e = raw.indexOf(".trycloudflare.com", s);
                                    if (e != -1) {
                                        String domain = raw.substring(s, e + 17).trim();
                                        currentTunnelDomain = domain;
                                        if (tunnelTab != null) {
                                            tunnelTab.updateTunnelStatus("穿透成功 (已连通)", Color.parseColor(UiTheme.C_GREEN));
                                            tunnelTab.refreshTunnelEndpoints();
                                        }
                                        if (serviceTab != null) {
                                            serviceTab.refreshAddressSection();
                                        }
                                    }
                                }
                            }
                            if (raw.contains("穿透失败") || raw.contains("deadline exceeded")) {
                                if (tunnelTab != null) {
                                    tunnelTab.updateTunnelStatus("穿透失败 (连接超时)", Color.parseColor(UiTheme.C_RED));
                                }
                            }
                        });
                    } else {
                        Thread.sleep(300);
                    }
                }
            } catch (Exception ignored) {}
        }).start();
    }

    private void startTailscaleLogReader() {
        new Thread(() -> {
            try {
                File logFile = new File(getFilesDir(), "tailscale.log");
                int wait = 0;
                while (!logFile.exists() && wait < 40) {
                    Thread.sleep(500);
                    wait++;
                }
                if (!logFile.exists()) return;

                BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(logFile)));
                String line;
                while (true) {
                    line = reader.readLine();
                    if (line != null) {
                        final String raw = line;
                        handler.post(() -> {
                            if (tailscaleTab != null) tailscaleTab.appendLog(raw);
                            if (raw.contains("节点 IPv4: 100.")) {
                                int idx = raw.indexOf("100.");
                                String ip = raw.substring(idx).trim();
                                updateTailscaleUI(true, ip);
                            }
                        });
                    } else {
                        Thread.sleep(300);
                    }
                }
            } catch (Exception ignored) {}
        }).start();
    }

    private void loadExistingLogs() {
        File f = new File(getFilesDir(), "cliproxy.log");
        if (f.exists() && serviceTab != null) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f)))) {
                String line;
                while ((line = br.readLine()) != null) {
                    serviceTab.appendLog(line);
                }
            } catch (Exception ignored) {}
        }

        File tf = new File(getFilesDir(), "tunnel.log");
        if (tf.exists() && tunnelTab != null) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(tf)))) {
                String line;
                while ((line = br.readLine()) != null) {
                    tunnelTab.appendLog(line);
                }
            } catch (Exception ignored) {}
        }

        File tsf = new File(getFilesDir(), "tailscale.log");
        if (tsf.exists() && tailscaleTab != null) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(tsf)))) {
                String line;
                while ((line = br.readLine()) != null) {
                    tailscaleTab.appendLog(line);
                }
            } catch (Exception ignored) {}
        }
    }

    // ==================== 6. 全局对外暴露辅助方法 ====================

    public AppConfig getAppConfig() {
        return appConfig;
    }

    public SharedPreferences getPrefs() {
        return prefs;
    }

    public Handler getHandler() {
        return handler;
    }

    public boolean isServerRunning() {
        return isServerRunning;
    }

    public String getCurrentTunnelDomain() {
        return currentTunnelDomain;
    }

    public void copyToClipboard(String label, String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText(label, text));
            Toast.makeText(this, "已复制 " + label, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == WebPanelManager.FILE_CHOOSER_REQUEST_CODE) {
            if (webPanelManager != null) {
                webPanelManager.handleFileChooserResult(resultCode, data);
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (tunnelTab != null && tunnelTab.handleBackPressed()) {
            return;
        }
        if (webPanelManager != null && webPanelManager.handleBackPressed()) {
            return;
        }
        if (currentTab != 0) {
            selectTab(0);
            return;
        }
        super.onBackPressed();
    }
}
