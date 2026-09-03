package com.cliproxy.app.tabs;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.cliproxy.app.BuildConfig;
import com.cliproxy.app.MainActivity;
import com.cliproxy.app.R;
import com.cliproxy.app.ui.UiTheme;

/**
 * AboutTab: Tab 4 关于软件界面
 * 负责展示软件版本信息、运行底座、开源仓库、交流群聊及协议
 */
public class AboutTab {

    private final MainActivity activity;
    private View rootView;

    public AboutTab(MainActivity activity) {
        this.activity = activity;
    }

    public View createView() {
        ScrollView scroll = new ScrollView(activity);
        scroll.setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        scroll.setFillViewport(true);

        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setLayoutParams(new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        layout.setPadding(UiTheme.dp(activity, 16), UiTheme.dp(activity, 28), UiTheme.dp(activity, 16), UiTheme.dp(activity, 24));
        layout.setGravity(Gravity.CENTER_HORIZONTAL);

        // 1. 顶部 Header 居中区域
        ImageView iconView = new ImageView(activity);
        iconView.setImageResource(R.mipmap.ic_launcher);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(UiTheme.dp(activity, 62), UiTheme.dp(activity, 62));
        ilp.bottomMargin = UiTheme.dp(activity, 10);
        iconView.setLayoutParams(ilp);
        layout.addView(iconView);

        TextView tvAppName = new TextView(activity);
        tvAppName.setText("CLIProxyAPI");
        tvAppName.setTextSize(18);
        tvAppName.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        tvAppName.setTypeface(Typeface.DEFAULT_BOLD);
        tvAppName.setGravity(Gravity.CENTER);
        layout.addView(tvAppName);

        TextView tvEdition = new TextView(activity);
        String editionStr = "v1.2.0 · " + (BuildConfig.IS_LITE ? "Lite (1.2MB)" : "Full (50MB)");
        tvEdition.setText(editionStr);
        tvEdition.setTextSize(11);
        tvEdition.setTextColor(Color.parseColor(UiTheme.C_CYAN));
        tvEdition.setTypeface(Typeface.MONOSPACE);
        tvEdition.setGravity(Gravity.CENTER);
        tvEdition.setPadding(0, UiTheme.dp(activity, 4), 0, UiTheme.dp(activity, 20));
        layout.addView(tvEdition);

        // 2. iOS 极简单行卡片
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(UiTheme.roundRect(activity, UiTheme.C_SURFACE, UiTheme.C_BORDER, 1, 8));
        card.setPadding(UiTheme.dp(activity, 12), UiTheme.dp(activity, 4), UiTheme.dp(activity, 12), UiTheme.dp(activity, 4));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        card.setLayoutParams(clp);

        // 行 1: 版本信息
        card.addView(buildAboutRow("版本信息", "v1.2.0 (3)", null, v -> activity.copyToClipboard("版本号", "1.2.0")));
        card.addView(buildAboutDivider());

        // 行 2: 运行底座
        card.addView(buildAboutRow("运行底座", "PRoot / arm64", null, null));
        card.addView(buildAboutDivider());

        // 行 3: 开源仓库
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
        TextView tvFooter = new TextView(activity);
        tvFooter.setText("CLIProxyAPI for Android\nOpen source with MIT License");
        tvFooter.setTextSize(11);
        tvFooter.setTextColor(Color.parseColor(UiTheme.C_DIM));
        tvFooter.setGravity(Gravity.CENTER);
        tvFooter.setPadding(0, UiTheme.dp(activity, 30), 0, UiTheme.dp(activity, 10));
        layout.addView(tvFooter);

        scroll.addView(layout);
        rootView = scroll;
        return rootView;
    }

    public View getView() {
        return rootView;
    }

    private View buildAboutRow(String label, String value, String valueColorHex, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(UiTheme.dp(activity, 4), UiTheme.dp(activity, 12), UiTheme.dp(activity, 4), UiTheme.dp(activity, 12));

        TextView tvLabel = new TextView(activity);
        tvLabel.setText(label);
        tvLabel.setTextSize(12.5f);
        tvLabel.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        tvLabel.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(tvLabel);

        TextView tvValue = new TextView(activity);
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
        View div = new View(activity);
        div.setBackgroundColor(Color.parseColor(UiTheme.C_BORDER_SUB));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UiTheme.dp(activity, 1));
        div.setLayoutParams(dlp);
        return div;
    }

    private void openBrowserUrl(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(activity, "无法打开链接: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
}
