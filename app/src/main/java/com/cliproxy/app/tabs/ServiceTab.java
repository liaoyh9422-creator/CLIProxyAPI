package com.cliproxy.app.tabs;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.cliproxy.app.MainActivity;
import com.cliproxy.app.config.AppConfig;
import com.cliproxy.app.ui.UiTheme;
import com.cliproxy.core.mdns.MdnsManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

/**
 * ServiceTab: Tab 0 服务主页与 API 端点控制台
 * 包含代理服务生命周期控制、本地请求端点自动轮换、API Key与端口安全管理、服务终端日志
 */
public class ServiceTab {
    private static final String TAG = "ServiceTab";

    private final MainActivity activity;
    private View rootView;

    // 服务状态与操作按键
    private TextView statusText;
    private View statusIndicatorDot;
    private TextView btnStart, btnStop;
    private TextView portBadge;
    private TextView proxyBadge;
    private TextView tvApiKeyDisplay;
    private LinearLayout addressCardContainer;
    private boolean isMasterKeyMasked = true;

    // 服务日志组件
    private LinearLayout logContainer;
    private ScrollView logScroll;
    private final StringBuilder allServiceLogBuilder = new StringBuilder();
    private int serviceLogLineIndex = 0;

    // 请求端点自动轮换变换组件
    private final String[] ENDPOINT_SUFFIXES = {"/v1", "/v1/chat/completions", "/v1/responses"};
    private int lanEndpointIndex = 0;
    private TextView lanEndpointUrlView;
    private String currentLanPrefix = "";

    public ServiceTab(MainActivity activity) {
        this.activity = activity;
    }

    public View createView() {
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        layout.setPadding(UiTheme.dp(activity, 12), UiTheme.dp(activity, 8), UiTheme.dp(activity, 12), UiTheme.dp(activity, 4));

        layout.addView(buildHeroHeader());
        View addrCard = buildAddressSection();
        LinearLayout.LayoutParams acLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        acLp.topMargin = UiTheme.dp(activity, 6);
        addrCard.setLayoutParams(acLp);
        layout.addView(addrCard);

        View logCard = buildLogSection();
        LinearLayout.LayoutParams lclp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        lclp.topMargin = UiTheme.dp(activity, 6);
        logCard.setLayoutParams(lclp);
        layout.addView(logCard);

        rootView = layout;
        loadApiKeyFromConfigFile();
        return rootView;
    }

    public View getView() {
        return rootView;
    }

    private View buildHeroHeader() {
        AppConfig appConfig = activity.getAppConfig();

        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(UiTheme.roundRect(activity, UiTheme.C_SURFACE, UiTheme.C_BORDER, 1, 6));
        card.setPadding(UiTheme.dp(activity, 12), UiTheme.dp(activity, 8), UiTheme.dp(activity, 12), UiTheme.dp(activity, 8));

        LinearLayout topRow = new LinearLayout(activity);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(activity);
        title.setText(appConfig.getProjectName());
        title.setTextSize(15);
        title.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        topRow.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView tagBadge = new TextView(activity);
        tagBadge.setText("AI Proxy_Lite");
        tagBadge.setTextSize(9.5f);
        tagBadge.setTextColor(Color.parseColor(UiTheme.C_PURPLE));
        tagBadge.setBackground(UiTheme.roundRect(activity, "#1A102F", UiTheme.C_PURPLE, 1, 3));
        tagBadge.setPadding(UiTheme.dp(activity, 4), UiTheme.dp(activity, 1), UiTheme.dp(activity, 4), UiTheme.dp(activity, 1));
        topRow.addView(tagBadge);

        portBadge = new TextView(activity);
        portBadge.setText(":" + appConfig.getPort() + " ✎");
        portBadge.setTextSize(10);
        portBadge.setTextColor(Color.parseColor(UiTheme.C_CYAN));
        portBadge.setTypeface(Typeface.MONOSPACE);
        portBadge.setBackground(UiTheme.roundRect(activity, "#0A2328", UiTheme.C_CYAN, 1, 3));
        portBadge.setPadding(UiTheme.dp(activity, 5), UiTheme.dp(activity, 1), UiTheme.dp(activity, 5), UiTheme.dp(activity, 1));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.leftMargin = UiTheme.dp(activity, 4);
        portBadge.setLayoutParams(plp);
        portBadge.setOnClickListener(v -> showPortEditDialog());
        topRow.addView(portBadge);

        proxyBadge = new TextView(activity);
        updateProxyBadge();
        LinearLayout.LayoutParams prxlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        prxlp.leftMargin = UiTheme.dp(activity, 4);
        proxyBadge.setLayoutParams(prxlp);
        proxyBadge.setOnClickListener(v -> activity.showOutboundProxyDialog());
        topRow.addView(proxyBadge);

        card.addView(topRow);

        LinearLayout statusRow = new LinearLayout(activity);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusRow.setPadding(0, UiTheme.dp(activity, 4), 0, UiTheme.dp(activity, 6));

        statusIndicatorDot = new View(activity);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(UiTheme.dp(activity, 6), UiTheme.dp(activity, 6));
        dlp.rightMargin = UiTheme.dp(activity, 4);
        statusIndicatorDot.setLayoutParams(dlp);
        statusIndicatorDot.setBackground(UiTheme.roundRect(activity, UiTheme.C_DIM, null, 0, 3));
        statusRow.addView(statusIndicatorDot);

        statusText = new TextView(activity);
        statusText.setText("已停止");
        statusText.setTextSize(11);
        statusText.setTextColor(Color.parseColor(UiTheme.C_DIM));
        statusRow.addView(statusText);
        card.addView(statusRow);

        LinearLayout btnRow = new LinearLayout(activity);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);

        btnStart = UiTheme.createButton(activity, "启动", UiTheme.C_GREEN, "#0D2818", UiTheme.C_GREEN, 3);
        btnStart.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        btnStart.setOnClickListener(v -> activity.startProxyServer());
        btnRow.addView(btnStart);

        TextView btnCheck = UiTheme.createButton(activity, "检查", UiTheme.C_CYAN, "#0A2328", UiTheme.C_CYAN, 3);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        clp.leftMargin = UiTheme.dp(activity, 5);
        btnCheck.setLayoutParams(clp);
        btnCheck.setOnClickListener(v -> activity.checkHealth());
        btnRow.addView(btnCheck);

        TextView btnPanel = UiTheme.createButton(activity, "管理", UiTheme.C_BLUE, "#0D2240", UiTheme.C_BLUE, 3);
        LinearLayout.LayoutParams pmlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        pmlp.leftMargin = UiTheme.dp(activity, 5);
        btnPanel.setLayoutParams(pmlp);
        btnPanel.setOnClickListener(v -> activity.openWebPanel(appConfig.getManagementUrl()));
        btnRow.addView(btnPanel);

        btnStop = UiTheme.createButton(activity, "停止", UiTheme.C_RED, "#2D1214", "#4E1C1F", 3);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        slp.leftMargin = UiTheme.dp(activity, 5);
        btnStop.setLayoutParams(slp);
        btnStop.setOnClickListener(v -> activity.stopProxyServer());
        btnRow.addView(btnStop);

        card.addView(btnRow);
        return card;
    }

    public void updateProxyBadge() {
        if (proxyBadge == null) return;
        boolean proxyEnabled = activity.getPrefs().getBoolean("outbound_proxy_enabled", false);
        if (proxyEnabled) {
            proxyBadge.setText("代理:开");
            proxyBadge.setTextColor(Color.parseColor(UiTheme.C_GREEN));
            proxyBadge.setBackground(UiTheme.roundRect(activity, "#0D2818", UiTheme.C_GREEN, 1, 3));
        } else {
            proxyBadge.setText("代理:关");
            proxyBadge.setTextColor(Color.parseColor(UiTheme.C_DIM));
            proxyBadge.setBackground(UiTheme.roundRect(activity, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 1, 3));
        }
        proxyBadge.setTextSize(10);
        proxyBadge.setTypeface(Typeface.MONOSPACE);
        proxyBadge.setPadding(UiTheme.dp(activity, 5), UiTheme.dp(activity, 1), UiTheme.dp(activity, 5), UiTheme.dp(activity, 1));
    }

    private View buildAddressSection() {
        addressCardContainer = new LinearLayout(activity);
        addressCardContainer.setOrientation(LinearLayout.VERTICAL);
        addressCardContainer.setBackground(UiTheme.roundRect(activity, UiTheme.C_SURFACE, UiTheme.C_BORDER, 1, 6));
        addressCardContainer.setPadding(UiTheme.dp(activity, 12), UiTheme.dp(activity, 8), UiTheme.dp(activity, 12), UiTheme.dp(activity, 8));

        refreshAddressSection();
        return addressCardContainer;
    }

    public void refreshAddressSection() {
        if (addressCardContainer == null) return;
        addressCardContainer.removeAllViews();

        AppConfig appConfig = activity.getAppConfig();

        // 顶栏：标题 + mDNS 独立热切换开关
        LinearLayout top = new LinearLayout(activity);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(0, 0, 0, UiTheme.dp(activity, 6));

        TextView title = new TextView(activity);
        title.setText("API 端点");
        title.setTextSize(12);
        title.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        boolean isMdnsOn = activity.getPrefs().getBoolean("mdns_enabled", false);
        TextView btnMdnsToggle = UiTheme.createButton(activity, isMdnsOn ? "mDNS: 已开启" : "mDNS: 已关闭",
                isMdnsOn ? UiTheme.C_GREEN : UiTheme.C_DIM,
                isMdnsOn ? "#0D2818" : UiTheme.C_SURFACE_ALT,
                isMdnsOn ? UiTheme.C_GREEN : UiTheme.C_BORDER, 3);
        btnMdnsToggle.setOnClickListener(v -> {
            boolean nextState = !activity.getPrefs().getBoolean("mdns_enabled", false);
            activity.getPrefs().edit().putBoolean("mdns_enabled", nextState).apply();

            if (activity.isServerRunning()) {
                if (nextState) {
                    MdnsManager.getInstance(activity).start(appConfig.getPort());
                } else {
                    MdnsManager.getInstance(activity).stop();
                }
            }
            refreshAddressSection();
            Toast.makeText(activity, nextState ?
                    "mDNS 广播已开启 (http://cliproxy.local:" + appConfig.getPort() + "/v1)" :
                    "mDNS 广播已关闭 (已释放组播锁，切换为本地请求端点)", Toast.LENGTH_SHORT).show();
        });
        top.addView(btnMdnsToggle);
        addressCardContainer.addView(top);

        String currentTunnelDomain = activity.getCurrentTunnelDomain();
        if (!currentTunnelDomain.isEmpty()) {
            addressCardContainer.addView(buildCyclingEndpointRow("全球公网请求端点 (Cloudflare)", currentTunnelDomain));
            View divExt = new View(activity);
            divExt.setBackgroundColor(Color.parseColor(UiTheme.C_BORDER_SUB));
            LinearLayout.LayoutParams dlpe = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UiTheme.dp(activity, 1));
            dlpe.topMargin = UiTheme.dp(activity, 4);
            dlpe.bottomMargin = UiTheme.dp(activity, 4);
            addressCardContainer.addView(divExt, dlpe);
        }

        String localPrefix = isMdnsOn ?
                ("http://" + MdnsManager.HOST_NAME + ":" + appConfig.getPort()) :
                ("http://127.0.0.1:" + appConfig.getPort());
        addressCardContainer.addView(buildCyclingEndpointRow(
                isMdnsOn ? "局域网请求端点 (mDNS)" : "本地请求端点",
                localPrefix));

        View div1 = new View(activity);
        div1.setBackgroundColor(Color.parseColor(UiTheme.C_BORDER_SUB));
        LinearLayout.LayoutParams dlp1 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UiTheme.dp(activity, 1));
        dlp1.topMargin = UiTheme.dp(activity, 4);
        dlp1.bottomMargin = UiTheme.dp(activity, 4);
        addressCardContainer.addView(div1, dlp1);

        // 访问密钥 (API Key)
        addressCardContainer.addView(buildApiKeyRow());
    }

    private View buildCyclingEndpointRow(String title, String hostPrefix) {
        currentLanPrefix = hostPrefix;

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, UiTheme.dp(activity, 5), 0, UiTheme.dp(activity, 5));

        // 顶层标题
        TextView label = new TextView(activity);
        label.setText("● " + title);
        label.setTextSize(10.5f);
        label.setTextColor(Color.parseColor(UiTheme.C_BLUE));
        label.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(label);

        // 底层端点地址展示与复制按钮
        LinearLayout bottom = new LinearLayout(activity);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setPadding(0, UiTheme.dp(activity, 2), 0, 0);

        TextView tvUrl = new TextView(activity);
        tvUrl.setTextSize(10.5f);
        tvUrl.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        tvUrl.setTypeface(Typeface.MONOSPACE);
        tvUrl.setText(hostPrefix + ENDPOINT_SUFFIXES[lanEndpointIndex]);
        tvUrl.setSingleLine(true);
        tvUrl.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        bottom.addView(tvUrl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        lanEndpointUrlView = tvUrl;

        TextView copyBtn = UiTheme.createButton(activity, "复制", UiTheme.C_TEXT, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 3);
        copyBtn.setOnClickListener(v -> activity.copyToClipboard(title, tvUrl.getText().toString()));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.leftMargin = UiTheme.dp(activity, 6);
        copyBtn.setLayoutParams(clp);
        bottom.addView(copyBtn);

        row.addView(bottom);
        return row;
    }

    public void updateCyclingEndpoint(int index) {
        this.lanEndpointIndex = index;
        if (lanEndpointUrlView != null && !currentLanPrefix.isEmpty()) {
            lanEndpointUrlView.setText(currentLanPrefix + ENDPOINT_SUFFIXES[lanEndpointIndex]);
        }
    }

    private View buildApiKeyRow() {
        AppConfig appConfig = activity.getAppConfig();

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, UiTheme.dp(activity, 5), 0, UiTheme.dp(activity, 5));

        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView labelView = new TextView(activity);
        labelView.setText("● 访问密钥 (API Key)");
        labelView.setTextSize(10.5f);
        labelView.setTextColor(Color.parseColor(UiTheme.C_BLUE));
        labelView.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(labelView);

        tvApiKeyDisplay = new TextView(activity);
        tvApiKeyDisplay.setText(isMasterKeyMasked ? maskKey(appConfig.getApiKey()) : appConfig.getApiKey());
        tvApiKeyDisplay.setTextSize(10.5f);
        tvApiKeyDisplay.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        tvApiKeyDisplay.setTypeface(Typeface.MONOSPACE);
        tvApiKeyDisplay.setPadding(0, UiTheme.dp(activity, 1), 0, 0);
        col.addView(tvApiKeyDisplay);

        row.addView(col);

        TextView eyeBtn = UiTheme.createButton(activity, isMasterKeyMasked ? "👁" : "👁‍🗨",
                isMasterKeyMasked ? UiTheme.C_DIM : UiTheme.C_CYAN,
                UiTheme.C_SURFACE_ALT,
                isMasterKeyMasked ? UiTheme.C_BORDER : UiTheme.C_CYAN, 3);
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        olp.rightMargin = UiTheme.dp(activity, 5);
        eyeBtn.setLayoutParams(olp);
        eyeBtn.setOnClickListener(v -> {
            isMasterKeyMasked = !isMasterKeyMasked;
            tvApiKeyDisplay.setText(isMasterKeyMasked ? maskKey(appConfig.getApiKey()) : appConfig.getApiKey());
            eyeBtn.setText(isMasterKeyMasked ? "👁" : "👁‍🗨");
            eyeBtn.setTextColor(Color.parseColor(isMasterKeyMasked ? UiTheme.C_DIM : UiTheme.C_CYAN));
            eyeBtn.setBackground(UiTheme.roundRect(activity, UiTheme.C_SURFACE_ALT,
                    isMasterKeyMasked ? UiTheme.C_BORDER : UiTheme.C_CYAN, 1, 3));
        });
        row.addView(eyeBtn);

        TextView editBtn = UiTheme.createButton(activity, "修改", UiTheme.C_CYAN, "#0A2328", UiTheme.C_CYAN, 3);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.rightMargin = UiTheme.dp(activity, 5);
        editBtn.setLayoutParams(elp);
        editBtn.setOnClickListener(v -> showApiKeyEditDialog());
        row.addView(editBtn);

        TextView copyBtn = UiTheme.createButton(activity, "复制", UiTheme.C_TEXT, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 3);
        copyBtn.setOnClickListener(v -> activity.copyToClipboard("API Key", appConfig.getApiKey()));
        row.addView(copyBtn);

        return row;
    }

    private View buildLogSection() {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(UiTheme.roundRect(activity, UiTheme.C_SURFACE, UiTheme.C_BORDER, 1, 6));
        card.setPadding(UiTheme.dp(activity, 10), UiTheme.dp(activity, 6), UiTheme.dp(activity, 10), UiTheme.dp(activity, 6));

        LinearLayout top = new LinearLayout(activity);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(0, 0, 0, UiTheme.dp(activity, 4));

        TextView title = new TextView(activity);
        title.setText("服务日志");
        title.setTextSize(11.5f);
        title.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView copyBtn = UiTheme.createButton(activity, "复制", UiTheme.C_TEXT, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 3);
        copyBtn.setOnClickListener(v -> activity.copyToClipboard("服务日志", allServiceLogBuilder.toString()));
        top.addView(copyBtn);

        TextView clearBtn = UiTheme.createButton(activity, "清空", UiTheme.C_DIM, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 3);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.leftMargin = UiTheme.dp(activity, 5);
        clearBtn.setLayoutParams(clp);
        clearBtn.setOnClickListener(v -> clearLog());
        top.addView(clearBtn);
        card.addView(top);

        logScroll = new ScrollView(activity);
        logScroll.setBackgroundColor(Color.parseColor("#080B0F"));
        logScroll.setFillViewport(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        logScroll.setLayoutParams(lp);

        logContainer = new LinearLayout(activity);
        logContainer.setOrientation(LinearLayout.VERTICAL);
        logContainer.setPadding(UiTheme.dp(activity, 6), UiTheme.dp(activity, 4), UiTheme.dp(activity, 6), UiTheme.dp(activity, 4));
        logScroll.addView(logContainer);

        card.addView(logScroll);
        return card;
    }

    public void updateServiceUI(boolean running) {
        if (statusIndicatorDot == null || statusText == null) return;
        if (running) {
            statusIndicatorDot.setBackground(UiTheme.roundRect(activity, UiTheme.C_GREEN, null, 0, 3));
            statusText.setText("运行中 (:" + activity.getAppConfig().getPort() + ")");
            statusText.setTextColor(Color.parseColor(UiTheme.C_GREEN));
            if (btnStart != null) {
                btnStart.setEnabled(false);
                btnStart.setAlpha(0.35f);
            }
            if (btnStop != null) {
                btnStop.setEnabled(true);
                btnStop.setAlpha(1.0f);
            }
        } else {
            statusIndicatorDot.setBackground(UiTheme.roundRect(activity, UiTheme.C_DIM, null, 0, 3));
            statusText.setText("已停止");
            statusText.setTextColor(Color.parseColor(UiTheme.C_DIM));
            if (btnStart != null) {
                btnStart.setEnabled(true);
                btnStart.setAlpha(1.0f);
            }
            if (btnStop != null) {
                btnStop.setEnabled(false);
                btnStop.setAlpha(0.35f);
            }
        }
    }

    public void appendLog(String rawLine) {
        if (logContainer != null) {
            LogHelper.appendStyledLog(activity, logContainer, allServiceLogBuilder, logScroll, rawLine, serviceLogLineIndex++);
        }
    }

    public void clearLog() {
        if (logContainer != null) {
            logContainer.removeAllViews();
            allServiceLogBuilder.setLength(0);
            serviceLogLineIndex = 0;
            activity.getPrefs().edit().remove("log").apply();
            Toast.makeText(activity, "日志已清空", Toast.LENGTH_SHORT).show();
        }
    }

    private void showPortEditDialog() {
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(UiTheme.roundRect(activity, UiTheme.C_BG, UiTheme.C_BORDER, 1, 8));
        root.setPadding(UiTheme.dp(activity, 16), UiTheme.dp(activity, 14), UiTheme.dp(activity, 16), UiTheme.dp(activity, 14));

        TextView tvTitle = new TextView(activity);
        tvTitle.setText("修改服务端口 (Port)");
        tvTitle.setTextSize(14);
        tvTitle.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(tvTitle);

        TextView tvSub = new TextView(activity);
        tvSub.setText("有效范围: 1024 ~ 65534 (修改后需重启服务)");
        tvSub.setTextSize(10.5f);
        tvSub.setTextColor(Color.parseColor(UiTheme.C_DIM));
        tvSub.setPadding(0, UiTheme.dp(activity, 2), 0, UiTheme.dp(activity, 10));
        root.addView(tvSub);

        EditText input = new EditText(activity);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(String.valueOf(activity.getAppConfig().getPort()));
        input.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        input.setTextSize(13);
        input.setTypeface(Typeface.MONOSPACE);
        input.setBackground(UiTheme.roundRect(activity, UiTheme.C_SURFACE, UiTheme.C_BORDER, 1, 4));
        input.setPadding(UiTheme.dp(activity, 10), UiTheme.dp(activity, 8), UiTheme.dp(activity, 10), UiTheme.dp(activity, 8));
        root.addView(input);

        LinearLayout btnRow = new LinearLayout(activity);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams brlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        brlp.topMargin = UiTheme.dp(activity, 12);
        btnRow.setLayoutParams(brlp);

        TextView btnCancel = UiTheme.createButton(activity, "取消", UiTheme.C_DIM, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 3);
        btnCancel.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        btnCancel.setOnClickListener(v -> dialog.dismiss());
        btnRow.addView(btnCancel);

        View spacer = new View(activity);
        btnRow.addView(spacer, new LinearLayout.LayoutParams(UiTheme.dp(activity, 8), 1));

        TextView btnSave = UiTheme.createButton(activity, "保存", UiTheme.C_CYAN, "#0A2328", UiTheme.C_CYAN, 3);
        btnSave.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        btnSave.setOnClickListener(v -> {
            String val = input.getText().toString().trim();
            try {
                int p = Integer.parseInt(val);
                if (p < 1024 || p > 65534) {
                    Toast.makeText(activity, "端口需在 1024 ~ 65534 之间", Toast.LENGTH_SHORT).show();
                    return;
                }
                updatePortInConfig(p);
                refreshAddressSection();
                dialog.dismiss();
                if (activity.isServerRunning()) {
                    Toast.makeText(activity, "端口已修改为 " + p + "，重启服务后生效", Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(activity, "端口已修改为 " + p, Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                Toast.makeText(activity, "请输入合法的端口号", Toast.LENGTH_SHORT).show();
            }
        });
        btnRow.addView(btnSave);
        root.addView(btnRow);

        dialog.setContentView(root);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setLayout(UiTheme.dp(activity, 300), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
    }

    private void showApiKeyEditDialog() {
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(UiTheme.roundRect(activity, UiTheme.C_BG, UiTheme.C_BORDER, 1, 8));
        root.setPadding(UiTheme.dp(activity, 16), UiTheme.dp(activity, 14), UiTheme.dp(activity, 16), UiTheme.dp(activity, 14));

        TextView tvTitle = new TextView(activity);
        tvTitle.setText("修改 API Key (访问密钥)");
        tvTitle.setTextSize(14);
        tvTitle.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(tvTitle);

        TextView tvSub = new TextView(activity);
        tvSub.setText("第三方客户端认证使用的 Bearer Token (修改后需重启服务)");
        tvSub.setTextSize(10.5f);
        tvSub.setTextColor(Color.parseColor(UiTheme.C_DIM));
        tvSub.setPadding(0, UiTheme.dp(activity, 2), 0, UiTheme.dp(activity, 10));
        root.addView(tvSub);

        EditText input = new EditText(activity);
        input.setText(activity.getAppConfig().getApiKey());
        input.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        input.setTextSize(12.5f);
        input.setTypeface(Typeface.MONOSPACE);
        input.setBackground(UiTheme.roundRect(activity, UiTheme.C_SURFACE, UiTheme.C_BORDER, 1, 4));
        input.setPadding(UiTheme.dp(activity, 10), UiTheme.dp(activity, 8), UiTheme.dp(activity, 10), UiTheme.dp(activity, 8));
        root.addView(input);

        LinearLayout btnRow = new LinearLayout(activity);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams brlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        brlp.topMargin = UiTheme.dp(activity, 12);
        btnRow.setLayoutParams(brlp);

        TextView btnCancel = UiTheme.createButton(activity, "取消", UiTheme.C_DIM, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 3);
        btnCancel.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        btnCancel.setOnClickListener(v -> dialog.dismiss());
        btnRow.addView(btnCancel);

        View spacer = new View(activity);
        btnRow.addView(spacer, new LinearLayout.LayoutParams(UiTheme.dp(activity, 8), 1));

        TextView btnSave = UiTheme.createButton(activity, "保存", UiTheme.C_CYAN, "#0A2328", UiTheme.C_CYAN, 3);
        btnSave.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        btnSave.setOnClickListener(v -> {
            String newKey = input.getText().toString().trim();
            if (newKey.isEmpty()) {
                Toast.makeText(activity, "密钥不能为空", Toast.LENGTH_SHORT).show();
                return;
            }
            updateApiKeyInConfig(newKey);
            dialog.dismiss();
            if (activity.isServerRunning()) {
                Toast.makeText(activity, "密钥已更新，重启服务后生效", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(activity, "密钥已保存", Toast.LENGTH_SHORT).show();
            }
        });
        btnRow.addView(btnSave);
        root.addView(btnRow);

        dialog.setContentView(root);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setLayout(UiTheme.dp(activity, 300), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
    }

    private void updatePortInConfig(int newPort) {
        activity.getAppConfig().setPort(newPort);
        if (portBadge != null) {
            portBadge.setText(":" + newPort + " ✎");
        }
        new Thread(() -> {
            try {
                File configFile = activity.getSecureConfigFile();
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
        activity.getAppConfig().setApiKey(newKey);
        if (tvApiKeyDisplay != null) {
            tvApiKeyDisplay.setText(isMasterKeyMasked ? maskKey(newKey) : newKey);
        }
        new Thread(() -> {
            try {
                File configFile = activity.getSecureConfigFile();
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

    public void loadApiKeyFromConfigFile() {
        new Thread(() -> {
            try {
                File configFile = activity.getSecureConfigFile();
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
                                    activity.getAppConfig().setApiKey(key);
                                    activity.getHandler().post(() -> {
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

    private String maskKey(String key) {
        if (key == null || key.isEmpty()) return "";
        if (key.length() <= 10) return "••••••••";
        return key.substring(0, 7) + "••••••••";
    }
}
