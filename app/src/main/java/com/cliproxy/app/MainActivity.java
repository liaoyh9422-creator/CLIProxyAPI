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
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
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
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.cliproxy.app.config.AppConfig;
import com.cliproxy.app.ui.UiTheme;
import com.cliproxy.app.ui.WebPanelManager;
import com.cliproxy.core.download.BinaryDownloadManager;
import com.cliproxy.core.cache.ResponseCacheDb;
import com.cliproxy.core.cache.SmartCacheProxy;
import com.cliproxy.core.mdns.MdnsManager;
import com.cliproxy.core.metrics.MetricsTracker;
import com.cliproxy.core.proxy.ProxyTester;
import com.cliproxy.core.service.ProxyService;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import android.os.Environment;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * MainActivity: 宿主交互主界面
 * 架构：4-Tab 纯图标无外框矩阵（服务、外网、内网、仪表）
 * 包含：本地智能响应缓存（5ms 秒回）、Cloudflare 与 Tailscale 双通道穿透
 */
public class MainActivity extends Activity {
    private static final String TAG = "MainActivity";

    private AppConfig appConfig;
    private SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private View mainRootView;
    private WebPanelManager webPanelManager;

    // 页面与 4-Tab 引用
    private int currentTab = 0;
    private View pageService;
    private View pageTunnel;
    private View pageTailscale;
    private View pageMetrics;
    private View pageAbout;
    private View pageGuestShare;
    private LinearLayout guestShareListContainer;

    private LinearLayout tabServicePill, tabTunnelPill, tabTailscalePill, tabMetricsPill, tabAboutPill;
    private ImageView tabServiceIcon, tabTunnelIcon, tabTailscaleIcon, tabMetricsIcon, tabAboutIcon;

    // 服务状态与操作按键
    private TextView statusText;
    private View statusIndicatorDot;
    private TextView btnStart, btnStop;
    private boolean isServerRunning = false;
    private TextView portBadge;
    private TextView tvApiKeyDisplay;
    private LinearLayout addressCardContainer;
    private boolean isMasterKeyMasked = true;
    private String currentTunnelDomain = "";
    private LinearLayout tunnelEndpointsContainer;

    // 服务日志组件
    private LinearLayout logContainer;
    private ScrollView logScroll;
    private final StringBuilder allServiceLogBuilder = new StringBuilder();
    private int serviceLogLineIndex = 0;

    // 外网隧道组件
    private TextView tunnelStatusText;
    private TextView tunnelProxyText;
    private TextView tunnelUrlText;
    private TextView btnTunnelUrlCopy;
    private LinearLayout tunnelLogContainer;
    private ScrollView tunnelLogScroll;
    private final StringBuilder allTunnelLogBuilder = new StringBuilder();
    private int tunnelLogLineIndex = 0;

    // 出站代理组件
    private TextView proxyBadge;

    // Tailscale 组件
    private TextView tsStatusText;
    private TextView tsIpText;
    private TextView btnTsIpCopy;
    private EditText editTsAuthKey;
    private EditText editTsHostname;
    private LinearLayout tsLogContainer;
    private ScrollView tsLogScroll;
    private final StringBuilder allTsLogBuilder = new StringBuilder();
    private int tsLogLineIndex = 0;

    // Metrics 仪表与智能缓存组件
    private TextView metricTotalRequests;
    private TextView metricAvgLatency;
    private TextView metricSuccessRate;
    private TextView metricUptime;
    private TextView metricCacheHits;
    private TextView metricSavedTokens;
    private TextView metricCachedCount;
    private LinearLayout metricsLogList;

    // ==================== 1. 生命周期与权限配置 ====================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupImmersiveStatusBar();

        appConfig = new AppConfig(this);
        prefs = getSharedPreferences(AppConfig.PREFS_NAME, MODE_PRIVATE);
        webPanelManager = new WebPanelManager(this, () -> setContentView(mainRootView));

        setupUI();
        loadApiKeyFromConfigFile();

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

    // ==================== 2. 主界面布局与 4-Tab 无框导航 ====================

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
        pageService = buildServicePage();
        contentContainer.addView(pageService);

        // Tab 1: 外网穿透页面
        pageTunnel = buildTunnelPage();
        pageTunnel.setVisibility(View.GONE);
        contentContainer.addView(pageTunnel);

        // Tab 2: Tailscale 虚拟内网页面
        pageTailscale = buildTailscalePage();
        pageTailscale.setVisibility(View.GONE);
        contentContainer.addView(pageTailscale);

        // Tab 3: 流量与运维仪表盘页面
        pageMetrics = buildMetricsPage();
        pageMetrics.setVisibility(View.GONE);
        contentContainer.addView(pageMetrics);

        // Tab 4: 关于页面
        pageAbout = buildAboutPage();
        pageAbout.setVisibility(View.GONE);
        contentContainer.addView(pageAbout);

        // 二级页面：外网共享API 专属管理页
        pageGuestShare = buildGuestSharePage();
        pageGuestShare.setVisibility(View.GONE);
        contentContainer.addView(pageGuestShare);

        root.addView(contentContainer);
        root.addView(buildBottomBar());

        mainRootView = root;
        setContentView(mainRootView);
    }

    /** 4-Tab 底部纯图标导航栏（服务、外网、内网、仪表） */
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

        if (index == 3) {
            refreshMetricsView();
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

    // ==================== 3. Tab 0: 服务控制台与 API 端点 ====================

    private View buildServicePage() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        layout.setPadding(UiTheme.dp(this, 12), UiTheme.dp(this, 8), UiTheme.dp(this, 12), UiTheme.dp(this, 4));

        layout.addView(buildHeroHeader());
        View addrCard = buildAddressSection();
        LinearLayout.LayoutParams acLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        acLp.topMargin = UiTheme.dp(this, 6);
        addrCard.setLayoutParams(acLp);
        layout.addView(addrCard);

        View logCard = buildLogSection();
        LinearLayout.LayoutParams lclp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        lclp.topMargin = UiTheme.dp(this, 6);
        logCard.setLayoutParams(lclp);
        layout.addView(logCard);

        return layout;
    }

    private View buildHeroHeader() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(UiTheme.roundRect(this, UiTheme.C_SURFACE, UiTheme.C_BORDER, 1, 6));
        card.setPadding(UiTheme.dp(this, 12), UiTheme.dp(this, 8), UiTheme.dp(this, 12), UiTheme.dp(this, 8));

        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText(appConfig.getProjectName());
        title.setTextSize(15);
        title.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        topRow.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView tagBadge = new TextView(this);
        tagBadge.setText("AI Proxy");
        tagBadge.setTextSize(9.5f);
        tagBadge.setTextColor(Color.parseColor(UiTheme.C_PURPLE));
        tagBadge.setBackground(UiTheme.roundRect(this, "#1A102F", UiTheme.C_PURPLE, 1, 3));
        tagBadge.setPadding(UiTheme.dp(this, 4), UiTheme.dp(this, 1), UiTheme.dp(this, 4), UiTheme.dp(this, 1));
        topRow.addView(tagBadge);

        portBadge = new TextView(this);
        portBadge.setText(" :" + appConfig.getPort() + " ✎");
        portBadge.setTextSize(10);
        portBadge.setTextColor(Color.parseColor(UiTheme.C_CYAN));
        portBadge.setTypeface(Typeface.MONOSPACE);
        portBadge.setBackground(UiTheme.roundRect(this, "#0A2328", UiTheme.C_CYAN, 1, 3));
        portBadge.setPadding(UiTheme.dp(this, 5), UiTheme.dp(this, 1), UiTheme.dp(this, 5), UiTheme.dp(this, 1));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.leftMargin = UiTheme.dp(this, 4);
        portBadge.setLayoutParams(plp);
        portBadge.setOnClickListener(v -> showPortEditDialog());
        topRow.addView(portBadge);

        proxyBadge = new TextView(this);
        updateProxyBadge();
        LinearLayout.LayoutParams prxlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        prxlp.leftMargin = UiTheme.dp(this, 4);
        proxyBadge.setLayoutParams(prxlp);
        proxyBadge.setOnClickListener(v -> showOutboundProxyDialog());
        topRow.addView(proxyBadge);

        card.addView(topRow);

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusRow.setPadding(0, UiTheme.dp(this, 4), 0, UiTheme.dp(this, 6));

        statusIndicatorDot = new View(this);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(UiTheme.dp(this, 6), UiTheme.dp(this, 6));
        dlp.rightMargin = UiTheme.dp(this, 4);
        statusIndicatorDot.setLayoutParams(dlp);
        statusIndicatorDot.setBackground(UiTheme.roundRect(this, UiTheme.C_DIM, null, 0, 3));
        statusRow.addView(statusIndicatorDot);

        statusText = new TextView(this);
        statusText.setText("已停止");
        statusText.setTextSize(11);
        statusText.setTextColor(Color.parseColor(UiTheme.C_DIM));
        statusRow.addView(statusText);
        card.addView(statusRow);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);

        btnStart = UiTheme.createButton(this, "启动", UiTheme.C_GREEN, "#0D2818", UiTheme.C_GREEN, 3);
        btnStart.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        btnStart.setOnClickListener(v -> startServer());
        btnRow.addView(btnStart);

        TextView btnCheck = UiTheme.createButton(this, "检查", UiTheme.C_CYAN, "#0A2328", UiTheme.C_CYAN, 3);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        clp.leftMargin = UiTheme.dp(this, 5);
        btnCheck.setLayoutParams(clp);
        btnCheck.setOnClickListener(v -> checkHealth());
        btnRow.addView(btnCheck);

        TextView btnPanel = UiTheme.createButton(this, "管理", UiTheme.C_BLUE, "#0D2240", UiTheme.C_BLUE, 3);
        LinearLayout.LayoutParams pmlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        pmlp.leftMargin = UiTheme.dp(this, 5);
        btnPanel.setLayoutParams(pmlp);
        btnPanel.setOnClickListener(v -> openWebPanel(appConfig.getManagementUrl()));
        btnRow.addView(btnPanel);

        btnStop = UiTheme.createButton(this, "停止", UiTheme.C_RED, "#2D1214", "#4E1C1F", 3);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        slp.leftMargin = UiTheme.dp(this, 5);
        btnStop.setLayoutParams(slp);
        btnStop.setOnClickListener(v -> stopServer());
        btnRow.addView(btnStop);

        card.addView(btnRow);
        return card;
    }

    private View buildAddressSection() {
        addressCardContainer = new LinearLayout(this);
        addressCardContainer.setOrientation(LinearLayout.VERTICAL);
        addressCardContainer.setBackground(UiTheme.roundRect(this, UiTheme.C_SURFACE, UiTheme.C_BORDER, 1, 6));
        addressCardContainer.setPadding(UiTheme.dp(this, 12), UiTheme.dp(this, 8), UiTheme.dp(this, 12), UiTheme.dp(this, 8));

        refreshAddressSection();
        return addressCardContainer;
    }

    private void refreshAddressSection() {
        if (addressCardContainer == null) return;
        addressCardContainer.removeAllViews();

        // 顶栏：标题 + mDNS 独立热切换开关
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(0, 0, 0, UiTheme.dp(this, 6));

        TextView title = new TextView(this);
        title.setText("API 端点");
        title.setTextSize(12);
        title.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        boolean isMdnsOn = prefs.getBoolean("mdns_enabled", false);
        TextView btnMdnsToggle = UiTheme.createButton(this, isMdnsOn ? "mDNS: 已开启" : "mDNS: 已关闭",
                isMdnsOn ? UiTheme.C_GREEN : UiTheme.C_DIM,
                isMdnsOn ? "#0D2818" : UiTheme.C_SURFACE_ALT,
                isMdnsOn ? UiTheme.C_GREEN : UiTheme.C_BORDER, 3);
        btnMdnsToggle.setOnClickListener(v -> {
            boolean nextState = !prefs.getBoolean("mdns_enabled", false);
            prefs.edit().putBoolean("mdns_enabled", nextState).apply();

            if (isServerRunning) {
                if (nextState) {
                    MdnsManager.getInstance(this).start(appConfig.getPort());
                } else {
                    MdnsManager.getInstance(this).stop();
                }
            }
            refreshAddressSection();
            Toast.makeText(this, nextState ?
                    "mDNS 广播已开启 (http://cliproxy.local:" + appConfig.getPort() + "/v1)" :
                    "mDNS 广播已关闭 (已释放组播锁，切换为局域网 IP 直连)", Toast.LENGTH_SHORT).show();
        });
        top.addView(btnMdnsToggle);
        addressCardContainer.addView(top);

        if (!currentTunnelDomain.isEmpty()) {
            addressCardContainer.addView(buildAddressRow("全球公网直连 (Cloudflare)", currentTunnelDomain + "/v1"));
            View divExt = new View(this);
            divExt.setBackgroundColor(Color.parseColor(UiTheme.C_BORDER_SUB));
            LinearLayout.LayoutParams dlpe = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UiTheme.dp(this, 1));
            dlpe.topMargin = UiTheme.dp(this, 4);
            dlpe.bottomMargin = UiTheme.dp(this, 4);
            addressCardContainer.addView(divExt, dlpe);
        }

        // 第 1 行：局域网端点 (根据 mDNS 状态切换)
        if (isMdnsOn) {
            addressCardContainer.addView(buildAddressRow("局域网直连 (仅家庭或私有wifi)",
                    "http://" + MdnsManager.HOST_NAME + ":" + appConfig.getPort() + "/v1"));
        } else {
            addressCardContainer.addView(buildAddressRow("局域网 IP 直连",
                    "http://" + getLanIpAddress() + ":" + appConfig.getPort() + "/v1"));
        }

        View div1 = new View(this);
        div1.setBackgroundColor(Color.parseColor(UiTheme.C_BORDER_SUB));
        LinearLayout.LayoutParams dlp1 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UiTheme.dp(this, 1));
        dlp1.topMargin = UiTheme.dp(this, 4);
        dlp1.bottomMargin = UiTheme.dp(this, 4);
        addressCardContainer.addView(div1, dlp1);

        // 第 2 行：主流客户端 (chat/completions)
        addressCardContainer.addView(buildAddressRow("主流客户端", appConfig.getChatUrl()));

        View div2 = new View(this);
        div2.setBackgroundColor(Color.parseColor(UiTheme.C_BORDER_SUB));
        LinearLayout.LayoutParams dlp2 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UiTheme.dp(this, 1));
        dlp2.topMargin = UiTheme.dp(this, 4);
        dlp2.bottomMargin = UiTheme.dp(this, 4);
        addressCardContainer.addView(div2, dlp2);

        // 第 3 行：新一代API (responses)
        addressCardContainer.addView(buildAddressRow("新一代API", appConfig.getResponsesUrl()));

        View div3 = new View(this);
        div3.setBackgroundColor(Color.parseColor(UiTheme.C_BORDER_SUB));
        LinearLayout.LayoutParams dlp3 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UiTheme.dp(this, 1));
        dlp3.topMargin = UiTheme.dp(this, 4);
        dlp3.bottomMargin = UiTheme.dp(this, 4);
        addressCardContainer.addView(div3, dlp3);

        // 第 4 行：访问密钥 (API Key)
        addressCardContainer.addView(buildApiKeyRow());
    }

    private String maskKey(String key) {
        if (key == null || key.isEmpty()) return "";
        if (key.length() <= 10) return "••••••••";
        return key.substring(0, 7) + "••••••••";
    }

    private View buildApiKeyRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, UiTheme.dp(this, 5), 0, UiTheme.dp(this, 5));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView labelView = new TextView(this);
        labelView.setText("● 访问密钥 (API Key)");
        labelView.setTextSize(10.5f);
        labelView.setTextColor(Color.parseColor(UiTheme.C_BLUE));
        labelView.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(labelView);

        tvApiKeyDisplay = new TextView(this);
        tvApiKeyDisplay.setText(isMasterKeyMasked ? maskKey(appConfig.getApiKey()) : appConfig.getApiKey());
        tvApiKeyDisplay.setTextSize(10.5f);
        tvApiKeyDisplay.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        tvApiKeyDisplay.setTypeface(Typeface.MONOSPACE);
        tvApiKeyDisplay.setPadding(0, UiTheme.dp(this, 1), 0, 0);
        col.addView(tvApiKeyDisplay);

        row.addView(col);

        TextView eyeBtn = UiTheme.createButton(this, isMasterKeyMasked ? "👁" : "👁‍🗨",
                isMasterKeyMasked ? UiTheme.C_DIM : UiTheme.C_CYAN,
                UiTheme.C_SURFACE_ALT,
                isMasterKeyMasked ? UiTheme.C_BORDER : UiTheme.C_CYAN, 3);
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        olp.rightMargin = UiTheme.dp(this, 5);
        eyeBtn.setLayoutParams(olp);
        eyeBtn.setOnClickListener(v -> {
            isMasterKeyMasked = !isMasterKeyMasked;
            tvApiKeyDisplay.setText(isMasterKeyMasked ? maskKey(appConfig.getApiKey()) : appConfig.getApiKey());
            eyeBtn.setText(isMasterKeyMasked ? "👁" : "👁‍🗨");
            eyeBtn.setTextColor(Color.parseColor(isMasterKeyMasked ? UiTheme.C_DIM : UiTheme.C_CYAN));
            eyeBtn.setBackground(UiTheme.roundRect(this, UiTheme.C_SURFACE_ALT,
                    isMasterKeyMasked ? UiTheme.C_BORDER : UiTheme.C_CYAN, 1, 3));
        });
        row.addView(eyeBtn);

        TextView editBtn = UiTheme.createButton(this, "修改", UiTheme.C_CYAN, "#0A2328", UiTheme.C_CYAN, 3);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.rightMargin = UiTheme.dp(this, 5);
        editBtn.setLayoutParams(elp);
        editBtn.setOnClickListener(v -> showApiKeyEditDialog());
        row.addView(editBtn);

        TextView copyBtn = UiTheme.createButton(this, "复制", UiTheme.C_TEXT, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 3);
        copyBtn.setOnClickListener(v -> copyToClipboard("API Key", appConfig.getApiKey()));
        row.addView(copyBtn);

        return row;
    }

    private void updateProxyBadge() {
        if (proxyBadge == null) return;
        boolean enabled = prefs.getBoolean("outbound_proxy_enabled", false);
        String url = prefs.getString("outbound_proxy_url", "").trim();
        proxyBadge.setTextSize(10);
        proxyBadge.setTypeface(Typeface.MONOSPACE);
        proxyBadge.setPadding(UiTheme.dp(this, 5), UiTheme.dp(this, 1), UiTheme.dp(this, 5), UiTheme.dp(this, 1));
        if (enabled && !url.isEmpty()) {
            proxyBadge.setText(" 🌐 代理: 开 ");
            proxyBadge.setTextColor(Color.parseColor(UiTheme.C_GREEN));
            proxyBadge.setBackground(UiTheme.roundRect(this, "#0D2818", UiTheme.C_GREEN, 1, 3));
        } else {
            proxyBadge.setText(" 🌐 代理: 关 ");
            proxyBadge.setTextColor(Color.parseColor(UiTheme.C_DIM));
            proxyBadge.setBackground(UiTheme.roundRect(this, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 1, 3));
        }
    }

    private void updateTunnelProxyText() {
        if (tunnelProxyText == null) return;
        boolean enabled = prefs.getBoolean("outbound_proxy_enabled", false);
        String url = prefs.getString("outbound_proxy_url", "").trim();
        if (enabled && !url.isEmpty()) {
            tunnelProxyText.setText("出站代理: " + url);
            tunnelProxyText.setTextColor(Color.parseColor(UiTheme.C_GREEN));
        } else {
            tunnelProxyText.setText("出站代理: 未启用（国内建议开启）");
            tunnelProxyText.setTextColor(Color.parseColor(UiTheme.C_DIM));
        }
    }

    private void showOutboundProxyDialog() {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(UiTheme.roundRect(this, UiTheme.C_BG, UiTheme.C_BORDER, 1, 8));
        root.setPadding(UiTheme.dp(this, 16), UiTheme.dp(this, 14), UiTheme.dp(this, 16), UiTheme.dp(this, 14));

        TextView tvTitle = new TextView(this);
        tvTitle.setText("🌐 出站网络代理 (Outbound Proxy)");
        tvTitle.setTextSize(14);
        tvTitle.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(tvTitle);

        TextView tvSub = new TextView(this);
        tvSub.setText("为 AI 核心网关与 Cloudflare 隧道提供外部代理通信通道");
        tvSub.setTextSize(10.5f);
        tvSub.setTextColor(Color.parseColor(UiTheme.C_DIM));
        tvSub.setPadding(0, UiTheme.dp(this, 2), 0, UiTheme.dp(this, 8));
        root.addView(tvSub);

        // 开关行
        boolean currentEnabled = prefs.getBoolean("outbound_proxy_enabled", false);
        final boolean[] proxyEnabledHolder = new boolean[]{currentEnabled};

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

        // 代理地址输入框
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

        // 快捷常用地址行
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

        // 连通性测试区
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

        // 绕过规则 (NO_PROXY)
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

        // 按钮栏
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

            updateProxyBadge();
            updateTunnelProxyText();
            dialog.dismiss();

            String statusDesc = en ? ("已开启 (" + u + ")") : "已关闭";
            if (isServerRunning) {
                Toast.makeText(this, "代理" + statusDesc + "，将在重启服务或下次穿透时生效", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "出站代理设置已保存: " + statusDesc, Toast.LENGTH_SHORT).show();
            }
        });
        actionRow.addView(btnSave);
        root.addView(actionRow);

        dialog.setContentView(root);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setLayout(UiTheme.dp(this, 320), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
    }

    private void showPortEditDialog() {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(UiTheme.roundRect(this, UiTheme.C_BG, UiTheme.C_BORDER, 1, 8));
        root.setPadding(UiTheme.dp(this, 16), UiTheme.dp(this, 14), UiTheme.dp(this, 16), UiTheme.dp(this, 14));

        TextView tvTitle = new TextView(this);
        tvTitle.setText("修改服务端口 (Port)");
        tvTitle.setTextSize(14);
        tvTitle.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(tvTitle);

        TextView tvSub = new TextView(this);
        tvSub.setText("有效范围: 1024 ~ 65534 (修改后需重启服务)");
        tvSub.setTextSize(10.5f);
        tvSub.setTextColor(Color.parseColor(UiTheme.C_DIM));
        tvSub.setPadding(0, UiTheme.dp(this, 2), 0, UiTheme.dp(this, 10));
        root.addView(tvSub);

        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(appConfig.getPort()));
        input.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        input.setTextSize(13);
        input.setTypeface(Typeface.MONOSPACE);
        input.setBackground(UiTheme.roundRect(this, UiTheme.C_SURFACE, UiTheme.C_BORDER, 1, 4));
        input.setPadding(UiTheme.dp(this, 10), UiTheme.dp(this, 8), UiTheme.dp(this, 10), UiTheme.dp(this, 8));
        root.addView(input);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams brlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        brlp.topMargin = UiTheme.dp(this, 12);
        btnRow.setLayoutParams(brlp);

        TextView btnCancel = UiTheme.createButton(this, "取消", UiTheme.C_DIM, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 3);
        btnCancel.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        btnCancel.setOnClickListener(v -> dialog.dismiss());
        btnRow.addView(btnCancel);

        View spacer = new View(this);
        btnRow.addView(spacer, new LinearLayout.LayoutParams(UiTheme.dp(this, 8), 1));

        TextView btnSave = UiTheme.createButton(this, "保存", UiTheme.C_CYAN, "#0A2328", UiTheme.C_CYAN, 3);
        btnSave.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        btnSave.setOnClickListener(v -> {
            String val = input.getText().toString().trim();
            try {
                int p = Integer.parseInt(val);
                if (p < 1024 || p > 65534) {
                    Toast.makeText(this, "端口需在 1024 ~ 65534 之间", Toast.LENGTH_SHORT).show();
                    return;
                }
                updatePortInConfig(p);
                refreshAddressSection();
                dialog.dismiss();
                if (isServerRunning) {
                    Toast.makeText(this, "端口已修改为 " + p + "，重启服务后生效", Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, "端口已修改为 " + p, Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                Toast.makeText(this, "请输入合法的端口号", Toast.LENGTH_SHORT).show();
            }
        });
        btnRow.addView(btnSave);
        root.addView(btnRow);

        dialog.setContentView(root);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setLayout(UiTheme.dp(this, 300), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
    }

    private void showApiKeyEditDialog() {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(UiTheme.roundRect(this, UiTheme.C_BG, UiTheme.C_BORDER, 1, 8));
        root.setPadding(UiTheme.dp(this, 16), UiTheme.dp(this, 14), UiTheme.dp(this, 16), UiTheme.dp(this, 14));

        TextView tvTitle = new TextView(this);
        tvTitle.setText("修改 API Key (访问密钥)");
        tvTitle.setTextSize(14);
        tvTitle.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(tvTitle);

        TextView tvSub = new TextView(this);
        tvSub.setText("第三方客户端认证使用的 Bearer Token (修改后需重启服务)");
        tvSub.setTextSize(10.5f);
        tvSub.setTextColor(Color.parseColor(UiTheme.C_DIM));
        tvSub.setPadding(0, UiTheme.dp(this, 2), 0, UiTheme.dp(this, 10));
        root.addView(tvSub);

        EditText input = new EditText(this);
        input.setText(appConfig.getApiKey());
        input.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        input.setTextSize(12.5f);
        input.setTypeface(Typeface.MONOSPACE);
        input.setBackground(UiTheme.roundRect(this, UiTheme.C_SURFACE, UiTheme.C_BORDER, 1, 4));
        input.setPadding(UiTheme.dp(this, 10), UiTheme.dp(this, 8), UiTheme.dp(this, 10), UiTheme.dp(this, 8));
        root.addView(input);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams brlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        brlp.topMargin = UiTheme.dp(this, 12);
        btnRow.setLayoutParams(brlp);

        TextView btnCancel = UiTheme.createButton(this, "取消", UiTheme.C_DIM, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 3);
        btnCancel.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        btnCancel.setOnClickListener(v -> dialog.dismiss());
        btnRow.addView(btnCancel);

        View spacer = new View(this);
        btnRow.addView(spacer, new LinearLayout.LayoutParams(UiTheme.dp(this, 8), 1));

        TextView btnSave = UiTheme.createButton(this, "保存", UiTheme.C_CYAN, "#0A2328", UiTheme.C_CYAN, 3);
        btnSave.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        btnSave.setOnClickListener(v -> {
            String newKey = input.getText().toString().trim();
            if (newKey.isEmpty()) {
                Toast.makeText(this, "密钥不能为空", Toast.LENGTH_SHORT).show();
                return;
            }
            updateApiKeyInConfig(newKey);
            dialog.dismiss();
            if (isServerRunning) {
                Toast.makeText(this, "密钥已更新，重启服务后生效", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "密钥已保存", Toast.LENGTH_SHORT).show();
            }
        });
        btnRow.addView(btnSave);
        root.addView(btnRow);

        dialog.setContentView(root);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setLayout(UiTheme.dp(this, 300), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
    }

    private File getSecureConfigFile() {
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

    private void updatePortInConfig(int newPort) {
        appConfig.setPort(newPort);
        if (portBadge != null) {
            portBadge.setText(" :" + newPort + " ✎");
        }
        new Thread(() -> {
            try {
                File configFile = getSecureConfigFile();
                if (configFile.exists()) {
                    FileInputStream fis = new FileInputStream(configFile);
                    byte[] b = new byte[(int) configFile.length()];
                    fis.read(b);
                    fis.close();
                    String yaml = new String(b, StandardCharsets.UTF_8);
                    yaml = yaml.replaceAll("(?m)^port:\\s*\\d+", "port: " + newPort);
                    FileOutputStream fos = new FileOutputStream(configFile);
                    fos.write(yaml.getBytes(StandardCharsets.UTF_8));
                    fos.flush();
                    fos.close();
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to update port in config.yaml", e);
            }
        }).start();
    }

    private void updateApiKeyInConfig(String newKey) {
        appConfig.setApiKey(newKey);
        if (tvApiKeyDisplay != null) {
            tvApiKeyDisplay.setText(isMasterKeyMasked ? maskKey(newKey) : newKey);
        }
        new Thread(() -> {
            try {
                File configFile = getSecureConfigFile();
                if (configFile.exists()) {
                    FileInputStream fis = new FileInputStream(configFile);
                    byte[] b = new byte[(int) configFile.length()];
                    fis.read(b);
                    fis.close();
                    String yaml = new String(b, StandardCharsets.UTF_8);
                    yaml = yaml.replaceFirst("(?m)^(\\s*-\\s*)[\"']?[^\"'\\r\\n]+[\"']?", "$1\"" + newKey + "\"");
                    FileOutputStream fos = new FileOutputStream(configFile);
                    fos.write(yaml.getBytes(StandardCharsets.UTF_8));
                    fos.flush();
                    fos.close();
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to update API key in config.yaml", e);
            }
        }).start();
    }



    private void loadApiKeyFromConfigFile() {
        new Thread(() -> {
            try {
                File configFile = getSecureConfigFile();
                if (configFile.exists()) {
                    FileInputStream fis = new FileInputStream(configFile);
                    BufferedReader br = new BufferedReader(new InputStreamReader(fis, StandardCharsets.UTF_8));
                    String line;
                    boolean inApiKeys = false;
                    while ((line = br.readLine()) != null) {
                        String trimmed = line.trim();
                        if (trimmed.startsWith("api-keys:")) {
                            inApiKeys = true;
                            continue;
                        }
                        if (inApiKeys) {
                            if (trimmed.startsWith("-")) {
                                String key = trimmed.substring(1).trim().replace("\"", "").replace("'", "");
                                if (!key.isEmpty() && !key.startsWith("sk-guest-")) {
                                    appConfig.setApiKey(key);
                                    handler.post(() -> {
                                        if (tvApiKeyDisplay != null) {
                                            tvApiKeyDisplay.setText(isMasterKeyMasked ? maskKey(key) : key);
                                        }
                                    });
                                    break;
                                }
                            } else if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                                break;
                            }
                        }
                    }
                    br.close();
                }
            } catch (Exception ignored) {}
        }).start();
    }

    /** 获取当前手机的有效局域网 Wi-Fi IPv4 地址 */
    private String getLanIpAddress() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                if (intf.isLoopback() || !intf.isUp()) continue;
                String name = intf.getName().toLowerCase();
                if (name.contains("wlan") || name.contains("ap") || name.contains("eth")) {
                    List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
                    for (InetAddress addr : addrs) {
                        if (!addr.isLoopbackAddress() && addr.getAddress().length == 4) {
                            return addr.getHostAddress();
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return "127.0.0.1";
    }

    private View buildAddressRow(String label, String url) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, UiTheme.dp(this, 5), 0, UiTheme.dp(this, 5));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView labelView = new TextView(this);
        labelView.setText("● " + label);
        labelView.setTextSize(10.5f);
        labelView.setTextColor(Color.parseColor(UiTheme.C_BLUE));
        labelView.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(labelView);

        TextView urlView = new TextView(this);
        urlView.setText(url);
        urlView.setTextSize(10.5f);
        urlView.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        urlView.setTypeface(Typeface.MONOSPACE);
        urlView.setPadding(0, UiTheme.dp(this, 1), 0, 0);
        col.addView(urlView);

        row.addView(col);

        TextView copyBtn = UiTheme.createButton(this, "复制", UiTheme.C_TEXT, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 3);
        copyBtn.setOnClickListener(v -> copyToClipboard(label, url));
        row.addView(copyBtn);
        return row;
    }

    private View buildLogSection() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(UiTheme.roundRect(this, UiTheme.C_SURFACE, UiTheme.C_BORDER, 1, 6));
        card.setPadding(UiTheme.dp(this, 10), UiTheme.dp(this, 6), UiTheme.dp(this, 10), UiTheme.dp(this, 6));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(0, 0, 0, UiTheme.dp(this, 4));

        TextView title = new TextView(this);
        title.setText("服务日志");
        title.setTextSize(11.5f);
        title.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView copyBtn = UiTheme.createButton(this, "复制", UiTheme.C_TEXT, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 3);
        copyBtn.setOnClickListener(v -> copyToClipboard("服务日志", allServiceLogBuilder.toString()));
        top.addView(copyBtn);

        TextView clearBtn = UiTheme.createButton(this, "清空", UiTheme.C_DIM, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 3);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.leftMargin = UiTheme.dp(this, 5);
        clearBtn.setLayoutParams(clp);
        clearBtn.setOnClickListener(v -> {
            logContainer.removeAllViews();
            allServiceLogBuilder.setLength(0);
            serviceLogLineIndex = 0;
            prefs.edit().remove("log").apply();
            Toast.makeText(this, "日志已清空", Toast.LENGTH_SHORT).show();
        });
        top.addView(clearBtn);
        card.addView(top);

        logScroll = new ScrollView(this);
        logScroll.setBackgroundColor(Color.parseColor("#080B0F"));
        logScroll.setFillViewport(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        logScroll.setLayoutParams(lp);

        logContainer = new LinearLayout(this);
        logContainer.setOrientation(LinearLayout.VERTICAL);
        logContainer.setPadding(UiTheme.dp(this, 6), UiTheme.dp(this, 4), UiTheme.dp(this, 6), UiTheme.dp(this, 4));
        logScroll.addView(logContainer);

        card.addView(logScroll);
        return card;
    }

    // ==================== 4. Tab 1: 外网穿透控制台 (Cloudflare) ====================

    private View buildTunnelPage() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        layout.setPadding(UiTheme.dp(this, 12), UiTheme.dp(this, 8), UiTheme.dp(this, 12), UiTheme.dp(this, 4));

        layout.addView(UiTheme.buildSectionHeader(this, "外网穿透 (Cloudflare)", null));
        layout.addView(buildTunnelSection());
        layout.addView(buildGuestShareEntryCard());

        View tunnelLogCard = buildTunnelLogSection();
        LinearLayout.LayoutParams tlclp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        tlclp.topMargin = UiTheme.dp(this, 6);
        tunnelLogCard.setLayoutParams(tlclp);
        layout.addView(tunnelLogCard);

        return layout;
    }

    private View buildTunnelSection() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(UiTheme.roundRect(this, UiTheme.C_SURFACE, UiTheme.C_BORDER, 1, 6));
        card.setPadding(UiTheme.dp(this, 12), UiTheme.dp(this, 8), UiTheme.dp(this, 12), UiTheme.dp(this, 8));

        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView label = new TextView(this);
        label.setText("穿透状态: ");
        label.setTextSize(11);
        label.setTextColor(Color.parseColor(UiTheme.C_DIM));
        statusRow.addView(label);

        tunnelStatusText = new TextView(this);
        tunnelStatusText.setText("未启动");
        tunnelStatusText.setTextSize(11);
        tunnelStatusText.setTextColor(Color.parseColor(UiTheme.C_DIM));
        statusRow.addView(tunnelStatusText);
        card.addView(statusRow);

        LinearLayout proxyRow = new LinearLayout(this);
        proxyRow.setOrientation(LinearLayout.HORIZONTAL);
        proxyRow.setGravity(Gravity.CENTER_VERTICAL);
        proxyRow.setPadding(0, UiTheme.dp(this, 2), 0, UiTheme.dp(this, 3));

        tunnelProxyText = new TextView(this);
        updateTunnelProxyText();
        tunnelProxyText.setTextSize(10.5f);
        tunnelProxyText.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        proxyRow.addView(tunnelProxyText);

        TextView cfgProxyBtn = UiTheme.createButton(this, "出站代理设置 ⚙", UiTheme.C_CYAN, "#0A2328", UiTheme.C_CYAN, 3);
        cfgProxyBtn.setOnClickListener(v -> showOutboundProxyDialog());
        proxyRow.addView(cfgProxyBtn);
        card.addView(proxyRow);

        tunnelEndpointsContainer = new LinearLayout(this);
        tunnelEndpointsContainer.setOrientation(LinearLayout.VERTICAL);
        refreshTunnelEndpoints();
        card.addView(tunnelEndpointsContainer);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams brlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        brlp.topMargin = UiTheme.dp(this, 6);
        btnRow.setLayoutParams(brlp);

        TextView btnStartT = UiTheme.createButton(this, "启动 Cloudflare 穿透", UiTheme.C_BLUE, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 3);
        btnStartT.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        btnStartT.setOnClickListener(v -> startTunnel());
        btnRow.addView(btnStartT);

        TextView btnStopT = UiTheme.createButton(this, "停止", UiTheme.C_RED, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 3);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.leftMargin = UiTheme.dp(this, 5);
        btnStopT.setLayoutParams(slp);
        btnStopT.setOnClickListener(v -> stopTunnel());
        btnRow.addView(btnStopT);

        card.addView(btnRow);
        return card;
    }

    private void refreshTunnelEndpoints() {
        if (tunnelEndpointsContainer == null) return;
        tunnelEndpointsContainer.removeAllViews();

        if (currentTunnelDomain.isEmpty()) {
            LinearLayout urlRow = new LinearLayout(this);
            urlRow.setOrientation(LinearLayout.HORIZONTAL);
            urlRow.setGravity(Gravity.CENTER_VERTICAL);
            urlRow.setPadding(0, UiTheme.dp(this, 4), 0, UiTheme.dp(this, 2));

            tunnelUrlText = new TextView(this);
            tunnelUrlText.setText("https://...trycloudflare.com/v1");
            tunnelUrlText.setTextSize(10.5f);
            tunnelUrlText.setTextColor(Color.parseColor(UiTheme.C_DIM));
            tunnelUrlText.setTypeface(Typeface.MONOSPACE);
            tunnelUrlText.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            urlRow.addView(tunnelUrlText);

            btnTunnelUrlCopy = UiTheme.createButton(this, "复制", UiTheme.C_DIM, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 3);
            btnTunnelUrlCopy.setEnabled(false);
            btnTunnelUrlCopy.setAlpha(0.35f);
            urlRow.addView(btnTunnelUrlCopy);
            tunnelEndpointsContainer.addView(urlRow);
        } else {
            tunnelEndpointsContainer.addView(buildAddressRow("公网 Base URL (推荐好友填入)", currentTunnelDomain + "/v1"));

            View d1 = new View(this);
            d1.setBackgroundColor(Color.parseColor(UiTheme.C_BORDER_SUB));
            LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UiTheme.dp(this, 1));
            lp1.topMargin = UiTheme.dp(this, 3);
            lp1.bottomMargin = UiTheme.dp(this, 3);
            tunnelEndpointsContainer.addView(d1, lp1);

            tunnelEndpointsContainer.addView(buildAddressRow("主流客户端", currentTunnelDomain + "/v1/chat/completions"));

            View d2 = new View(this);
            d2.setBackgroundColor(Color.parseColor(UiTheme.C_BORDER_SUB));
            LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UiTheme.dp(this, 1));
            lp2.topMargin = UiTheme.dp(this, 3);
            lp2.bottomMargin = UiTheme.dp(this, 3);
            tunnelEndpointsContainer.addView(d2, lp2);

            tunnelEndpointsContainer.addView(buildAddressRow("新一代API", currentTunnelDomain + "/v1/responses"));
        }
    }

    private View buildGuestShareEntryCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(UiTheme.roundRect(this, UiTheme.C_SURFACE, UiTheme.C_BORDER, 1, 6));
        card.setPadding(UiTheme.dp(this, 12), UiTheme.dp(this, 10), UiTheme.dp(this, 12), UiTheme.dp(this, 10));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = UiTheme.dp(this, 6);
        card.setLayoutParams(clp);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = new TextView(this);
        title.setText("外网共享API");
        title.setTextSize(12.5f);
        title.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(title);

        TextView sub = new TextView(this);
        sub.setText("独立客用密钥 · 额度限制 · 一键分享");
        sub.setTextSize(10.5f);
        sub.setTextColor(Color.parseColor(UiTheme.C_DIM));
        sub.setPadding(0, UiTheme.dp(this, 2), 0, 0);
        col.addView(sub);
        card.addView(col);

        TextView btnEnter = UiTheme.createButton(this, "进入管理 ➔", UiTheme.C_CYAN, "#0A2328", UiTheme.C_CYAN, 3);
        btnEnter.setOnClickListener(v -> openGuestSharePage());
        card.addView(btnEnter);

        return card;
    }

    private View buildGuestSharePage() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(Color.parseColor(UiTheme.C_BG));
        layout.setPadding(UiTheme.dp(this, 12), UiTheme.dp(this, 8), UiTheme.dp(this, 12), UiTheme.dp(this, 8));

        // 顶栏：← 返回 + 外网共享API + [+ 新增Key]
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(0, UiTheme.dp(this, 4), 0, UiTheme.dp(this, 10));

        TextView btnBack = UiTheme.createButton(this, "← 返回", UiTheme.C_TEXT, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 3);
        btnBack.setOnClickListener(v -> closeGuestSharePage());
        topBar.addView(btnBack);

        TextView title = new TextView(this);
        title.setText("外网共享API");
        title.setTextSize(14f);
        title.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        topBar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView btnAdd = UiTheme.createButton(this, "+ 新增Key", UiTheme.C_CYAN, "#0A2328", UiTheme.C_CYAN, 3);
        btnAdd.setOnClickListener(v -> showGuestKeyEditDialog(null, -1));
        topBar.addView(btnAdd);
        layout.addView(topBar);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        scroll.setLayoutParams(slp);

        guestShareListContainer = new LinearLayout(this);
        guestShareListContainer.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(guestShareListContainer);
        layout.addView(scroll);

        return layout;
    }

    private void openGuestSharePage() {
        if (pageGuestShare != null) {
            pageGuestShare.setVisibility(View.VISIBLE);
            refreshGuestShareList();
        }
    }

    private void closeGuestSharePage() {
        if (pageGuestShare != null) {
            pageGuestShare.setVisibility(View.GONE);
        }
    }

    private final java.util.Map<String, Boolean> guestKeyMaskMap = new java.util.HashMap<>();

    private void refreshGuestShareList() {
        if (guestShareListContainer == null) return;
        guestShareListContainer.removeAllViews();

        JSONArray arr = getGuestKeysJsonArray();
        if (arr.length() == 0) {
            LinearLayout emptyBox = new LinearLayout(this);
            emptyBox.setOrientation(LinearLayout.VERTICAL);
            emptyBox.setGravity(Gravity.CENTER);
            emptyBox.setPadding(0, UiTheme.dp(this, 50), 0, 0);

            TextView tip1 = new TextView(this);
            tip1.setText("暂无客用 Key");
            tip1.setTextSize(14);
            tip1.setTextColor(Color.parseColor(UiTheme.C_DIM));
            tip1.setTypeface(Typeface.DEFAULT_BOLD);
            emptyBox.addView(tip1);

            TextView tip2 = new TextView(this);
            tip2.setText("点击右上角【+ 新增Key】即可创建独立防刷密钥，\n支持按 Token (10M~100M) 或按次数精准限额！");
            tip2.setTextSize(11);
            tip2.setTextColor(Color.parseColor(UiTheme.C_DIM));
            tip2.setGravity(Gravity.CENTER);
            tip2.setPadding(0, UiTheme.dp(this, 8), 0, UiTheme.dp(this, 16));
            emptyBox.addView(tip2);

            TextView btnAdd = UiTheme.createButton(this, "+ 创建第一个客用密钥", UiTheme.C_CYAN, "#0A2328", UiTheme.C_CYAN, 3);
            btnAdd.setOnClickListener(v -> showGuestKeyEditDialog(null, -1));
            emptyBox.addView(btnAdd);

            guestShareListContainer.addView(emptyBox);
            return;
        }

        for (int i = 0; i < arr.length(); i++) {
            JSONObject obj = arr.optJSONObject(i);
            if (obj == null) continue;
            final int index = i;
            final JSONObject item = obj;

            String key = obj.optString("key", "");
            String remark = obj.optString("remark", "访客");
            String mode = obj.optString("mode", "TOKEN");
            long quotaTotal = obj.optLong("quotaTotal", 10_000_000L);
            long quotaRemaining = obj.optLong("quotaRemaining", quotaTotal);
            int rpm = obj.optInt("rpm", 5);

            if (!guestKeyMaskMap.containsKey(key)) {
                guestKeyMaskMap.put(key, true);
            }
            boolean isMasked = Boolean.TRUE.equals(guestKeyMaskMap.get(key));

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(UiTheme.roundRect(this, UiTheme.C_SURFACE, UiTheme.C_BORDER, 1, 6));
            card.setPadding(UiTheme.dp(this, 12), UiTheme.dp(this, 8), UiTheme.dp(this, 12), UiTheme.dp(this, 8));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.bottomMargin = UiTheme.dp(this, 8);
            card.setLayoutParams(clp);

            // 头部：● 备注名 + 模式标签 + RPM 标签
            LinearLayout top = new LinearLayout(this);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);

            TextView tvRemark = new TextView(this);
            tvRemark.setText("● " + remark);
            tvRemark.setTextSize(12);
            tvRemark.setTextColor(Color.parseColor(UiTheme.C_TEXT));
            tvRemark.setTypeface(Typeface.DEFAULT_BOLD);
            top.addView(tvRemark, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView modeTag = new TextView(this);
            modeTag.setText("TOKEN".equals(mode) ? "Token计费" : "按次计费");
            modeTag.setTextSize(9.5f);
            modeTag.setTextColor(Color.parseColor(UiTheme.C_CYAN));
            modeTag.setBackground(UiTheme.roundRect(this, "#0A2328", UiTheme.C_CYAN, 1, 3));
            modeTag.setPadding(UiTheme.dp(this, 4), UiTheme.dp(this, 1), UiTheme.dp(this, 4), UiTheme.dp(this, 1));
            LinearLayout.LayoutParams mtlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            mtlp.rightMargin = UiTheme.dp(this, 4);
            modeTag.setLayoutParams(mtlp);
            top.addView(modeTag);

            TextView rpmTag = new TextView(this);
            rpmTag.setText(rpm + " RPM 盾牌");
            rpmTag.setTextSize(9.5f);
            rpmTag.setTextColor(Color.parseColor(UiTheme.C_GREEN));
            rpmTag.setBackground(UiTheme.roundRect(this, "#0D2818", UiTheme.C_GREEN, 1, 3));
            rpmTag.setPadding(UiTheme.dp(this, 4), UiTheme.dp(this, 1), UiTheme.dp(this, 4), UiTheme.dp(this, 1));
            top.addView(rpmTag);
            card.addView(top);

            // Key 行：Key 文本 + [👁] + [编辑] + [删除]
            LinearLayout keyRow = new LinearLayout(this);
            keyRow.setOrientation(LinearLayout.HORIZONTAL);
            keyRow.setGravity(Gravity.CENTER_VERTICAL);
            keyRow.setPadding(0, UiTheme.dp(this, 5), 0, UiTheme.dp(this, 4));

            TextView tvKey = new TextView(this);
            tvKey.setText(isMasked ? maskKey(key) : key);
            tvKey.setTextSize(10.5f);
            tvKey.setTextColor(Color.parseColor(UiTheme.C_BLUE));
            tvKey.setTypeface(Typeface.MONOSPACE);
            tvKey.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            keyRow.addView(tvKey);

            TextView eyeBtn = UiTheme.createButton(this, isMasked ? "👁" : "👁‍🗨",
                    isMasked ? UiTheme.C_DIM : UiTheme.C_CYAN,
                    UiTheme.C_SURFACE_ALT,
                    isMasked ? UiTheme.C_BORDER : UiTheme.C_CYAN, 3);
            LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            elp.rightMargin = UiTheme.dp(this, 4);
            eyeBtn.setLayoutParams(elp);
            eyeBtn.setOnClickListener(v -> {
                boolean next = !guestKeyMaskMap.get(key);
                guestKeyMaskMap.put(key, next);
                tvKey.setText(next ? maskKey(key) : key);
                eyeBtn.setText(next ? "👁" : "👁‍🗨");
                eyeBtn.setTextColor(Color.parseColor(next ? UiTheme.C_DIM : UiTheme.C_CYAN));
                eyeBtn.setBackground(UiTheme.roundRect(this, UiTheme.C_SURFACE_ALT,
                        next ? UiTheme.C_BORDER : UiTheme.C_CYAN, 1, 3));
            });
            keyRow.addView(eyeBtn);

            TextView btnEdit = UiTheme.createButton(this, "编辑", UiTheme.C_CYAN, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 3);
            LinearLayout.LayoutParams edlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            edlp.rightMargin = UiTheme.dp(this, 4);
            btnEdit.setLayoutParams(edlp);
            btnEdit.setOnClickListener(v -> showGuestKeyEditDialog(item, index));
            keyRow.addView(btnEdit);

            TextView btnDel = UiTheme.createButton(this, "删除", UiTheme.C_RED, "#280A0A", UiTheme.C_RED, 3);
            btnDel.setOnClickListener(v -> {
                JSONArray list = getGuestKeysJsonArray();
                JSONArray nextList = new JSONArray();
                for (int j = 0; j < list.length(); j++) {
                    if (j != index) nextList.put(list.optJSONObject(j));
                }
                saveGuestKeysJsonArray(nextList);
                syncGuestKeysToProxyAndConfig();
                refreshGuestShareList();
                Toast.makeText(this, "已销毁客用密钥: " + remark, Toast.LENGTH_SHORT).show();
            });
            keyRow.addView(btnDel);
            card.addView(keyRow);

            // 进度信息行
            long used = quotaTotal - quotaRemaining;
            int pct = (quotaTotal > 0) ? (int) (used * 100 / quotaTotal) : 0;
            TextView tvProgress = new TextView(this);
            tvProgress.setText("剩余: " + SmartCacheProxy.formatQuota(quotaRemaining, mode) +
                    " / " + SmartCacheProxy.formatQuota(quotaTotal, mode) +
                    " (已用 " + pct + "%) · 限频: " + rpm + "次/分");
            tvProgress.setTextSize(10f);
            tvProgress.setTextColor(Color.parseColor(UiTheme.C_DIM));
            tvProgress.setPadding(0, 0, 0, UiTheme.dp(this, 6));
            card.addView(tvProgress);

            // 一键复制专属分享卡片
            TextView btnShare = UiTheme.createButton(this, "📋 复制专属分享卡片", UiTheme.C_GREEN, "#0D2818", UiTheme.C_GREEN, 3);
            btnShare.setOnClickListener(v -> copyGuestShareCard(item));
            card.addView(btnShare);

            guestShareListContainer.addView(card);
        }
    }

    private void showGuestKeyEditDialog(JSONObject existing, int editIndex) {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackground(UiTheme.roundRect(this, UiTheme.C_SURFACE, UiTheme.C_BORDER, 1, 8));
        layout.setPadding(UiTheme.dp(this, 16), UiTheme.dp(this, 14), UiTheme.dp(this, 16), UiTheme.dp(this, 14));

        TextView title = new TextView(this);
        title.setText(existing == null ? "✨ 新增客用 Key" : "✎ 编辑客用 Key");
        title.setTextSize(13.5f);
        title.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        layout.addView(title);

        TextView lblRemark = new TextView(this);
        lblRemark.setText("备注名称 (如: 张同事、写代码专用)");
        lblRemark.setTextSize(10.5f);
        lblRemark.setTextColor(Color.parseColor(UiTheme.C_DIM));
        lblRemark.setPadding(0, UiTheme.dp(this, 8), 0, UiTheme.dp(this, 2));
        layout.addView(lblRemark);

        EditText etRemark = new EditText(this);
        etRemark.setText(existing != null ? existing.optString("remark", "") : "");
        etRemark.setHint("好友/场景备注");
        etRemark.setTextSize(11.5f);
        etRemark.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        etRemark.setHintTextColor(Color.parseColor(UiTheme.C_DIM));
        etRemark.setBackground(UiTheme.roundRect(this, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 1, 4));
        etRemark.setPadding(UiTheme.dp(this, 8), UiTheme.dp(this, 6), UiTheme.dp(this, 8), UiTheme.dp(this, 6));
        layout.addView(etRemark);

        TextView lblKey = new TextView(this);
        lblKey.setText("专属 Key 字符串 (可自定义)");
        lblKey.setTextSize(10.5f);
        lblKey.setTextColor(Color.parseColor(UiTheme.C_DIM));
        lblKey.setPadding(0, UiTheme.dp(this, 8), 0, UiTheme.dp(this, 2));
        layout.addView(lblKey);

        String autoKey = "sk-guest-" + Long.toHexString(System.currentTimeMillis() ^ (long) (Math.random() * 0xFFFF));
        if (autoKey.length() > 13) autoKey = autoKey.substring(0, 13);

        EditText etKey = new EditText(this);
        etKey.setText(existing != null ? existing.optString("key", autoKey) : autoKey);
        etKey.setTextSize(11.5f);
        etKey.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        etKey.setTypeface(Typeface.MONOSPACE);
        etKey.setBackground(UiTheme.roundRect(this, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 1, 4));
        etKey.setPadding(UiTheme.dp(this, 8), UiTheme.dp(this, 6), UiTheme.dp(this, 8), UiTheme.dp(this, 6));
        layout.addView(etKey);

        TextView lblMode = new TextView(this);
        lblMode.setText("计费模式选择");
        lblMode.setTextSize(10.5f);
        lblMode.setTextColor(Color.parseColor(UiTheme.C_DIM));
        lblMode.setPadding(0, UiTheme.dp(this, 8), 0, UiTheme.dp(this, 3));
        layout.addView(lblMode);

        final String[] curMode = { existing != null ? existing.optString("mode", "TOKEN") : "TOKEN" };

        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);

        TextView btnModeToken = UiTheme.createButton(this, "按 Token 限制 (10M~100M)",
                "TOKEN".equals(curMode[0]) ? UiTheme.C_CYAN : UiTheme.C_DIM,
                "TOKEN".equals(curMode[0]) ? "#0A2328" : UiTheme.C_SURFACE_ALT,
                "TOKEN".equals(curMode[0]) ? UiTheme.C_CYAN : UiTheme.C_BORDER, 3);
        btnModeToken.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f));

        TextView btnModeCount = UiTheme.createButton(this, "按次数限制",
                "COUNT".equals(curMode[0]) ? UiTheme.C_CYAN : UiTheme.C_DIM,
                "COUNT".equals(curMode[0]) ? "#0A2328" : UiTheme.C_SURFACE_ALT,
                "COUNT".equals(curMode[0]) ? UiTheme.C_CYAN : UiTheme.C_BORDER, 3);
        LinearLayout.LayoutParams clp2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.8f);
        clp2.leftMargin = UiTheme.dp(this, 6);
        btnModeCount.setLayoutParams(clp2);

        modeRow.addView(btnModeToken);
        modeRow.addView(btnModeCount);
        layout.addView(modeRow);

        LinearLayout tokenBox = new LinearLayout(this);
        tokenBox.setOrientation(LinearLayout.VERTICAL);
        tokenBox.setVisibility("TOKEN".equals(curMode[0]) ? View.VISIBLE : View.GONE);

        TextView lblToken = new TextView(this);
        lblToken.setText("Token 额度 (最低 10M ~ 最高 100M):");
        lblToken.setTextSize(10.5f);
        lblToken.setTextColor(Color.parseColor(UiTheme.C_DIM));
        lblToken.setPadding(0, UiTheme.dp(this, 6), 0, UiTheme.dp(this, 2));
        tokenBox.addView(lblToken);

        LinearLayout presetRow = new LinearLayout(this);
        presetRow.setOrientation(LinearLayout.HORIZONTAL);
        presetRow.setPadding(0, 0, 0, UiTheme.dp(this, 4));

        long initialToken = existing != null ? existing.optLong("quotaTotal", 10_000_000L) : 10_000_000L;
        int initialM = (int) Math.max(10, Math.min(100, initialToken / 1_000_000L));

        EditText etTokenM = new EditText(this);
        etTokenM.setText(String.valueOf(initialM));
        etTokenM.setInputType(InputType.TYPE_CLASS_NUMBER);
        etTokenM.setTextSize(11.5f);
        etTokenM.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        etTokenM.setBackground(UiTheme.roundRect(this, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 1, 4));
        etTokenM.setPadding(UiTheme.dp(this, 8), UiTheme.dp(this, 6), UiTheme.dp(this, 8), UiTheme.dp(this, 6));

        int[] presets = {10, 20, 50, 100};
        for (int p : presets) {
            TextView pBtn = UiTheme.createButton(this, p + "M", UiTheme.C_TEXT, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 3);
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            plp.rightMargin = UiTheme.dp(this, 3);
            pBtn.setLayoutParams(plp);
            pBtn.setOnClickListener(v -> etTokenM.setText(String.valueOf(p)));
            presetRow.addView(pBtn);
        }
        tokenBox.addView(presetRow);
        tokenBox.addView(etTokenM);
        layout.addView(tokenBox);

        LinearLayout countBox = new LinearLayout(this);
        countBox.setOrientation(LinearLayout.VERTICAL);
        countBox.setVisibility("COUNT".equals(curMode[0]) ? View.VISIBLE : View.GONE);

        TextView lblCount = new TextView(this);
        lblCount.setText("调用总次数 (次):");
        lblCount.setTextSize(10.5f);
        lblCount.setTextColor(Color.parseColor(UiTheme.C_DIM));
        lblCount.setPadding(0, UiTheme.dp(this, 6), 0, UiTheme.dp(this, 2));
        countBox.addView(lblCount);

        EditText etCount = new EditText(this);
        etCount.setText(String.valueOf(existing != null ? existing.optLong("quotaTotal", 100L) : 100L));
        etCount.setInputType(InputType.TYPE_CLASS_NUMBER);
        etCount.setTextSize(11.5f);
        etCount.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        etCount.setBackground(UiTheme.roundRect(this, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 1, 4));
        etCount.setPadding(UiTheme.dp(this, 8), UiTheme.dp(this, 6), UiTheme.dp(this, 8), UiTheme.dp(this, 6));
        countBox.addView(etCount);
        layout.addView(countBox);

        btnModeToken.setOnClickListener(v -> {
            curMode[0] = "TOKEN";
            btnModeToken.setTextColor(Color.parseColor(UiTheme.C_CYAN));
            btnModeToken.setBackground(UiTheme.roundRect(this, "#0A2328", UiTheme.C_CYAN, 1, 3));
            btnModeCount.setTextColor(Color.parseColor(UiTheme.C_DIM));
            btnModeCount.setBackground(UiTheme.roundRect(this, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 1, 3));
            tokenBox.setVisibility(View.VISIBLE);
            countBox.setVisibility(View.GONE);
        });
        btnModeCount.setOnClickListener(v -> {
            curMode[0] = "COUNT";
            btnModeCount.setTextColor(Color.parseColor(UiTheme.C_CYAN));
            btnModeCount.setBackground(UiTheme.roundRect(this, "#0A2328", UiTheme.C_CYAN, 1, 3));
            btnModeToken.setTextColor(Color.parseColor(UiTheme.C_DIM));
            btnModeToken.setBackground(UiTheme.roundRect(this, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 1, 3));
            countBox.setVisibility(View.VISIBLE);
            tokenBox.setVisibility(View.GONE);
        });

        TextView lblRpm = new TextView(this);
        lblRpm.setText("频次限制 (RPM · 每分钟最大请求数，防刷单):");
        lblRpm.setTextSize(10.5f);
        lblRpm.setTextColor(Color.parseColor(UiTheme.C_DIM));
        lblRpm.setPadding(0, UiTheme.dp(this, 8), 0, UiTheme.dp(this, 2));
        layout.addView(lblRpm);

        EditText etRpm = new EditText(this);
        etRpm.setText(String.valueOf(existing != null ? existing.optInt("rpm", 5) : 5));
        etRpm.setInputType(InputType.TYPE_CLASS_NUMBER);
        etRpm.setTextSize(11.5f);
        etRpm.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        etRpm.setBackground(UiTheme.roundRect(this, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 1, 4));
        etRpm.setPadding(UiTheme.dp(this, 8), UiTheme.dp(this, 6), UiTheme.dp(this, 8), UiTheme.dp(this, 6));
        layout.addView(etRpm);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, UiTheme.dp(this, 14), 0, 0);

        TextView btnCancel = UiTheme.createButton(this, "取消", UiTheme.C_DIM, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 3);
        btnCancel.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        btnCancel.setOnClickListener(v -> dialog.dismiss());
        btnRow.addView(btnCancel);

        TextView btnSave = UiTheme.createButton(this, "保存", UiTheme.C_CYAN, "#0A2328", UiTheme.C_CYAN, 3);
        LinearLayout.LayoutParams splp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        splp.leftMargin = UiTheme.dp(this, 8);
        btnSave.setLayoutParams(splp);
        btnSave.setOnClickListener(v -> {
            String remark = etRemark.getText().toString().trim();
            if (remark.isEmpty()) remark = "好友访客";
            String key = etKey.getText().toString().trim();
            if (key.isEmpty()) {
                Toast.makeText(this, "密钥不能为空", Toast.LENGTH_SHORT).show();
                return;
            }

            int rpm = 5;
            try { rpm = Integer.parseInt(etRpm.getText().toString().trim()); } catch (Exception ignored) {}
            if (rpm <= 0) rpm = 5;

            long totalQuota;
            if ("TOKEN".equals(curMode[0])) {
                int m = 10;
                try { m = Integer.parseInt(etTokenM.getText().toString().trim()); } catch (Exception ignored) {}
                if (m < 10) {
                    Toast.makeText(this, "Token 额度最低为 10M", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (m > 100) {
                    Toast.makeText(this, "Token 额度最高为 100M", Toast.LENGTH_SHORT).show();
                    return;
                }
                totalQuota = (long) m * 1_000_000L;
            } else {
                long c = 100L;
                try { c = Long.parseLong(etCount.getText().toString().trim()); } catch (Exception ignored) {}
                if (c <= 0) c = 100L;
                totalQuota = c;
            }

            long remainingQuota = totalQuota;
            if (existing != null) {
                long oldTotal = existing.optLong("quotaTotal", totalQuota);
                long oldRemaining = existing.optLong("quotaRemaining", oldTotal);
                if (totalQuota >= oldTotal) {
                    remainingQuota = oldRemaining + (totalQuota - oldTotal);
                } else {
                    remainingQuota = Math.min(oldRemaining, totalQuota);
                }
            }

            try {
                JSONObject saveObj = new JSONObject();
                saveObj.put("key", key);
                saveObj.put("remark", remark);
                saveObj.put("mode", curMode[0]);
                saveObj.put("quotaTotal", totalQuota);
                saveObj.put("quotaRemaining", remainingQuota);
                saveObj.put("rpm", rpm);

                JSONArray arr = getGuestKeysJsonArray();
                if (editIndex >= 0 && editIndex < arr.length()) {
                    arr.put(editIndex, saveObj);
                } else {
                    arr.put(saveObj);
                }
                saveGuestKeysJsonArray(arr);
                syncGuestKeysToProxyAndConfig();
                refreshGuestShareList();
                dialog.dismiss();
                Toast.makeText(this, "客用密钥【" + remark + "】已保存！", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "保存失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
        btnRow.addView(btnSave);
        layout.addView(btnRow);

        dialog.setContentView(layout);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.92f), WindowManager.LayoutParams.WRAP_CONTENT);
            w.setBackgroundDrawableResource(android.R.color.transparent);
        }
        dialog.show();
    }

    private void copyGuestShareCard(JSONObject item) {
        String baseUrl = (!currentTunnelDomain.isEmpty()) ? (currentTunnelDomain + "/v1") : "https://[请先启动外网穿透]/v1";
        String chatUrl = (!currentTunnelDomain.isEmpty()) ? (currentTunnelDomain + "/v1/chat/completions") : "https://[请先启动外网穿透]/v1/chat/completions";
        String respUrl = (!currentTunnelDomain.isEmpty()) ? (currentTunnelDomain + "/v1/responses") : "https://[请先启动外网穿透]/v1/responses";

        String key = item.optString("key", appConfig.getApiKey());
        String remark = item.optString("remark", "好友");
        String mode = item.optString("mode", "TOKEN");
        long total = item.optLong("quotaTotal", 10_000_000L);
        int rpm = item.optInt("rpm", 5);

        String quotaDesc = "TOKEN".equals(mode) ?
                (SmartCacheProxy.formatQuota(total, mode) + " · " + rpm + "次/分") :
                (total + "次 · " + rpm + "次/分");

        String content = "🤖 给你分享一个我的 AI 大模型代理节点：\n\n" +
                "👤 专属用户: " + remark + "\n\n" +
                "🌐 接口地址 (Base URL):\n" + baseUrl + "\n\n" +
                "🔑 专属密钥 (API Key):\n" + key + " (专属配额: " + quotaDesc + ")\n\n" +
                "⚡ 支持模型:\ngrok-3-mini, grok-4.5\n\n" +
                "💡 客户端配置指导：\n" +
                "1. NextChat / Chatbox：在设置中将 API 地址填为上方 Base URL，密钥填入 API Key；\n" +
                "2. 沉浸式翻译：接口类型选 OpenAI，模型选 grok-3-mini；\n" +
                "3. 直达完整端点：\n" +
                "   - 主流客户端: " + chatUrl + "\n" +
                "   - 新一代API: " + respUrl;

        copyToClipboard("分享卡片", content);
        Toast.makeText(this, "已复制【" + remark + "】专属分享卡片，可直接发微信好友！", Toast.LENGTH_SHORT).show();
    }

    private JSONArray getGuestKeysJsonArray() {
        String json = prefs.getString("guest_keys_json", "[]");
        try {
            return new JSONArray(json);
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private void saveGuestKeysJsonArray(JSONArray arr) {
        prefs.edit().putString("guest_keys_json", arr != null ? arr.toString() : "[]").apply();
    }

    private void syncGuestKeysToProxyAndConfig() {
        JSONArray arr = getGuestKeysJsonArray();
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
                    yaml = yaml.replaceAll("(?m)^\\s*-\\s*\"sk-guest-[^\"]+\"\\r?\\n?", "");
                    StringBuilder sb = new StringBuilder();
                    for (String gk : keys) {
                        sb.append("  - \"").append(gk).append("\"\n");
                    }
                    yaml = yaml.replaceFirst("(?m)(api-keys:\\s*\\n)", "$1" + sb.toString());
                    FileOutputStream fos = new FileOutputStream(configFile);
                    fos.write(yaml.getBytes(StandardCharsets.UTF_8));
                    fos.flush();
                    fos.close();
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to sync guest keys to config.yaml", e);
            }
        }).start();
    }

    private void initGuestPolicies() {
        String oldGuestKey = prefs.getString("guest_api_key", "");
        if (!oldGuestKey.isEmpty() && getGuestKeysJsonArray().length() == 0) {
            JSONArray arr = new JSONArray();
            JSONObject obj = new JSONObject();
            try {
                obj.put("key", oldGuestKey);
                obj.put("remark", "默认客用Key");
                obj.put("mode", "TOKEN");
                obj.put("quotaTotal", 10_000_000L);
                obj.put("quotaRemaining", 10_000_000L);
                obj.put("rpm", 5);
                arr.put(obj);
                saveGuestKeysJsonArray(arr);
            } catch (Exception ignored) {}
            prefs.edit().remove("guest_api_key").apply();
        }

        syncGuestKeysToProxyAndConfig();

        SmartCacheProxy.setMultiQuotaListener((key, remaining, total) -> {
            JSONArray arr = getGuestKeysJsonArray();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj != null && key.equals(obj.optString("key"))) {
                    try {
                        obj.put("quotaRemaining", remaining);
                        arr.put(i, obj);
                        saveGuestKeysJsonArray(arr);
                    } catch (Exception ignored) {}
                    break;
                }
            }
            handler.post(() -> {
                if (pageGuestShare != null && pageGuestShare.getVisibility() == View.VISIBLE) {
                    refreshGuestShareList();
                }
            });
        });
    }

    private View buildTunnelLogSection() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(UiTheme.roundRect(this, UiTheme.C_SURFACE, UiTheme.C_BORDER, 1, 6));
        card.setPadding(UiTheme.dp(this, 10), UiTheme.dp(this, 6), UiTheme.dp(this, 10), UiTheme.dp(this, 6));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(0, 0, 0, UiTheme.dp(this, 4));

        TextView title = new TextView(this);
        title.setText("隧道日志");
        title.setTextSize(11.5f);
        title.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView copyBtn = UiTheme.createButton(this, "复制", UiTheme.C_TEXT, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 3);
        copyBtn.setOnClickListener(v -> copyToClipboard("隧道日志", allTunnelLogBuilder.toString()));
        top.addView(copyBtn);

        TextView clearBtn = UiTheme.createButton(this, "清空", UiTheme.C_DIM, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 3);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.leftMargin = UiTheme.dp(this, 5);
        clearBtn.setLayoutParams(clp);
        clearBtn.setOnClickListener(v -> {
            tunnelLogContainer.removeAllViews();
            allTunnelLogBuilder.setLength(0);
            tunnelLogLineIndex = 0;
            Toast.makeText(this, "隧道日志已清空", Toast.LENGTH_SHORT).show();
        });
        top.addView(clearBtn);
        card.addView(top);

        tunnelLogScroll = new ScrollView(this);
        tunnelLogScroll.setBackgroundColor(Color.parseColor("#080B0F"));
        tunnelLogScroll.setFillViewport(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        tunnelLogScroll.setLayoutParams(lp);

        tunnelLogContainer = new LinearLayout(this);
        tunnelLogContainer.setOrientation(LinearLayout.VERTICAL);
        tunnelLogContainer.setPadding(UiTheme.dp(this, 6), UiTheme.dp(this, 4), UiTheme.dp(this, 6), UiTheme.dp(this, 4));
        tunnelLogScroll.addView(tunnelLogContainer);

        card.addView(tunnelLogScroll);
        return card;
    }

    // ==================== 5. Tab 2: Tailscale 虚拟局域网 ====================

    private View buildTailscalePage() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        layout.setPadding(UiTheme.dp(this, 12), UiTheme.dp(this, 8), UiTheme.dp(this, 12), UiTheme.dp(this, 4));

        layout.addView(UiTheme.buildSectionHeader(this, "Tailscale 虚拟局域网", "纯用户态模式（免 Root，不占系统 VPN 槽位）"));
        layout.addView(buildTailscaleControlSection());

        View tsLogCard = buildTailscaleLogSection();
        LinearLayout.LayoutParams tslp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        tslp.topMargin = UiTheme.dp(this, 6);
        tsLogCard.setLayoutParams(tslp);
        layout.addView(tsLogCard);

        return layout;
    }

    private View buildTailscaleControlSection() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(UiTheme.roundRect(this, UiTheme.C_SURFACE, UiTheme.C_BORDER, 1, 6));
        card.setPadding(UiTheme.dp(this, 12), UiTheme.dp(this, 8), UiTheme.dp(this, 12), UiTheme.dp(this, 8));

        // 状态行
        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView label = new TextView(this);
        label.setText("Tailnet 状态: ");
        label.setTextSize(11);
        label.setTextColor(Color.parseColor(UiTheme.C_DIM));
        statusRow.addView(label);

        tsStatusText = new TextView(this);
        tsStatusText.setText("未连接");
        tsStatusText.setTextSize(11);
        tsStatusText.setTextColor(Color.parseColor(UiTheme.C_DIM));
        statusRow.addView(tsStatusText);
        card.addView(statusRow);

        // IP / 局域网地址行
        LinearLayout ipRow = new LinearLayout(this);
        ipRow.setOrientation(LinearLayout.HORIZONTAL);
        ipRow.setGravity(Gravity.CENTER_VERTICAL);
        ipRow.setPadding(0, UiTheme.dp(this, 4), 0, UiTheme.dp(this, 6));

        tsIpText = new TextView(this);
        tsIpText.setText("http://100.x.x.x:" + appConfig.getPort() + "/v1");
        tsIpText.setTextSize(10.5f);
        tsIpText.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        tsIpText.setTypeface(Typeface.MONOSPACE);
        tsIpText.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ipRow.addView(tsIpText);

        btnTsIpCopy = UiTheme.createButton(this, "复制", UiTheme.C_TEXT, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 3);
        btnTsIpCopy.setEnabled(false);
        btnTsIpCopy.setAlpha(0.35f);
        btnTsIpCopy.setOnClickListener(v -> copyToClipboard("Tailscale 地址", tsIpText.getText().toString()));
        ipRow.addView(btnTsIpCopy);
        card.addView(ipRow);

        // AuthKey 输入框
        editTsAuthKey = new EditText(this);
        editTsAuthKey.setHint("Auth Key (tskey-auth-xxxx，可空)");
        editTsAuthKey.setHintTextColor(Color.parseColor("#608B949E"));
        editTsAuthKey.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        editTsAuthKey.setTextSize(10.5f);
        editTsAuthKey.setTypeface(Typeface.MONOSPACE);
        editTsAuthKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        editTsAuthKey.setBackground(UiTheme.roundRect(this, "#0D1117", UiTheme.C_BORDER, 1, 4));
        editTsAuthKey.setPadding(UiTheme.dp(this, 8), UiTheme.dp(this, 4), UiTheme.dp(this, 8), UiTheme.dp(this, 4));
        editTsAuthKey.setText(prefs.getString("ts_auth_key", ""));
        card.addView(editTsAuthKey);

        // 节点 Hostname 输入框
        editTsHostname = new EditText(this);
        editTsHostname.setHint("节点名称 (默认 cliproxy-phone)");
        editTsHostname.setHintTextColor(Color.parseColor("#608B949E"));
        editTsHostname.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        editTsHostname.setTextSize(10.5f);
        editTsHostname.setTypeface(Typeface.MONOSPACE);
        editTsHostname.setBackground(UiTheme.roundRect(this, "#0D1117", UiTheme.C_BORDER, 1, 4));
        editTsHostname.setPadding(UiTheme.dp(this, 8), UiTheme.dp(this, 4), UiTheme.dp(this, 8), UiTheme.dp(this, 4));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = UiTheme.dp(this, 5);
        editTsHostname.setLayoutParams(hlp);
        editTsHostname.setText(prefs.getString("ts_hostname", "cliproxy-phone"));
        card.addView(editTsHostname);

        // 控制按键
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = UiTheme.dp(this, 6);
        btnRow.setLayoutParams(blp);

        TextView btnConnectTs = UiTheme.createButton(this, "连接 Tailscale", UiTheme.C_BLUE, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 3);
        btnConnectTs.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        btnConnectTs.setOnClickListener(v -> startTailscale());
        btnRow.addView(btnConnectTs);

        TextView btnDisconnectTs = UiTheme.createButton(this, "断开", UiTheme.C_RED, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 3);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.leftMargin = UiTheme.dp(this, 5);
        btnDisconnectTs.setLayoutParams(dlp);
        btnDisconnectTs.setOnClickListener(v -> stopTailscale());
        btnRow.addView(btnDisconnectTs);

        card.addView(btnRow);
        return card;
    }

    private View buildTailscaleLogSection() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(UiTheme.roundRect(this, UiTheme.C_SURFACE, UiTheme.C_BORDER, 1, 6));
        card.setPadding(UiTheme.dp(this, 10), UiTheme.dp(this, 6), UiTheme.dp(this, 10), UiTheme.dp(this, 6));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(0, 0, 0, UiTheme.dp(this, 4));

        TextView title = new TextView(this);
        title.setText("Tailscale 日志");
        title.setTextSize(11.5f);
        title.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView copyBtn = UiTheme.createButton(this, "复制", UiTheme.C_TEXT, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 3);
        copyBtn.setOnClickListener(v -> copyToClipboard("Tailscale 日志", allTsLogBuilder.toString()));
        top.addView(copyBtn);

        TextView clearBtn = UiTheme.createButton(this, "清空", UiTheme.C_DIM, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 3);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.leftMargin = UiTheme.dp(this, 5);
        clearBtn.setLayoutParams(clp);
        clearBtn.setOnClickListener(v -> {
            tsLogContainer.removeAllViews();
            allTsLogBuilder.setLength(0);
            tsLogLineIndex = 0;
            Toast.makeText(this, "Tailscale 日志已清空", Toast.LENGTH_SHORT).show();
        });
        top.addView(clearBtn);
        card.addView(top);

        tsLogScroll = new ScrollView(this);
        tsLogScroll.setBackgroundColor(Color.parseColor("#080B0F"));
        tsLogScroll.setFillViewport(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        tsLogScroll.setLayoutParams(lp);

        tsLogContainer = new LinearLayout(this);
        tsLogContainer.setOrientation(LinearLayout.VERTICAL);
        tsLogContainer.setPadding(UiTheme.dp(this, 6), UiTheme.dp(this, 4), UiTheme.dp(this, 6), UiTheme.dp(this, 4));
        tsLogScroll.addView(tsLogContainer);

        card.addView(tsLogScroll);
        return card;
    }

    // ==================== 6. Tab 3: 流量与运维统计仪表 (Metrics) ====================

    private View buildMetricsPage() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        layout.setPadding(UiTheme.dp(this, 12), UiTheme.dp(this, 8), UiTheme.dp(this, 12), UiTheme.dp(this, 4));

        layout.addView(UiTheme.buildSectionHeader(this, "流量与运维监控", "实时统计 API 调用吞吐、延迟分布与最近访问审计"));
        layout.addView(buildKpiGridSection());
        layout.addView(buildSmartCacheSection());

        // 审计流水容器卡片（动态填满高度）
        View auditCard = buildAuditSection();
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        alp.topMargin = UiTheme.dp(this, 6);
        auditCard.setLayoutParams(alp);
        layout.addView(auditCard);

        return layout;
    }

    /** 核心指标四宫格卡片 */
    private View buildKpiGridSection() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(UiTheme.roundRect(this, UiTheme.C_SURFACE, UiTheme.C_BORDER, 1, 6));
        card.setPadding(UiTheme.dp(this, 10), UiTheme.dp(this, 8), UiTheme.dp(this, 10), UiTheme.dp(this, 8));

        // 第 1 行: 今日请求数 + 平均延迟
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);

        View kpi1 = createKpiItem("今日请求量", "0", UiTheme.C_BLUE);
        metricTotalRequests = kpi1.findViewById(android.R.id.text1);
        row1.addView(kpi1, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        View kpi2 = createKpiItem("平均延迟", "0 ms", UiTheme.C_CYAN);
        metricAvgLatency = kpi2.findViewById(android.R.id.text1);
        row1.addView(kpi2, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(row1);

        View div = new View(this);
        div.setBackgroundColor(Color.parseColor(UiTheme.C_BORDER_SUB));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UiTheme.dp(this, 1));
        dlp.topMargin = UiTheme.dp(this, 6);
        dlp.bottomMargin = UiTheme.dp(this, 6);
        card.addView(div, dlp);

        // 第 2 行: 成功率 + 运行时间
        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);

        View kpi3 = createKpiItem("请求成功率", "100.0%", UiTheme.C_GREEN);
        metricSuccessRate = kpi3.findViewById(android.R.id.text1);
        row2.addView(kpi3, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        View kpi4 = createKpiItem("已运行时长", "00分 00秒", UiTheme.C_PURPLE);
        metricUptime = kpi4.findViewById(android.R.id.text1);
        row2.addView(kpi4, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(row2);

        return card;
    }

    /** 本地智能响应缓存卡片 */
    private View buildSmartCacheSection() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(UiTheme.roundRect(this, UiTheme.C_SURFACE, UiTheme.C_BORDER, 1, 6));
        card.setPadding(UiTheme.dp(this, 10), UiTheme.dp(this, 6), UiTheme.dp(this, 10), UiTheme.dp(this, 6));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = UiTheme.dp(this, 6);
        card.setLayoutParams(clp);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(0, 0, 0, UiTheme.dp(this, 4));

        TextView title = new TextView(this);
        title.setText("⚡ 智能响应缓存 (5ms 秒回)");
        title.setTextSize(11f);
        title.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        boolean isCacheOn = prefs.getBoolean("cache_enabled", false);
        SmartCacheProxy.setGlobalCacheEnabled(isCacheOn);

        TextView btnCacheToggle = UiTheme.createButton(this, isCacheOn ? "已开启" : "已关闭",
                isCacheOn ? UiTheme.C_GREEN : UiTheme.C_DIM,
                isCacheOn ? "#0D2818" : UiTheme.C_SURFACE_ALT,
                isCacheOn ? UiTheme.C_GREEN : UiTheme.C_BORDER, 3);
        btnCacheToggle.setOnClickListener(v -> {
            boolean nextState = !prefs.getBoolean("cache_enabled", false);
            prefs.edit().putBoolean("cache_enabled", nextState).apply();
            SmartCacheProxy.setGlobalCacheEnabled(nextState);

            btnCacheToggle.setText(nextState ? "已开启" : "已关闭");
            btnCacheToggle.setTextColor(Color.parseColor(nextState ? UiTheme.C_GREEN : UiTheme.C_DIM));
            btnCacheToggle.setBackground(UiTheme.roundRect(this,
                    nextState ? "#0D2818" : UiTheme.C_SURFACE_ALT,
                    nextState ? UiTheme.C_GREEN : UiTheme.C_BORDER, 1, 3));

            Toast.makeText(this, nextState ? "智能缓存已开启 (5ms 极速秒回)" : "智能缓存已关闭 (全量直通模型)", Toast.LENGTH_SHORT).show();
        });
        top.addView(btnCacheToggle);

        TextView btnViewCache = UiTheme.createButton(this, "查看", UiTheme.C_CYAN, "#0A2328", UiTheme.C_CYAN, 3);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vlp.leftMargin = UiTheme.dp(this, 5);
        btnViewCache.setLayoutParams(vlp);
        btnViewCache.setOnClickListener(v -> showCacheViewerDialog());
        top.addView(btnViewCache);

        TextView clearCacheBtn = UiTheme.createButton(this, "清空", UiTheme.C_DIM, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 3);
        LinearLayout.LayoutParams clpBtn = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clpBtn.leftMargin = UiTheme.dp(this, 5);
        clearCacheBtn.setLayoutParams(clpBtn);
        clearCacheBtn.setOnClickListener(v -> {
            ResponseCacheDb.getInstance(this).clear();
            refreshMetricsView();
            Toast.makeText(this, "本地响应缓存已清空", Toast.LENGTH_SHORT).show();
        });
        top.addView(clearCacheBtn);
        card.addView(top);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        View item1 = createKpiItem("缓存命中", "0 次", UiTheme.C_CYAN);
        metricCacheHits = item1.findViewById(android.R.id.text1);
        row.addView(item1, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        View item2 = createKpiItem("节省 Token", "~0", "#E3B341");
        metricSavedTokens = item2.findViewById(android.R.id.text1);
        row.addView(item2, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        View item3 = createKpiItem("已缓存词条", "0 条", UiTheme.C_PURPLE);
        metricCachedCount = item3.findViewById(android.R.id.text1);
        row.addView(item3, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        card.addView(row);
        return card;
    }

    private View createKpiItem(String title, String defaultValue, String accentColor) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(UiTheme.dp(this, 4), UiTheme.dp(this, 2), UiTheme.dp(this, 4), UiTheme.dp(this, 2));

        TextView tvTitle = new TextView(this);
        tvTitle.setText(title);
        tvTitle.setTextSize(10);
        tvTitle.setTextColor(Color.parseColor(UiTheme.C_DIM));
        box.addView(tvTitle);

        TextView tvVal = new TextView(this);
        tvVal.setId(android.R.id.text1);
        tvVal.setText(defaultValue);
        tvVal.setTextSize(15);
        tvVal.setTypeface(Typeface.DEFAULT_BOLD);
        tvVal.setTextColor(Color.parseColor(accentColor));
        tvVal.setPadding(0, UiTheme.dp(this, 2), 0, 0);
        box.addView(tvVal);

        return box;
    }

    /** 访问审计流水区 */
    private View buildAuditSection() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(UiTheme.roundRect(this, UiTheme.C_SURFACE, UiTheme.C_BORDER, 1, 6));
        card.setPadding(UiTheme.dp(this, 10), UiTheme.dp(this, 6), UiTheme.dp(this, 10), UiTheme.dp(this, 6));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(0, 0, 0, UiTheme.dp(this, 4));

        TextView title = new TextView(this);
        title.setText("最近访问审计 (50条)");
        title.setTextSize(11.5f);
        title.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView refreshBtn = UiTheme.createButton(this, "⟳", UiTheme.C_TEXT, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 3);
        refreshBtn.setOnClickListener(v -> refreshMetricsView());
        top.addView(refreshBtn);

        TextView resetBtn = UiTheme.createButton(this, "重置", UiTheme.C_DIM, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 3);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.leftMargin = UiTheme.dp(this, 5);
        resetBtn.setLayoutParams(rlp);
        resetBtn.setOnClickListener(v -> {
            MetricsTracker.getInstance().reset();
            refreshMetricsView();
            Toast.makeText(this, "统计指标已重置", Toast.LENGTH_SHORT).show();
        });
        top.addView(resetBtn);
        card.addView(top);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#080B0F"));
        scroll.setFillViewport(true);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        metricsLogList = new LinearLayout(this);
        metricsLogList.setOrientation(LinearLayout.VERTICAL);
        metricsLogList.setPadding(UiTheme.dp(this, 6), UiTheme.dp(this, 4), UiTheme.dp(this, 6), UiTheme.dp(this, 4));
        scroll.addView(metricsLogList);

        card.addView(scroll);
        return card;
    }

    private void refreshMetricsView() {
        MetricsTracker mt = MetricsTracker.getInstance();
        if (metricTotalRequests != null) {
            metricTotalRequests.setText(String.valueOf(mt.getTotalRequests()));
        }
        if (metricAvgLatency != null) {
            metricAvgLatency.setText(mt.getAverageLatencyMs() + " ms");
        }
        if (metricSuccessRate != null) {
            metricSuccessRate.setText(String.format(Locale.getDefault(), "%.1f%%", mt.getSuccessRate()));
        }
        if (metricUptime != null) {
            metricUptime.setText(mt.getFormattedUptime());
        }

        ResponseCacheDb cacheDb = ResponseCacheDb.getInstance(this);
        if (metricCacheHits != null) {
            metricCacheHits.setText(cacheDb.getTotalHits() + " 次");
        }
        if (metricSavedTokens != null) {
            metricSavedTokens.setText("~" + cacheDb.getTotalSavedTokens());
        }
        if (metricCachedCount != null) {
            metricCachedCount.setText(cacheDb.getCachedEntriesCount() + " 条");
        }

        if (metricsLogList != null) {
            metricsLogList.removeAllViews();
            List<MetricsTracker.RequestRecord> list = mt.getRecentRecords();
            if (list.isEmpty()) {
                TextView empty = new TextView(this);
                empty.setText("暂无调用记录（启动服务后通过客户端发起请求即可记录）");
                empty.setTextSize(10.5f);
                empty.setTextColor(Color.parseColor(UiTheme.C_DIM));
                empty.setPadding(UiTheme.dp(this, 4), UiTheme.dp(this, 8), UiTheme.dp(this, 4), UiTheme.dp(this, 8));
                metricsLogList.addView(empty);
                return;
            }

            for (MetricsTracker.RequestRecord rec : list) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(0, UiTheme.dp(this, 3), 0, UiTheme.dp(this, 3));

                // 状态码徽章（区分普通请求与缓存秒回请求）
                TextView badge = new TextView(this);
                boolean isCacheHit = rec.path.contains("缓存") || "5ms".equals(rec.latency);
                boolean ok = (rec.statusCode >= 200 && rec.statusCode < 400);

                if (isCacheHit) {
                    badge.setText("⚡200 CACHE");
                    badge.setTextColor(Color.parseColor(UiTheme.C_CYAN));
                    badge.setBackground(UiTheme.roundRect(this, "#0A2328", UiTheme.C_CYAN, 1, 2));
                } else {
                    badge.setText(String.valueOf(rec.statusCode));
                    badge.setTextColor(Color.parseColor(ok ? UiTheme.C_GREEN : UiTheme.C_RED));
                    badge.setBackground(UiTheme.roundRect(this, ok ? "#0D2818" : "#2D1214", ok ? UiTheme.C_GREEN : UiTheme.C_RED, 1, 2));
                }
                badge.setTextSize(9);
                badge.setTypeface(Typeface.MONOSPACE);
                badge.setPadding(UiTheme.dp(this, 4), UiTheme.dp(this, 1), UiTheme.dp(this, 4), UiTheme.dp(this, 1));
                row.addView(badge);

                // 延迟
                TextView tvLat = new TextView(this);
                tvLat.setText(" " + rec.latency);
                tvLat.setTextSize(10);
                tvLat.setTextColor(Color.parseColor(isCacheHit ? UiTheme.C_GREEN : UiTheme.C_CYAN));
                tvLat.setTypeface(Typeface.MONOSPACE);
                row.addView(tvLat);

                // 路径
                TextView tvPath = new TextView(this);
                tvPath.setText(" " + rec.method + " " + rec.path);
                tvPath.setTextSize(10);
                tvPath.setTextColor(Color.parseColor(UiTheme.C_TEXT));
                tvPath.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                row.addView(tvPath);

                // 时间
                TextView tvTime = new TextView(this);
                tvTime.setText(rec.time);
                tvTime.setTextSize(9);
                tvTime.setTextColor(Color.parseColor(UiTheme.C_DIM));
                tvTime.setTypeface(Typeface.MONOSPACE);
                row.addView(tvTime);

                metricsLogList.addView(row);

                // 分割线
                View div = new View(this);
                div.setBackgroundColor(Color.parseColor("#151B23"));
                metricsLogList.addView(div, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UiTheme.dp(this, 1)));
            }
        }
    }

    private void startPeriodicMetricsUpdater() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (currentTab == 3) {
                    refreshMetricsView();
                }
                handler.postDelayed(this, 2500);
            }
        }, 2500);
    }

    /** 打开本地已缓存条目明细浏览器 */
    private void showCacheViewerDialog() {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(UiTheme.roundRect(this, UiTheme.C_BG, UiTheme.C_BORDER, 1, 8));
        root.setPadding(UiTheme.dp(this, 14), UiTheme.dp(this, 12), UiTheme.dp(this, 14), UiTheme.dp(this, 12));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        ResponseCacheDb db = ResponseCacheDb.getInstance(this);
        List<ResponseCacheDb.CacheEntry> entries = db.getAllEntries(100);

        TextView tvTitle = new TextView(this);
        tvTitle.setText("已缓存条目明细 (" + entries.size() + " 条)");
        tvTitle.setTextSize(13);
        tvTitle.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(tvTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView btnClose = UiTheme.createButton(this, "✕", UiTheme.C_DIM, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 3);
        btnClose.setOnClickListener(v -> dialog.dismiss());
        header.addView(btnClose);
        root.addView(header);

        View div = new View(this);
        div.setBackgroundColor(Color.parseColor(UiTheme.C_BORDER_SUB));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UiTheme.dp(this, 1));
        dlp.topMargin = UiTheme.dp(this, 8);
        dlp.bottomMargin = UiTheme.dp(this, 8);
        root.addView(div, dlp);

        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiTheme.dp(this, 380)));

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);

        if (entries.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("暂无缓存内容\n发起聊天或翻译请求后，命中内容将在此自动沉淀。");
            empty.setTextSize(11);
            empty.setTextColor(Color.parseColor(UiTheme.C_DIM));
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, UiTheme.dp(this, 40), 0, UiTheme.dp(this, 40));
            list.addView(empty);
        } else {
            for (ResponseCacheDb.CacheEntry entry : entries) {
                LinearLayout card = new LinearLayout(this);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setBackground(UiTheme.roundRect(this, UiTheme.C_SURFACE, UiTheme.C_BORDER_SUB, 1, 4));
                card.setPadding(UiTheme.dp(this, 8), UiTheme.dp(this, 6), UiTheme.dp(this, 8), UiTheme.dp(this, 6));
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                clp.bottomMargin = UiTheme.dp(this, 6);
                card.setLayoutParams(clp);

                LinearLayout cardTop = new LinearLayout(this);
                cardTop.setOrientation(LinearLayout.HORIZONTAL);
                cardTop.setGravity(Gravity.CENTER_VERTICAL);

                TextView modelBadge = new TextView(this);
                modelBadge.setText(entry.model);
                modelBadge.setTextSize(9);
                modelBadge.setTextColor(Color.parseColor(UiTheme.C_BLUE));
                modelBadge.setTypeface(Typeface.MONOSPACE);
                cardTop.addView(modelBadge, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                TextView hitBadge = new TextView(this);
                hitBadge.setText("命中 " + entry.hitCount + "次 · 省 " + entry.tokenCount + " tok ");
                hitBadge.setTextSize(9);
                hitBadge.setTextColor(Color.parseColor(UiTheme.C_CYAN));
                cardTop.addView(hitBadge);

                TextView btnDel = UiTheme.createButton(this, "删除", UiTheme.C_RED, "#2D1214", "#4E1C1F", 2);
                btnDel.setOnClickListener(v -> {
                    db.deleteEntry(entry.cacheKey);
                    refreshMetricsView();
                    dialog.dismiss();
                    showCacheViewerDialog();
                });
                cardTop.addView(btnDel);
                card.addView(cardTop);

                if (!entry.promptSummary.isEmpty()) {
                    TextView tvPrompt = new TextView(this);
                    tvPrompt.setText("Q: " + entry.promptSummary);
                    tvPrompt.setTextSize(10);
                    tvPrompt.setTextColor(Color.parseColor(UiTheme.C_DIM));
                    tvPrompt.setPadding(0, UiTheme.dp(this, 2), 0, UiTheme.dp(this, 2));
                    card.addView(tvPrompt);
                }

                TextView tvContent = new TextView(this);
                String preview = entry.responseContent.trim();
                if (preview.length() > 120) preview = preview.substring(0, 120) + "...";
                tvContent.setText("A: " + preview);
                tvContent.setTextSize(10.5f);
                tvContent.setTextColor(Color.parseColor(UiTheme.C_TEXT));
                tvContent.setMaxLines(3);
                card.addView(tvContent);

                list.addView(card);
            }
        }

        scroll.addView(list);
        root.addView(scroll);

        dialog.setContentView(root);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
    }

    // ==================== 7. 通用日志清洗与色彩语法 ====================

    private String stripDateTime(String line) {
        if (line == null) return "";
        String res = line;
        res = res.replaceFirst("^\\[?\\d{4}[-/]\\d{2}[-/]\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?(Z|[+-]\\d{2}:?\\d{2})?\\]?\\s*", "");
        res = res.replaceFirst("^\\[?\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?\\]?\\s*", "");
        res = res.replaceFirst("^\\[-+\\]\\s*", "");
        res = res.replace("[info ]", "[info]");
        return res;
    }

    private int getLogColor(String line, int index) {
        String lower = line.toLowerCase(Locale.ROOT);
        if (lower.contains("error") || lower.contains("fail") || lower.contains("exception") || line.contains("❌") || line.contains("!!!")) {
            return Color.parseColor("#FF7B72");
        }
        if (lower.contains("warn") || line.contains("⚠")) {
            return Color.parseColor("#FFA657");
        }
        if (lower.contains("started") || lower.contains("listening") || lower.contains("success") || line.contains("✅") || line.contains("===") || line.contains("⚡")) {
            return Color.parseColor("#7EE787");
        }
        return (index % 2 == 0) ? Color.parseColor("#79C0FF") : Color.parseColor("#E6EDF3");
    }

    private void appendStyledLog(LinearLayout container, StringBuilder fullLog, ScrollView sv, String rawLine, int type) {
        String cleanLine = stripDateTime(rawLine);
        if (cleanLine.trim().isEmpty() && rawLine.trim().isEmpty()) return;

        fullLog.append(cleanLine).append("\n");

        int index;
        if (type == 0) index = serviceLogLineIndex++;
        else if (type == 1) index = tunnelLogLineIndex++;
        else index = tsLogLineIndex++;

        int color = getLogColor(cleanLine, index);

        if (container.getChildCount() > 0) {
            View div = new View(this);
            div.setBackgroundColor(Color.parseColor("#1C232E"));
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, UiTheme.dp(this, 1));
            dlp.topMargin = UiTheme.dp(this, 3);
            dlp.bottomMargin = UiTheme.dp(this, 3);
            container.addView(div, dlp);
        }

        TextView tv = new TextView(this);
        tv.setText(cleanLine);
        tv.setTextSize(10.5f);
        tv.setTextColor(color);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setPadding(UiTheme.dp(this, 1), 0, UiTheme.dp(this, 1), 0);
        container.addView(tv);

        if (container.getChildCount() > 600) {
            container.removeViews(0, 100);
        }

        if (sv != null) {
            sv.post(() -> sv.fullScroll(ScrollView.FOCUS_DOWN));
        }
    }

    // ==================== 8. 服务控制 (CLIProxy, Tunnel, Tailscale) ====================

    private void showBinaryDownloadDialog(String compName, String compKey, Runnable onSuccessAction) {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(false);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackground(UiTheme.roundRect(this, UiTheme.C_SURFACE, UiTheme.C_BORDER, 1, 10));
        layout.setPadding(UiTheme.dp(this, 18), UiTheme.dp(this, 18), UiTheme.dp(this, 18), UiTheme.dp(this, 18));

        // 标题
        TextView title = new TextView(this);
        title.setText("📦 组件按需下载 · " + compName);
        title.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        title.setTextSize(15f);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        title.setPadding(0, 0, 0, UiTheme.dp(this, 8));
        layout.addView(title);

        // 提示信息
        TextView subtitle = new TextView(this);
        subtitle.setText("当前为 Lite 极速轻量安装包，底层核心二进制将从国内 Gitee 镜像高速下载并自动配置。");
        subtitle.setTextColor(Color.parseColor(UiTheme.C_DIM));
        subtitle.setTextSize(12f);
        subtitle.setPadding(0, 0, 0, UiTheme.dp(this, 14));
        layout.addView(subtitle);

        // 进度条
        ProgressBar progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        progressBar.setIndeterminate(false);
        layout.addView(progressBar);

        // 进度详情
        TextView tvProgress = new TextView(this);
        tvProgress.setText("准备连接下载节点...");
        tvProgress.setTextColor(Color.parseColor(UiTheme.C_BLUE));
        tvProgress.setTextSize(11f);
        tvProgress.setPadding(0, UiTheme.dp(this, 8), 0, UiTheme.dp(this, 4));
        layout.addView(tvProgress);

        // 状态信息
        TextView tvStatus = new TextView(this);
        tvStatus.setText("正在连接 Gitee 节点...");
        tvStatus.setTextColor(Color.parseColor(UiTheme.C_DIM));
        tvStatus.setTextSize(10f);
        tvStatus.setPadding(0, 0, 0, UiTheme.dp(this, 12));
        layout.addView(tvStatus);

        // 底部按钮栏
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

    private void startServer() {
        if (!BinaryDownloadManager.isGlibcReady(this)) {
            showBinaryDownloadDialog("Glibc 基础运行库", BinaryDownloadManager.KEY_GLIBC, this::startServer);
            return;
        }
        if (!BinaryDownloadManager.isCliproxyReady(this)) {
            showBinaryDownloadDialog("CLIProxy 核心代理", BinaryDownloadManager.KEY_CLIPROXY, this::startServer);
            return;
        }
        ProxyService.start(this);
        prefs.edit().putBoolean("running", true).apply();
        updateUI(true);
        startLogReader();
    }

    private void stopServer() {
        Intent intent = new Intent(this, ProxyService.class);
        intent.setAction(ProxyService.ACTION_STOP_SERVER);
        startService(intent);
        prefs.edit().putBoolean("running", false).apply();
        updateUI(false);
    }

    private void checkHealth() {
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

    private void openWebPanel(String url) {
        View panelView = webPanelManager.createWebPanelView(url);
        setContentView(panelView);
    }

    private void startTunnel() {
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

    private void stopTunnel() {
        Intent intent = new Intent(this, ProxyService.class);
        intent.setAction(ProxyService.ACTION_STOP_TUNNEL);
        startService(intent);
        prefs.edit().putBoolean("tunnel_running", false).apply();
        updateTunnelUI(false);
    }

    private void startTailscale() {
        if (!BinaryDownloadManager.isTailscaleReady(this)) {
            showBinaryDownloadDialog("Tailscale 组网组件", BinaryDownloadManager.KEY_TAILSCALE, this::startTailscale);
            return;
        }
        String authKey = editTsAuthKey.getText().toString().trim();
        String hostname = editTsHostname.getText().toString().trim();
        prefs.edit().putString("ts_auth_key", authKey)
                    .putString("ts_hostname", hostname)
                    .putBoolean("tailscale_running", true)
                    .apply();

        Intent intent = new Intent(this, ProxyService.class);
        intent.setAction(ProxyService.ACTION_START_TAILSCALE);
        intent.putExtra(ProxyService.EXTRA_TAILSCALE_AUTHKEY, authKey);
        intent.putExtra(ProxyService.EXTRA_TAILSCALE_HOSTNAME, hostname);
        startService(intent);

        updateTailscaleUI(true, "连接中...");
        startTailscaleLogReader();
    }

    private void stopTailscale() {
        Intent intent = new Intent(this, ProxyService.class);
        intent.setAction(ProxyService.ACTION_STOP_TAILSCALE);
        startService(intent);
        prefs.edit().putBoolean("tailscale_running", false).apply();
        updateTailscaleUI(false, "");
    }

    private void updateUI(boolean running) {
        this.isServerRunning = running;
        if (running) {
            statusIndicatorDot.setBackground(UiTheme.roundRect(this, UiTheme.C_GREEN, null, 0, 3));
            statusText.setText("运行中 (:" + appConfig.getPort() + ")");
            statusText.setTextColor(Color.parseColor(UiTheme.C_GREEN));
            btnStart.setEnabled(false);
            btnStart.setAlpha(0.35f);
            btnStop.setEnabled(true);
            btnStop.setAlpha(1.0f);
        } else {
            statusIndicatorDot.setBackground(UiTheme.roundRect(this, UiTheme.C_DIM, null, 0, 3));
            statusText.setText("已停止");
            statusText.setTextColor(Color.parseColor(UiTheme.C_DIM));
            btnStart.setEnabled(true);
            btnStart.setAlpha(1.0f);
            btnStop.setEnabled(false);
            btnStop.setAlpha(0.35f);
        }
    }

    private void updateTunnelUI(boolean running) {
        if (running) {
            tunnelStatusText.setText("启动中 / 运行中");
            tunnelStatusText.setTextColor(Color.parseColor(UiTheme.C_BLUE));
            if (btnTunnelUrlCopy != null) {
                btnTunnelUrlCopy.setEnabled(true);
                btnTunnelUrlCopy.setAlpha(1.0f);
            }
        } else {
            currentTunnelDomain = "";
            tunnelStatusText.setText("未启动");
            tunnelStatusText.setTextColor(Color.parseColor(UiTheme.C_DIM));
            if (btnTunnelUrlCopy != null) {
                btnTunnelUrlCopy.setEnabled(false);
                btnTunnelUrlCopy.setAlpha(0.35f);
            }
            refreshTunnelEndpoints();
            refreshAddressSection();
        }
    }

    private void updateTailscaleUI(boolean running, String ip) {
        if (running) {
            tsStatusText.setText(ip.isEmpty() ? "启动中..." : "已就绪");
            tsStatusText.setTextColor(Color.parseColor(ip.isEmpty() ? UiTheme.C_BLUE : UiTheme.C_GREEN));
            if (!ip.isEmpty()) {
                tsIpText.setText("http://" + ip + ":" + appConfig.getPort() + "/v1");
                btnTsIpCopy.setEnabled(true);
                btnTsIpCopy.setAlpha(1.0f);
            }
        } else {
            tsStatusText.setText("未连接");
            tsStatusText.setTextColor(Color.parseColor(UiTheme.C_DIM));
            btnTsIpCopy.setEnabled(false);
            btnTsIpCopy.setAlpha(0.35f);
        }
    }

    // ==================== 9. 日志监听线程与恢复 ====================

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
                        handler.post(() -> appendStyledLog(logContainer, allServiceLogBuilder, logScroll, raw, 0));
                        prefs.edit().putString("log", allServiceLogBuilder.toString()).apply();
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
                            appendStyledLog(tunnelLogContainer, allTunnelLogBuilder, tunnelLogScroll, raw, 1);
                            if (raw.contains(".trycloudflare.com")) {
                                int s = raw.indexOf("https://");
                                if (s != -1) {
                                    int e = raw.indexOf(".trycloudflare.com", s);
                                    if (e != -1) {
                                        String domain = raw.substring(s, e + 18);
                                        currentTunnelDomain = domain;
                                        tunnelStatusText.setText("已就绪");
                                        tunnelStatusText.setTextColor(Color.parseColor(UiTheme.C_GREEN));
                                        refreshTunnelEndpoints();
                                        refreshAddressSection();
                                    }
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
                            appendStyledLog(tsLogContainer, allTsLogBuilder, tsLogScroll, raw, 2);
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
        if (f.exists()) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f)))) {
                String line;
                while ((line = br.readLine()) != null) {
                    appendStyledLog(logContainer, allServiceLogBuilder, null, line, 0);
                }
                if (logScroll != null) logScroll.post(() -> logScroll.fullScroll(ScrollView.FOCUS_DOWN));
            } catch (Exception ignored) {}
        }

        File tf = new File(getFilesDir(), "tunnel.log");
        if (tf.exists()) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(tf)))) {
                String line;
                while ((line = br.readLine()) != null) {
                    appendStyledLog(tunnelLogContainer, allTunnelLogBuilder, null, line, 1);
                }
                if (tunnelLogScroll != null) tunnelLogScroll.post(() -> tunnelLogScroll.fullScroll(ScrollView.FOCUS_DOWN));
            } catch (Exception ignored) {}
        }

        File tsf = new File(getFilesDir(), "tailscale.log");
        if (tsf.exists()) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(tsf)))) {
                String line;
                while ((line = br.readLine()) != null) {
                    appendStyledLog(tsLogContainer, allTsLogBuilder, null, line, 2);
                }
                if (tsLogScroll != null) tsLogScroll.post(() -> tsLogScroll.fullScroll(ScrollView.FOCUS_DOWN));
            } catch (Exception ignored) {}
        }
    }

    // ==================== Tab 4: 关于 (About) ====================

    private View buildAboutPage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        scroll.setFillViewport(true);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setLayoutParams(new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        layout.setPadding(UiTheme.dp(this, 16), UiTheme.dp(this, 28), UiTheme.dp(this, 16), UiTheme.dp(this, 24));
        layout.setGravity(Gravity.CENTER_HORIZONTAL);

        // 1. 顶部 Header 居中区域
        ImageView iconView = new ImageView(this);
        iconView.setImageResource(R.mipmap.ic_launcher);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(UiTheme.dp(this, 62), UiTheme.dp(this, 62));
        ilp.bottomMargin = UiTheme.dp(this, 10);
        iconView.setLayoutParams(ilp);
        layout.addView(iconView);

        TextView tvAppName = new TextView(this);
        tvAppName.setText("CLIProxyAPI");
        tvAppName.setTextSize(18);
        tvAppName.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        tvAppName.setTypeface(Typeface.DEFAULT_BOLD);
        tvAppName.setGravity(Gravity.CENTER);
        layout.addView(tvAppName);

        TextView tvEdition = new TextView(this);
        String editionStr = "v1.2.0 · " + (BuildConfig.IS_LITE ? "Lite (1.2MB)" : "Full (50MB)");
        tvEdition.setText(editionStr);
        tvEdition.setTextSize(11);
        tvEdition.setTextColor(Color.parseColor(UiTheme.C_CYAN));
        tvEdition.setTypeface(Typeface.MONOSPACE);
        tvEdition.setGravity(Gravity.CENTER);
        tvEdition.setPadding(0, UiTheme.dp(this, 4), 0, UiTheme.dp(this, 20));
        layout.addView(tvEdition);

        // 2. iOS 极简单行卡片
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(UiTheme.roundRect(this, UiTheme.C_SURFACE, UiTheme.C_BORDER, 1, 8));
        card.setPadding(UiTheme.dp(this, 12), UiTheme.dp(this, 4), UiTheme.dp(this, 12), UiTheme.dp(this, 4));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        card.setLayoutParams(clp);

        // 行 1: 版本信息
        card.addView(buildAboutRow("版本信息", "v1.2.0 (3)", null, v -> copyToClipboard("版本号", "1.2.0")));
        card.addView(buildAboutDivider());

        // 行 2: 运行底座
        card.addView(buildAboutRow("运行底座", "PRoot / arm64", null, null));
        card.addView(buildAboutDivider());

        // 行 3: 开源仓库 (我发布的开源)
        card.addView(buildAboutRow("开源仓库", "GitHub ↗", UiTheme.C_BLUE, v -> openBrowserUrl("https://github.com/liaoyh9422-creator/CLIProxyAPI")));
        card.addView(buildAboutDivider());

        // 行 4: 核心上游
        card.addView(buildAboutRow("核心上游", "Upstream ↗", UiTheme.C_BLUE, v -> openBrowserUrl("https://github.com/router-for-me/CLIProxyAPI")));
        card.addView(buildAboutDivider());

        // 行 5: 交流群聊
        final String qqUrl = "https://qun.qq.com/universal-share/share?ac=1&authKey=SbZHuQnLpmbg5dCOJ7ep1ci0l9RR2wetQuESdr%2Fsl7gz0pYKt8q42cA1xf%2BIAEz%2F&busi_data=eyJncm91cENvZGUiOiI5NjQzODIyMDciLCJ0b2tlbiI6IlN4dFVKblp3OXpFbXBmMUFaa0pUODc2bUU5UkNTVjQxUTM2SkVHK1d5c0d2WnJvZnlUNnpiVXpMRUhBQ0xiMmsiLCJ1aW4iOiI5OTUyNzc4MiJ9&data=TnnV-z6q8BjHv3PbEoG_mSi-6ceVaXKShIveW_l8_dZ0WsV8MQ2xLwYFDqC7ihgCN-SI0n064l57DrQBaYW3Cw&svctype=4&tempid=h5_group_info";
        card.addView(buildAboutRow("交流群聊", "加入 QQ 群 ↗", UiTheme.C_CYAN, v -> openBrowserUrl(qqUrl)));
        card.addView(buildAboutDivider());

        // 行 6: 开源协议
        card.addView(buildAboutRow("开源协议", "MIT ↗", UiTheme.C_BLUE, v -> openBrowserUrl("https://github.com/liaoyh9422-creator/CLIProxyAPI/blob/main/LICENSE")));

        layout.addView(card);

        // 3. 底部版权 Footer
        TextView tvFooter = new TextView(this);
        tvFooter.setText("CLIProxyAPI for Android\nOpen source with MIT License");
        tvFooter.setTextSize(11);
        tvFooter.setTextColor(Color.parseColor(UiTheme.C_DIM));
        tvFooter.setGravity(Gravity.CENTER);
        tvFooter.setPadding(0, UiTheme.dp(this, 30), 0, UiTheme.dp(this, 10));
        layout.addView(tvFooter);

        scroll.addView(layout);
        return scroll;
    }

    private View buildAboutRow(String label, String value, String valueColorHex, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(UiTheme.dp(this, 4), UiTheme.dp(this, 12), UiTheme.dp(this, 4), UiTheme.dp(this, 12));

        TextView tvLabel = new TextView(this);
        tvLabel.setText(label);
        tvLabel.setTextSize(12.5f);
        tvLabel.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        tvLabel.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(tvLabel);

        TextView tvValue = new TextView(this);
        tvValue.setText(value);
        tvValue.setTextSize(12f);
        tvValue.setTypeface(Typeface.MONOSPACE);
        String color = (valueColorHex != null) ? valueColorHex : UiTheme.C_DIM;
        tvValue.setTextColor(Color.parseColor(color));
        row.addView(tvValue);

        if (onClick != null) {
            row.setClickable(true);
            row.setFocusable(true);
            row.setOnClickListener(onClick);
        }
        return row;
    }

    private View buildAboutDivider() {
        View div = new View(this);
        div.setBackgroundColor(Color.parseColor(UiTheme.C_BORDER_SUB));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UiTheme.dp(this, 1));
        div.setLayoutParams(dlp);
        return div;
    }

    private void openBrowserUrl(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开链接: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void copyToClipboard(String label, String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText(label, text));
            Toast.makeText(this, "已复制 " + label, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onBackPressed() {
        if (pageGuestShare != null && pageGuestShare.getVisibility() == View.VISIBLE) {
            closeGuestSharePage();
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
