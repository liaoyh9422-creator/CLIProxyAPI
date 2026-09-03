package com.cliproxy.app.tabs;

import android.graphics.Color;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.cliproxy.app.MainActivity;
import com.cliproxy.app.ui.UiTheme;

/**
 * TailscaleTab: Tab 2 Tailscale 虚拟局域网控制台
 * 纯用户态运行（免 Root，不占系统 VPN 槽位），提供局域网接入、AuthKey 配置与终端日志
 */
public class TailscaleTab {

    private final MainActivity activity;
    private View rootView;

    private TextView tsStatusText;
    private TextView tsIpText;
    private TextView btnTsIpCopy;
    private EditText editTsAuthKey;
    private EditText editTsHostname;

    private LinearLayout tsLogContainer;
    private ScrollView tsLogScroll;
    private final StringBuilder allTsLogBuilder = new StringBuilder();
    private int tsLogLineIndex = 0;

    public TailscaleTab(MainActivity activity) {
        this.activity = activity;
    }

    public View createView() {
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        layout.setPadding(UiTheme.dp(activity, 12), UiTheme.dp(activity, 8), UiTheme.dp(activity, 12), UiTheme.dp(activity, 4));

        layout.addView(UiTheme.buildSectionHeader(activity, "Tailscale 虚拟局域网", "纯用户态模式（免 Root，不占系统 VPN 槽位）"));
        layout.addView(buildTailscaleControlSection());

        View tsLogCard = buildTailscaleLogSection();
        LinearLayout.LayoutParams tslp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        tslp.topMargin = UiTheme.dp(activity, 6);
        tsLogCard.setLayoutParams(tslp);
        layout.addView(tsLogCard);

        rootView = layout;
        return rootView;
    }

    public View getView() {
        return rootView;
    }

    private View buildTailscaleControlSection() {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(UiTheme.roundRect(activity, UiTheme.C_SURFACE, UiTheme.C_BORDER, 1, 6));
        card.setPadding(UiTheme.dp(activity, 12), UiTheme.dp(activity, 8), UiTheme.dp(activity, 12), UiTheme.dp(activity, 8));

        // 状态行
        LinearLayout statusRow = new LinearLayout(activity);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView label = new TextView(activity);
        label.setText("Tailnet 状态: ");
        label.setTextSize(11);
        label.setTextColor(Color.parseColor(UiTheme.C_DIM));
        statusRow.addView(label);

        tsStatusText = new TextView(activity);
        tsStatusText.setText("未连接");
        tsStatusText.setTextSize(11);
        tsStatusText.setTextColor(Color.parseColor(UiTheme.C_DIM));
        statusRow.addView(tsStatusText);
        card.addView(statusRow);

        // IP / 局域网地址行
        LinearLayout ipRow = new LinearLayout(activity);
        ipRow.setOrientation(LinearLayout.HORIZONTAL);
        ipRow.setGravity(Gravity.CENTER_VERTICAL);
        ipRow.setPadding(0, UiTheme.dp(activity, 4), 0, UiTheme.dp(activity, 6));

        tsIpText = new TextView(activity);
        tsIpText.setText("http://100.x.x.x:" + activity.getAppConfig().getPort() + "/v1");
        tsIpText.setTextSize(10.5f);
        tsIpText.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        tsIpText.setTypeface(Typeface.MONOSPACE);
        tsIpText.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        ipRow.addView(tsIpText);

        btnTsIpCopy = UiTheme.createButton(activity, "复制", UiTheme.C_TEXT, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 3);
        btnTsIpCopy.setEnabled(false);
        btnTsIpCopy.setAlpha(0.35f);
        btnTsIpCopy.setOnClickListener(v -> activity.copyToClipboard("Tailscale 地址", tsIpText.getText().toString()));
        ipRow.addView(btnTsIpCopy);
        card.addView(ipRow);

        // AuthKey 输入框
        editTsAuthKey = new EditText(activity);
        editTsAuthKey.setHint("Auth Key (tskey-auth-xxxx，可空)");
        editTsAuthKey.setHintTextColor(Color.parseColor("#608B949E"));
        editTsAuthKey.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        editTsAuthKey.setTextSize(10.5f);
        editTsAuthKey.setTypeface(Typeface.MONOSPACE);
        editTsAuthKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        editTsAuthKey.setBackground(UiTheme.roundRect(activity, "#0D1117", UiTheme.C_BORDER, 1, 4));
        editTsAuthKey.setPadding(UiTheme.dp(activity, 8), UiTheme.dp(activity, 4), UiTheme.dp(activity, 8), UiTheme.dp(activity, 4));
        editTsAuthKey.setText(activity.getPrefs().getString("ts_auth_key", ""));
        card.addView(editTsAuthKey);

        // 节点 Hostname 输入框
        editTsHostname = new EditText(activity);
        editTsHostname.setHint("节点名称 (默认 cliproxy-phone)");
        editTsHostname.setHintTextColor(Color.parseColor("#608B949E"));
        editTsHostname.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        editTsHostname.setTextSize(10.5f);
        editTsHostname.setTypeface(Typeface.MONOSPACE);
        editTsHostname.setBackground(UiTheme.roundRect(activity, "#0D1117", UiTheme.C_BORDER, 1, 4));
        editTsHostname.setPadding(UiTheme.dp(activity, 8), UiTheme.dp(activity, 4), UiTheme.dp(activity, 8), UiTheme.dp(activity, 4));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = UiTheme.dp(activity, 5);
        editTsHostname.setLayoutParams(hlp);
        editTsHostname.setText(activity.getPrefs().getString("ts_hostname", "cliproxy-phone"));
        card.addView(editTsHostname);

        // 控制按键
        LinearLayout btnRow = new LinearLayout(activity);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = UiTheme.dp(activity, 6);
        btnRow.setLayoutParams(blp);

        TextView btnConnectTs = UiTheme.createButton(activity, "连接 Tailscale", UiTheme.C_BLUE, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 3);
        btnConnectTs.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        btnConnectTs.setOnClickListener(v -> activity.startTailscale(getTsAuthKey(), getTsHostname()));
        btnRow.addView(btnConnectTs);

        TextView btnDisconnectTs = UiTheme.createButton(activity, "断开", UiTheme.C_RED, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 3);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.leftMargin = UiTheme.dp(activity, 5);
        btnDisconnectTs.setLayoutParams(dlp);
        btnDisconnectTs.setOnClickListener(v -> activity.stopTailscale());
        btnRow.addView(btnDisconnectTs);

        card.addView(btnRow);
        return card;
    }

    private View buildTailscaleLogSection() {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(UiTheme.roundRect(activity, UiTheme.C_SURFACE, UiTheme.C_BORDER, 1, 6));
        card.setPadding(UiTheme.dp(activity, 10), UiTheme.dp(activity, 6), UiTheme.dp(activity, 10), UiTheme.dp(activity, 6));

        LinearLayout top = new LinearLayout(activity);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(0, 0, 0, UiTheme.dp(activity, 4));

        TextView title = new TextView(activity);
        title.setText("Tailscale 日志");
        title.setTextSize(11.5f);
        title.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        title.setTypeface(Typeface.DEFAULT_BOLD);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView copyBtn = UiTheme.createButton(activity, "复制", UiTheme.C_TEXT, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 3);
        copyBtn.setOnClickListener(v -> activity.copyToClipboard("Tailscale 日志", allTsLogBuilder.toString()));
        top.addView(copyBtn);

        TextView clearBtn = UiTheme.createButton(activity, "清空", UiTheme.C_DIM, UiTheme.C_SURFACE_ALT, UiTheme.C_BORDER, 3);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.leftMargin = UiTheme.dp(activity, 5);
        clearBtn.setLayoutParams(clp);
        clearBtn.setOnClickListener(v -> clearLog());
        top.addView(clearBtn);
        card.addView(top);

        tsLogScroll = new ScrollView(activity);
        tsLogScroll.setBackgroundColor(Color.parseColor("#080B0F"));
        tsLogScroll.setFillViewport(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        tsLogScroll.setLayoutParams(lp);

        tsLogContainer = new LinearLayout(activity);
        tsLogContainer.setOrientation(LinearLayout.VERTICAL);
        tsLogContainer.setPadding(UiTheme.dp(activity, 6), UiTheme.dp(activity, 4), UiTheme.dp(activity, 6), UiTheme.dp(activity, 4));
        tsLogScroll.addView(tsLogContainer);

        card.addView(tsLogScroll);
        return card;
    }

    public void updateTailscaleUI(boolean isConnected, String ip) {
        if (isConnected) {
            if (tsStatusText != null) {
                tsStatusText.setText(ip.isEmpty() ? "启动中..." : "已就绪");
                tsStatusText.setTextColor(Color.parseColor(ip.isEmpty() ? UiTheme.C_BLUE : UiTheme.C_GREEN));
            }
            if (!ip.isEmpty() && tsIpText != null) {
                tsIpText.setText("http://" + ip + ":" + activity.getAppConfig().getPort() + "/v1");
                if (btnTsIpCopy != null) {
                    btnTsIpCopy.setEnabled(true);
                    btnTsIpCopy.setAlpha(1.0f);
                }
            }
        } else {
            if (tsStatusText != null) {
                tsStatusText.setText("未连接");
                tsStatusText.setTextColor(Color.parseColor(UiTheme.C_DIM));
            }
            if (btnTsIpCopy != null) {
                btnTsIpCopy.setEnabled(false);
                btnTsIpCopy.setAlpha(0.35f);
            }
        }
    }

    public void appendLog(String rawLine) {
        if (tsLogContainer != null) {
            LogHelper.appendStyledLog(activity, tsLogContainer, allTsLogBuilder, tsLogScroll, rawLine, tsLogLineIndex++);
        }
    }

    public void clearLog() {
        if (tsLogContainer != null) {
            tsLogContainer.removeAllViews();
            allTsLogBuilder.setLength(0);
            tsLogLineIndex = 0;
            Toast.makeText(activity, "Tailscale 日志已清空", Toast.LENGTH_SHORT).show();
        }
    }

    public String getTsAuthKey() {
        return editTsAuthKey != null ? editTsAuthKey.getText().toString().trim() : "";
    }

    public String getTsHostname() {
        return editTsHostname != null ? editTsHostname.getText().toString().trim() : "cliproxy-phone";
    }
}
