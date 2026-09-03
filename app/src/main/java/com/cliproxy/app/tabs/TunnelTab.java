package com.cliproxy.app.tabs;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.InputType;
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
import com.cliproxy.app.ui.UiTheme;
import com.cliproxy.core.cache.SmartCacheProxy;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * TunnelTab: Tab 1 外网穿透控制台 (Cloudflare Tunnel)
 * 包含全球公网访问通道、出站代理设置、外网共享API专属客用密钥配额管理与穿透终端日志
 */
public class TunnelTab {

    private final MainActivity activity;
    private FrameLayout rootContainer;
    private View mainTunnelLayout;

    // 外网隧道组件
    private TextView tunnelStatusText;
    private TextView tunnelProxyText;
    private TextView tunnelUrlText;
    private TextView btnTunnelUrlCopy;
    private LinearLayout tunnelEndpointsContainer;
    private LinearLayout tunnelLogContainer;
    private ScrollView tunnelLogScroll;
    private final StringBuilder allTunnelLogBuilder = new StringBuilder();
    private int tunnelLogLineIndex = 0;

    // 请求端点自动轮换变换组件
    private final String[] ENDPOINT_SUFFIXES = {"/v1", "/v1/chat/completions", "/v1/responses"};
    private int tunnelEndpointIndex = 0;
    private TextView tunnelEndpointUrlView;

    // 外网共享 API 专属管理组件
    private View pageGuestShare;
    private LinearLayout guestShareListContainer;
    private final Map<String, Boolean> guestKeyMaskMap = new HashMap<>();

    public TunnelTab(MainActivity activity) {
        this.activity = activity;
    }

    public View createView() {
        rootContainer = new FrameLayout(activity);
        rootContainer.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 1. 穿透主页面
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        layout.setPadding(UiTheme.dp(activity, 12), UiTheme.dp(activity, 8), UiTheme.dp(activity, 12), UiTheme.dp(activity, 4));

        layout.addView(UiTheme.buildSectionHeader(activity, "外网穿透 (Cloudflare)", null));
        layout.addView(buildTunnelSection());
        layout.addView(buildGuestShareEntryCard());

        View tunnelLogCard = buildTunnelLogSection();
        LinearLayout.LayoutParams tlclp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        tlclp.topMargin = UiTheme.dp(activity, 6);
        tunnelLogCard.setLayoutParams(tlclp);
        layout.addView(tunnelLogCard);

        mainTunnelLayout = layout;
        rootContainer.addView(mainTunnelLayout);

        // 2. 二级页面：外网共享 API 专属管理页
        pageGuestShare = buildGuestSharePage();
        pageGuestShare.setVisibility(View.GONE);
        rootContainer.addView(pageGuestShare);

        return rootContainer;
    }

    public View getView() {
        return rootContainer;
    }

    private View buildTunnelSection() {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(UiTheme.roundRect(activity, UiTheme.C_SURFACE, UiTheme.C_BORDER, 1, 6));
        card.setPadding(UiTheme.dp(activity, 12), UiTheme.dp(activity, 8), UiTheme.dp(activity, 12), UiTheme.dp(activity, 8));

        LinearLayout statusRow = new LinearLayout(activity);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView label = new TextView(activity);
        label.setText("穿透状态: ");
        label.setTextSize(11);
        label.setTextColor(Color.parseColor(UiTheme.C_DIM));
        statusRow.addView(label);

        tunnelStatusText = new TextView(activity);
        tunnelStatusText.setText("未启动");
        tunnelStatusText.setTextSize(11);
        tunnelStatusText.setTextColor(Color.parseColor(UiTheme.C_DIM));
        statusRow.addView(tunnelStatusText);
        card.addView(statusRow);

        LinearLayout proxyRow = new LinearLayout(activity);
        proxyRow.setOrientation(LinearLayout.HORIZONTAL);
        proxyRow.setGravity(Gravity.CENTER_VERTICAL);
        proxyRow.setPadding(0, UiTheme.dp(activity, 2), 0, UiTheme.dp(activity, 3));

        tunnelProxyText = new TextView(activity);
        updateTunnelProxyText();
        tunnelProxyText.setTextSize(10.5f);
        tunnelProxyText.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        proxyRow.addView(tunnelProxyText);

        TextView cfgProxyBtn = UiTheme.createButton(activity, "出站代理设置 ⚙", UiTheme.C_CYAN, "#0A2328", UiTheme.C_CYAN, 3);
        cfgProxyBtn.setOnClickListener(v -> activity.showOutboundProxyDialog());
        proxyRow.addView(cfgProxyBtn);
        card.addView(proxyRow);

        tunnelEndpointsContainer = new LinearLayout(activity);
        tunnelEndpointsContainer.setOrientation(LinearLayout.VERTICAL);
        refreshTunnelEndpoints();
        card.addView(tunnelEndpointsContainer);

        LinearLayout btnRow = new LinearLayout(activity);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams brlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        brlp.topMargin = UiTheme.dp(activity, 6);
        btnRow.setLayoutParams(brlp);

        TextView btnStartT = UiTheme.createButton(activity, "启动 Cloudflare 穿透", UiTheme.C_BLUE, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 3);
        btnStartT.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        btnStartT.setOnClickListener(v -> activity.startTunnel());
        btnRow.addView(btnStartT);

        TextView btnStopT = UiTheme.createButton(activity, "停止", UiTheme.C_RED, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 3);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.leftMargin = UiTheme.dp(activity, 5);
        btnStopT.setLayoutParams(slp);
        btnStopT.setOnClickListener(v -> activity.stopTunnel());
        btnRow.addView(btnStopT);

        card.addView(btnRow);
        return card;
    }

    public void updateTunnelProxyText() {
        if (tunnelProxyText == null) return;
        String proxyUrl = activity.getPrefs().getString("outbound_proxy_url", "").trim();
        if (proxyUrl.isEmpty()) {
            tunnelProxyText.setText("出站代理: 直连 (若穿透超时请配置)");
            tunnelProxyText.setTextColor(Color.parseColor(UiTheme.C_DIM));
        } else {
            tunnelProxyText.setText("出站代理: " + proxyUrl);
            tunnelProxyText.setTextColor(Color.parseColor(UiTheme.C_GREEN));
        }
    }

    public void refreshTunnelEndpoints() {
        if (tunnelEndpointsContainer == null) return;
        tunnelEndpointsContainer.removeAllViews();

        String domain = activity.getCurrentTunnelDomain();
        if (domain.isEmpty()) {
            LinearLayout urlRow = new LinearLayout(activity);
            urlRow.setOrientation(LinearLayout.HORIZONTAL);
            urlRow.setGravity(Gravity.CENTER_VERTICAL);
            urlRow.setPadding(0, UiTheme.dp(activity, 4), 0, UiTheme.dp(activity, 2));

            tunnelUrlText = new TextView(activity);
            tunnelUrlText.setText("https://...trycloudflare.com/v1");
            tunnelUrlText.setTextSize(10.5f);
            tunnelUrlText.setTextColor(Color.parseColor(UiTheme.C_DIM));
            tunnelUrlText.setTypeface(Typeface.MONOSPACE);
            tunnelUrlText.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            urlRow.addView(tunnelUrlText);

            btnTunnelUrlCopy = UiTheme.createButton(activity, "复制", UiTheme.C_DIM, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 3);
            btnTunnelUrlCopy.setEnabled(false);
            btnTunnelUrlCopy.setAlpha(0.35f);
            urlRow.addView(btnTunnelUrlCopy);
            tunnelEndpointsContainer.addView(urlRow);
        } else {
            tunnelEndpointsContainer.addView(buildCyclingEndpointRow("公网请求端点", domain));
        }
    }

    private View buildCyclingEndpointRow(String title, String hostPrefix) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, UiTheme.dp(activity, 5), 0, UiTheme.dp(activity, 5));

        TextView label = new TextView(activity);
        label.setText("● " + title);
        label.setTextSize(10.5f);
        label.setTextColor(Color.parseColor(UiTheme.C_BLUE));
        label.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(label);

        LinearLayout bottom = new LinearLayout(activity);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setPadding(0, UiTheme.dp(activity, 2), 0, 0);

        TextView tvUrl = new TextView(activity);
        tvUrl.setTextSize(10.5f);
        tvUrl.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        tvUrl.setTypeface(Typeface.MONOSPACE);
        tvUrl.setText(hostPrefix + ENDPOINT_SUFFIXES[tunnelEndpointIndex]);
        tvUrl.setSingleLine(true);
        tvUrl.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        bottom.addView(tvUrl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        tunnelEndpointUrlView = tvUrl;

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
        this.tunnelEndpointIndex = index;
        if (tunnelEndpointUrlView != null && !activity.getCurrentTunnelDomain().isEmpty()) {
            tunnelEndpointUrlView.setText(activity.getCurrentTunnelDomain() + ENDPOINT_SUFFIXES[tunnelEndpointIndex]);
        }
    }

    private View buildGuestShareEntryCard() {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(UiTheme.roundRect(activity, UiTheme.C_SURFACE, UiTheme.C_BORDER, 1, 6));
        card.setPadding(UiTheme.dp(activity, 12), UiTheme.dp(activity, 10), UiTheme.dp(activity, 12), UiTheme.dp(activity, 10));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = UiTheme.dp(activity, 6);
        card.setLayoutParams(clp);

        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = new TextView(activity);
        title.setText("外网共享API");
        title.setTextSize(12.5f);
        title.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(title);

        TextView sub = new TextView(activity);
        sub.setText("独立客用密钥 · 额度限制 · 一键分享");
        sub.setTextSize(10.5f);
        sub.setTextColor(Color.parseColor(UiTheme.C_DIM));
        sub.setPadding(0, UiTheme.dp(activity, 2), 0, 0);
        col.addView(sub);
        card.addView(col);

        TextView btnEnter = UiTheme.createButton(activity, "进入管理 ➔", UiTheme.C_CYAN, "#0A2328", UiTheme.C_CYAN, 3);
        btnEnter.setOnClickListener(v -> openGuestSharePage());
        card.addView(btnEnter);

        return card;
    }

    private View buildTunnelLogSection() {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(UiTheme.roundRect(activity, UiTheme.C_SURFACE, UiTheme.C_BORDER, 1, 6));
        card.setPadding(UiTheme.dp(activity, 10), UiTheme.dp(activity, 6), UiTheme.dp(activity, 10), UiTheme.dp(activity, 6));

        LinearLayout top = new LinearLayout(activity);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(0, 0, 0, UiTheme.dp(activity, 4));

        TextView title = new TextView(activity);
        title.setText("Cloudflare 隧道日志");
        title.setTextSize(11.5f);
        title.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView copyBtn = UiTheme.createButton(activity, "复制", UiTheme.C_TEXT, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 3);
        copyBtn.setOnClickListener(v -> activity.copyToClipboard("穿透日志", allTunnelLogBuilder.toString()));
        top.addView(copyBtn);

        TextView clearBtn = UiTheme.createButton(activity, "清空", UiTheme.C_DIM, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 3);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.leftMargin = UiTheme.dp(activity, 5);
        clearBtn.setLayoutParams(clp);
        clearBtn.setOnClickListener(v -> clearLog());
        top.addView(clearBtn);
        card.addView(top);

        tunnelLogScroll = new ScrollView(activity);
        tunnelLogScroll.setBackgroundColor(Color.parseColor("#080B0F"));
        tunnelLogScroll.setFillViewport(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        tunnelLogScroll.setLayoutParams(lp);

        tunnelLogContainer = new LinearLayout(activity);
        tunnelLogContainer.setOrientation(LinearLayout.VERTICAL);
        tunnelLogContainer.setPadding(UiTheme.dp(activity, 6), UiTheme.dp(activity, 4), UiTheme.dp(activity, 6), UiTheme.dp(activity, 4));
        tunnelLogScroll.addView(tunnelLogContainer);

        card.addView(tunnelLogScroll);
        return card;
    }

    public void updateTunnelUI(boolean running) {
        if (running) {
            if (tunnelStatusText != null) {
                tunnelStatusText.setText("启动中 / 运行中");
                tunnelStatusText.setTextColor(Color.parseColor(UiTheme.C_BLUE));
            }
            if (btnTunnelUrlCopy != null) {
                btnTunnelUrlCopy.setEnabled(true);
                btnTunnelUrlCopy.setAlpha(1.0f);
            }
        } else {
            if (tunnelStatusText != null) {
                tunnelStatusText.setText("未启动");
                tunnelStatusText.setTextColor(Color.parseColor(UiTheme.C_DIM));
            }
            if (btnTunnelUrlCopy != null) {
                btnTunnelUrlCopy.setEnabled(false);
                btnTunnelUrlCopy.setAlpha(0.35f);
            }
            refreshTunnelEndpoints();
        }
    }

    public void updateTunnelStatus(String status, int color) {
        if (tunnelStatusText != null) {
            tunnelStatusText.setText(status);
            tunnelStatusText.setTextColor(color);
        }
    }

    public void appendLog(String rawLine) {
        if (tunnelLogContainer != null) {
            LogHelper.appendStyledLog(activity, tunnelLogContainer, allTunnelLogBuilder, tunnelLogScroll, rawLine, tunnelLogLineIndex++);
        }
    }

    public void clearLog() {
        if (tunnelLogContainer != null) {
            tunnelLogContainer.removeAllViews();
            allTunnelLogBuilder.setLength(0);
            tunnelLogLineIndex = 0;
            Toast.makeText(activity, "穿透日志已清空", Toast.LENGTH_SHORT).show();
        }
    }

    // ==================== 外网共享 API 专属管理子页面 ====================

    public void openGuestSharePage() {
        if (mainTunnelLayout != null) mainTunnelLayout.setVisibility(View.GONE);
        if (pageGuestShare != null) {
            pageGuestShare.setVisibility(View.VISIBLE);
            refreshGuestShareList();
        }
    }

    public void closeGuestSharePage() {
        if (pageGuestShare != null) pageGuestShare.setVisibility(View.GONE);
        if (mainTunnelLayout != null) mainTunnelLayout.setVisibility(View.VISIBLE);
    }

    public boolean handleBackPressed() {
        if (pageGuestShare != null && pageGuestShare.getVisibility() == View.VISIBLE) {
            closeGuestSharePage();
            return true;
        }
        return false;
    }

    private View buildGuestSharePage() {
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        layout.setPadding(UiTheme.dp(activity, 12), UiTheme.dp(activity, 8), UiTheme.dp(activity, 12), UiTheme.dp(activity, 4));

        // 顶部操作栏
        LinearLayout topBar = new LinearLayout(activity);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(0, 0, 0, UiTheme.dp(activity, 8));

        TextView btnBack = UiTheme.createButton(activity, "← 返回", UiTheme.C_TEXT, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 3);
        btnBack.setOnClickListener(v -> closeGuestSharePage());
        topBar.addView(btnBack);

        TextView title = new TextView(activity);
        title.setText("外网共享 API 管理");
        title.setTextSize(14);
        title.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(UiTheme.dp(activity, 8), 0, 0, 0);
        topBar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView btnAdd = UiTheme.createButton(activity, "+ 新增客用Key", UiTheme.C_BLUE, "#1F2B3E", UiTheme.C_BLUE, 3);
        btnAdd.setOnClickListener(v -> showGuestKeyEditDialog(null, -1));
        topBar.addView(btnAdd);
        layout.addView(topBar);

        // 列表展示区
        ScrollView scroll = new ScrollView(activity);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        scroll.setFillViewport(true);

        guestShareListContainer = new LinearLayout(activity);
        guestShareListContainer.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(guestShareListContainer);

        layout.addView(scroll);
        return layout;
    }

    public void refreshGuestShareList() {
        if (guestShareListContainer == null) return;
        guestShareListContainer.removeAllViews();

        JSONArray list = getGuestKeysJsonArray();
        if (list.length() == 0) {
            TextView empty = new TextView(activity);
            empty.setText("暂无客用共享 Key。\n\n点击右上角「+ 新增客用Key」可为朋友分配专属密钥、限制 Token 消耗总量与 RPM 限频，并自动生成微信专属分享卡片！");
            empty.setTextSize(11);
            empty.setTextColor(Color.parseColor(UiTheme.C_DIM));
            empty.setPadding(UiTheme.dp(activity, 8), UiTheme.dp(activity, 20), UiTheme.dp(activity, 8), 0);
            guestShareListContainer.addView(empty);
            return;
        }

        for (int i = 0; i < list.length(); i++) {
            final int index = i;
            JSONObject obj = list.optJSONObject(i);
            if (obj == null) continue;
            final JSONObject item = obj;

            String key = obj.optString("key", "");
            String remark = obj.optString("remark", "未命名好友");
            String mode = obj.optString("mode", "TOKEN");
            long quotaTotal = obj.optLong("quotaTotal", 10_000_000L);
            long quotaRemaining = obj.optLong("quotaRemaining", quotaTotal);
            int rpm = obj.optInt("rpm", 5);

            if (!guestKeyMaskMap.containsKey(key)) {
                guestKeyMaskMap.put(key, true);
            }
            boolean isMasked = Boolean.TRUE.equals(guestKeyMaskMap.get(key));

            LinearLayout card = new LinearLayout(activity);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(UiTheme.roundRect(activity, UiTheme.C_SURFACE, UiTheme.C_BORDER, 1, 6));
            card.setPadding(UiTheme.dp(activity, 12), UiTheme.dp(activity, 8), UiTheme.dp(activity, 12), UiTheme.dp(activity, 8));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.bottomMargin = UiTheme.dp(activity, 8);
            card.setLayoutParams(clp);

            // 头部：● 备注名 + 模式标签 + RPM 标签
            LinearLayout top = new LinearLayout(activity);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);

            TextView tvRemark = new TextView(activity);
            tvRemark.setText("● " + remark);
            tvRemark.setTextSize(12);
            tvRemark.setTextColor(Color.parseColor(UiTheme.C_TEXT));
            tvRemark.setTypeface(Typeface.DEFAULT_BOLD);
            top.addView(tvRemark, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView modeTag = new TextView(activity);
            modeTag.setText("TOKEN".equals(mode) ? "Token计费" : "按次计费");
            modeTag.setTextSize(9.5f);
            modeTag.setTextColor(Color.parseColor(UiTheme.C_CYAN));
            modeTag.setBackground(UiTheme.roundRect(activity, "#0A2328", UiTheme.C_CYAN, 1, 3));
            modeTag.setPadding(UiTheme.dp(activity, 4), UiTheme.dp(activity, 1), UiTheme.dp(activity, 4), UiTheme.dp(activity, 1));
            LinearLayout.LayoutParams mtlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            mtlp.rightMargin = UiTheme.dp(activity, 4);
            modeTag.setLayoutParams(mtlp);
            top.addView(modeTag);

            TextView rpmTag = new TextView(activity);
            rpmTag.setText(rpm + " RPM 盾牌");
            rpmTag.setTextSize(9.5f);
            rpmTag.setTextColor(Color.parseColor(UiTheme.C_GREEN));
            rpmTag.setBackground(UiTheme.roundRect(activity, "#0D2818", UiTheme.C_GREEN, 1, 3));
            rpmTag.setPadding(UiTheme.dp(activity, 4), UiTheme.dp(activity, 1), UiTheme.dp(activity, 4), UiTheme.dp(activity, 1));
            top.addView(rpmTag);
            card.addView(top);

            // Key 行：Key 文本 + [👁] + [编辑] + [删除]
            LinearLayout keyRow = new LinearLayout(activity);
            keyRow.setOrientation(LinearLayout.HORIZONTAL);
            keyRow.setGravity(Gravity.CENTER_VERTICAL);
            keyRow.setPadding(0, UiTheme.dp(activity, 5), 0, UiTheme.dp(activity, 4));

            TextView tvKey = new TextView(activity);
            tvKey.setText(isMasked ? maskKey(key) : key);
            tvKey.setTextSize(10.5f);
            tvKey.setTextColor(Color.parseColor(UiTheme.C_BLUE));
            tvKey.setTypeface(Typeface.MONOSPACE);
            tvKey.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            keyRow.addView(tvKey);

            TextView eyeBtn = UiTheme.createButton(activity, isMasked ? "👁" : "👁‍🗨",
                    isMasked ? UiTheme.C_DIM : UiTheme.C_CYAN,
                    UiTheme.C_SURFACE_ALT,
                    isMasked ? UiTheme.C_BORDER : UiTheme.C_CYAN, 3);
            LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            elp.rightMargin = UiTheme.dp(activity, 4);
            eyeBtn.setLayoutParams(elp);
            eyeBtn.setOnClickListener(v -> {
                boolean next = !guestKeyMaskMap.get(key);
                guestKeyMaskMap.put(key, next);
                tvKey.setText(next ? maskKey(key) : key);
                eyeBtn.setText(next ? "👁" : "👁‍🗨");
                eyeBtn.setTextColor(Color.parseColor(next ? UiTheme.C_DIM : UiTheme.C_CYAN));
                eyeBtn.setBackground(UiTheme.roundRect(activity, UiTheme.C_SURFACE_ALT,
                        next ? UiTheme.C_BORDER : UiTheme.C_CYAN, 1, 3));
            });
            keyRow.addView(eyeBtn);

            TextView btnEdit = UiTheme.createButton(activity, "编辑", UiTheme.C_CYAN, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 3);
            LinearLayout.LayoutParams edlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            edlp.rightMargin = UiTheme.dp(activity, 4);
            btnEdit.setLayoutParams(edlp);
            btnEdit.setOnClickListener(v -> showGuestKeyEditDialog(item, index));
            keyRow.addView(btnEdit);

            TextView btnDel = UiTheme.createButton(activity, "删除", UiTheme.C_RED, "#280A0A", UiTheme.C_RED, 3);
            btnDel.setOnClickListener(v -> {
                JSONArray l = getGuestKeysJsonArray();
                JSONArray nextList = new JSONArray();
                for (int j = 0; j < l.length(); j++) {
                    if (j != index) nextList.put(l.optJSONObject(j));
                }
                saveGuestKeysJsonArray(nextList);
                activity.syncGuestKeysToProxyAndConfig();
                refreshGuestShareList();
                Toast.makeText(activity, "已销毁客用密钥: " + remark, Toast.LENGTH_SHORT).show();
            });
            keyRow.addView(btnDel);
            card.addView(keyRow);

            // 进度信息行
            long used = quotaTotal - quotaRemaining;
            int pct = (quotaTotal > 0) ? (int) (used * 100 / quotaTotal) : 0;
            TextView tvProgress = new TextView(activity);
            tvProgress.setText("剩余: " + SmartCacheProxy.formatQuota(quotaRemaining, mode) +
                    " / " + SmartCacheProxy.formatQuota(quotaTotal, mode) +
                    " (已用 " + pct + "%) · 限频: " + rpm + "次/分");
            tvProgress.setTextSize(10f);
            tvProgress.setTextColor(Color.parseColor(UiTheme.C_DIM));
            tvProgress.setPadding(0, 0, 0, UiTheme.dp(activity, 6));
            card.addView(tvProgress);

            // 一键复制专属分享卡片
            TextView btnShare = UiTheme.createButton(activity, "📋 复制专属分享卡片", UiTheme.C_GREEN, "#0D2818", UiTheme.C_GREEN, 3);
            btnShare.setOnClickListener(v -> copyGuestShareCard(item));
            card.addView(btnShare);

            guestShareListContainer.addView(card);
        }
    }

    private void showGuestKeyEditDialog(JSONObject existing, int editIndex) {
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackground(UiTheme.roundRect(activity, UiTheme.C_SURFACE, UiTheme.C_BORDER, 1, 8));
        layout.setPadding(UiTheme.dp(activity, 16), UiTheme.dp(activity, 14), UiTheme.dp(activity, 16), UiTheme.dp(activity, 14));

        TextView title = new TextView(activity);
        title.setText(existing == null ? "✨ 新增客用 Key" : "✎ 编辑客用 Key");
        title.setTextSize(13.5f);
        title.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        layout.addView(title);

        TextView lblRemark = new TextView(activity);
        lblRemark.setText("备注名称 (如: 张同事、写代码专用)");
        lblRemark.setTextSize(10.5f);
        lblRemark.setTextColor(Color.parseColor(UiTheme.C_DIM));
        lblRemark.setPadding(0, UiTheme.dp(activity, 8), 0, UiTheme.dp(activity, 2));
        layout.addView(lblRemark);

        EditText etRemark = new EditText(activity);
        etRemark.setText(existing != null ? existing.optString("remark", "") : "");
        etRemark.setHint("好友/场景备注");
        etRemark.setTextSize(11.5f);
        etRemark.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        etRemark.setHintTextColor(Color.parseColor(UiTheme.C_DIM));
        etRemark.setBackground(UiTheme.roundRect(activity, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 1, 4));
        etRemark.setPadding(UiTheme.dp(activity, 8), UiTheme.dp(activity, 6), UiTheme.dp(activity, 8), UiTheme.dp(activity, 6));
        layout.addView(etRemark);

        TextView lblKey = new TextView(activity);
        lblKey.setText("专属 Key 字符串 (可自定义)");
        lblKey.setTextSize(10.5f);
        lblKey.setTextColor(Color.parseColor(UiTheme.C_DIM));
        lblKey.setPadding(0, UiTheme.dp(activity, 8), 0, UiTheme.dp(activity, 2));
        layout.addView(lblKey);

        String autoKey = "sk-guest-" + Long.toHexString(System.currentTimeMillis() ^ (long) (Math.random() * 0xFFFF));
        if (autoKey.length() > 13) autoKey = autoKey.substring(0, 13);

        EditText etKey = new EditText(activity);
        etKey.setText(existing != null ? existing.optString("key", autoKey) : autoKey);
        etKey.setTextSize(11.5f);
        etKey.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        etKey.setTypeface(Typeface.MONOSPACE);
        etKey.setBackground(UiTheme.roundRect(activity, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 1, 4));
        etKey.setPadding(UiTheme.dp(activity, 8), UiTheme.dp(activity, 6), UiTheme.dp(activity, 8), UiTheme.dp(activity, 6));
        layout.addView(etKey);

        TextView lblMode = new TextView(activity);
        lblMode.setText("计费模式选择");
        lblMode.setTextSize(10.5f);
        lblMode.setTextColor(Color.parseColor(UiTheme.C_DIM));
        lblMode.setPadding(0, UiTheme.dp(activity, 8), 0, UiTheme.dp(activity, 3));
        layout.addView(lblMode);

        final String[] curMode = { existing != null ? existing.optString("mode", "TOKEN") : "TOKEN" };

        LinearLayout modeRow = new LinearLayout(activity);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);

        TextView btnModeToken = UiTheme.createButton(activity, "按 Token 限制 (10M~100M)",
                "TOKEN".equals(curMode[0]) ? UiTheme.C_CYAN : UiTheme.C_DIM,
                "TOKEN".equals(curMode[0]) ? "#0A2328" : UiTheme.C_SURFACE_ALT,
                "TOKEN".equals(curMode[0]) ? UiTheme.C_CYAN : UiTheme.C_BORDER, 3);
        btnModeToken.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f));

        TextView btnModeCount = UiTheme.createButton(activity, "按次数限制",
                "COUNT".equals(curMode[0]) ? UiTheme.C_CYAN : UiTheme.C_DIM,
                "COUNT".equals(curMode[0]) ? "#0A2328" : UiTheme.C_SURFACE_ALT,
                "COUNT".equals(curMode[0]) ? UiTheme.C_CYAN : UiTheme.C_BORDER, 3);
        LinearLayout.LayoutParams clp2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.8f);
        clp2.leftMargin = UiTheme.dp(activity, 6);
        btnModeCount.setLayoutParams(clp2);

        modeRow.addView(btnModeToken);
        modeRow.addView(btnModeCount);
        layout.addView(modeRow);

        // 模式详情设置
        LinearLayout tokenBox = new LinearLayout(activity);
        tokenBox.setOrientation(LinearLayout.VERTICAL);
        tokenBox.setVisibility("TOKEN".equals(curMode[0]) ? View.VISIBLE : View.GONE);

        TextView lblToken = new TextView(activity);
        lblToken.setText("总 Token 额度 (10M ~ 100M，建议 10M)");
        lblToken.setTextSize(10.5f);
        lblToken.setTextColor(Color.parseColor(UiTheme.C_DIM));
        lblToken.setPadding(0, UiTheme.dp(activity, 8), 0, UiTheme.dp(activity, 2));
        tokenBox.addView(lblToken);

        EditText etTokenM = new EditText(activity);
        long existM = (existing != null) ? (existing.optLong("quotaTotal", 10_000_000L) / 1_000_000L) : 10L;
        etTokenM.setText(String.valueOf(existM));
        etTokenM.setInputType(InputType.TYPE_CLASS_NUMBER);
        etTokenM.setTextSize(11.5f);
        etTokenM.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        etTokenM.setBackground(UiTheme.roundRect(activity, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 1, 4));
        etTokenM.setPadding(UiTheme.dp(activity, 8), UiTheme.dp(activity, 6), UiTheme.dp(activity, 8), UiTheme.dp(activity, 6));
        tokenBox.addView(etTokenM);
        layout.addView(tokenBox);

        LinearLayout countBox = new LinearLayout(activity);
        countBox.setOrientation(LinearLayout.VERTICAL);
        countBox.setVisibility("COUNT".equals(curMode[0]) ? View.VISIBLE : View.GONE);

        TextView lblCount = new TextView(activity);
        lblCount.setText("允许调用的总请求次数 (如 100, 500)");
        lblCount.setTextSize(10.5f);
        lblCount.setTextColor(Color.parseColor(UiTheme.C_DIM));
        lblCount.setPadding(0, UiTheme.dp(activity, 8), 0, UiTheme.dp(activity, 2));
        countBox.addView(lblCount);

        EditText etCount = new EditText(activity);
        long existC = (existing != null) ? existing.optLong("quotaTotal", 100L) : 100L;
        etCount.setText(String.valueOf(existC));
        etCount.setInputType(InputType.TYPE_CLASS_NUMBER);
        etCount.setTextSize(11.5f);
        etCount.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        etCount.setBackground(UiTheme.roundRect(activity, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 1, 4));
        etCount.setPadding(UiTheme.dp(activity, 8), UiTheme.dp(activity, 6), UiTheme.dp(activity, 8), UiTheme.dp(activity, 6));
        countBox.addView(etCount);
        layout.addView(countBox);

        btnModeToken.setOnClickListener(v -> {
            curMode[0] = "TOKEN";
            btnModeToken.setTextColor(Color.parseColor(UiTheme.C_CYAN));
            btnModeToken.setBackground(UiTheme.roundRect(activity, "#0A2328", UiTheme.C_CYAN, 1, 3));
            btnModeCount.setTextColor(Color.parseColor(UiTheme.C_DIM));
            btnModeCount.setBackground(UiTheme.roundRect(activity, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 1, 3));
            tokenBox.setVisibility(View.VISIBLE);
            countBox.setVisibility(View.GONE);
        });

        btnModeCount.setOnClickListener(v -> {
            curMode[0] = "COUNT";
            btnModeCount.setTextColor(Color.parseColor(UiTheme.C_CYAN));
            btnModeCount.setBackground(UiTheme.roundRect(activity, "#0A2328", UiTheme.C_CYAN, 1, 3));
            btnModeToken.setTextColor(Color.parseColor(UiTheme.C_DIM));
            btnModeToken.setBackground(UiTheme.roundRect(activity, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 1, 3));
            tokenBox.setVisibility(View.GONE);
            countBox.setVisibility(View.VISIBLE);
        });

        // RPM 限频
        TextView lblRpm = new TextView(activity);
        lblRpm.setText("每分钟最多请求次数 (RPM 防刷盾牌，建议 5~15)");
        lblRpm.setTextSize(10.5f);
        lblRpm.setTextColor(Color.parseColor(UiTheme.C_DIM));
        lblRpm.setPadding(0, UiTheme.dp(activity, 8), 0, UiTheme.dp(activity, 2));
        layout.addView(lblRpm);

        EditText etRpm = new EditText(activity);
        int existRpm = (existing != null) ? existing.optInt("rpm", 5) : 5;
        etRpm.setText(String.valueOf(existRpm));
        etRpm.setInputType(InputType.TYPE_CLASS_NUMBER);
        etRpm.setTextSize(11.5f);
        etRpm.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        etRpm.setBackground(UiTheme.roundRect(activity, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 1, 4));
        etRpm.setPadding(UiTheme.dp(activity, 8), UiTheme.dp(activity, 6), UiTheme.dp(activity, 8), UiTheme.dp(activity, 6));
        layout.addView(etRpm);

        // 按钮栏
        LinearLayout btnBar = new LinearLayout(activity);
        btnBar.setOrientation(LinearLayout.HORIZONTAL);
        btnBar.setGravity(Gravity.RIGHT);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = UiTheme.dp(activity, 14);
        btnBar.setLayoutParams(blp);

        TextView btnCancel = UiTheme.createButton(activity, "取消", UiTheme.C_DIM, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 3);
        btnCancel.setOnClickListener(v -> dialog.dismiss());
        btnBar.addView(btnCancel);

        TextView btnSave = UiTheme.createButton(activity, "保存生效", UiTheme.C_BLUE, "#1F2B3E", UiTheme.C_BLUE, 3);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.leftMargin = UiTheme.dp(activity, 8);
        btnSave.setLayoutParams(slp);
        btnSave.setOnClickListener(v -> {
            String remark = etRemark.getText().toString().trim();
            if (remark.isEmpty()) remark = "好友分享";
            String key = etKey.getText().toString().trim();
            if (key.isEmpty()) {
                Toast.makeText(activity, "密钥不能为空", Toast.LENGTH_SHORT).show();
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
                    Toast.makeText(activity, "Token 额度最低为 10M", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (m > 100) {
                    Toast.makeText(activity, "Token 额度最高为 100M", Toast.LENGTH_SHORT).show();
                    return;
                }
                totalQuota = (long) m * 1_000_000L;
            } else {
                long c = 100L;
                try { c = Long.parseLong(etCount.getText().toString().trim()); } catch (Exception ignored) {}
                if (c <= 0) c = 100L;
                totalQuota = c;
            }

            JSONArray list = getGuestKeysJsonArray();
            JSONObject item = (existing != null) ? existing : new JSONObject();
            try {
                item.put("key", key);
                item.put("remark", remark);
                item.put("mode", curMode[0]);
                item.put("quotaTotal", totalQuota);
                if (existing == null) {
                    item.put("quotaRemaining", totalQuota);
                } else {
                    long oldRem = existing.optLong("quotaRemaining", totalQuota);
                    item.put("quotaRemaining", Math.min(oldRem, totalQuota));
                }
                item.put("rpm", rpm);

                if (editIndex >= 0 && editIndex < list.length()) {
                    list.put(editIndex, item);
                } else {
                    list.put(item);
                }
                saveGuestKeysJsonArray(list);
                activity.syncGuestKeysToProxyAndConfig();
                refreshGuestShareList();
                dialog.dismiss();
                Toast.makeText(activity, "客用密钥配置已生效！", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(activity, "保存失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
        btnBar.addView(btnSave);
        layout.addView(btnBar);

        dialog.setContentView(layout);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout((int) (activity.getResources().getDisplayMetrics().widthPixels * 0.92f), ViewGroup.LayoutParams.WRAP_CONTENT);
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }
        dialog.show();
    }

    private void copyGuestShareCard(JSONObject item) {
        if (item == null) return;
        String domain = activity.getCurrentTunnelDomain();
        String baseUrl = domain.isEmpty() ? ("http://127.0.0.1:" + activity.getAppConfig().getPort() + "/v1") : (domain + "/v1");
        String chatUrl = domain.isEmpty() ? ("http://127.0.0.1:" + activity.getAppConfig().getPort() + "/v1/chat/completions") : (domain + "/v1/chat/completions");
        String respUrl = domain.isEmpty() ? ("http://127.0.0.1:" + activity.getAppConfig().getPort() + "/v1/responses") : (domain + "/v1/responses");

        String remark = item.optString("remark", "好友");
        String key = item.optString("key", "");
        String mode = item.optString("mode", "TOKEN");
        long total = item.optLong("quotaTotal", 10_000_000L);
        int rpm = item.optInt("rpm", 5);

        String quotaDesc = "TOKEN".equals(mode) ?
                ((total / 1_000_000L) + "M Token · " + rpm + "次/分") :
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
                "   - 聊天接口: " + chatUrl + "\n" +
                "   - 响应接口: " + respUrl;

        activity.copyToClipboard("分享卡片", content);
        Toast.makeText(activity, "已复制【" + remark + "】专属分享卡片，可直接发微信好友！", Toast.LENGTH_SHORT).show();
    }

    private JSONArray getGuestKeysJsonArray() {
        String json = activity.getPrefs().getString("guest_keys_json", "[]");
        try {
            return new JSONArray(json);
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private void saveGuestKeysJsonArray(JSONArray arr) {
        activity.getPrefs().edit().putString("guest_keys_json", arr != null ? arr.toString() : "[]").apply();
    }

    private String maskKey(String key) {
        if (key == null || key.isEmpty()) return "";
        if (key.length() <= 10) return "••••••••";
        return key.substring(0, 7) + "••••••••";
    }
}
