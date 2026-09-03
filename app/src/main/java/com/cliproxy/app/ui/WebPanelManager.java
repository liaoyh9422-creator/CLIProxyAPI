package com.cliproxy.app.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

/**
 * WebPanelManager: 内嵌管理面板控制器
 * 负责渲染全屏沉浸式 WebView、顶部精简工具栏以及处理网页导航事件
 */
public class WebPanelManager {

    public interface CloseCallback {
        void onClose();
    }

    private final Activity activity;
    private final CloseCallback closeCallback;
    private WebView activeWebView;

    public WebPanelManager(Activity activity, CloseCallback closeCallback) {
        this.activity = activity;
        this.closeCallback = closeCallback;
    }

    /** 构建完整的全屏 Web 控制面板 View */
    public View createWebPanelView(String url) {
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(Color.parseColor(UiTheme.C_BG));
        layout.setFitsSystemWindows(true);

        // 1. 顶部微型操作栏
        LinearLayout topBar = new LinearLayout(activity);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackground(UiTheme.roundRect(activity, "#80161B22", "#4030363D", 1, 0));
        topBar.setPadding(UiTheme.dp(activity, 12), UiTheme.dp(activity, 5), UiTheme.dp(activity, 12), UiTheme.dp(activity, 5));

        // 返回按钮
        TextView btnBack = UiTheme.createButton(activity, "← 返回", UiTheme.C_TEXT, "#8021262D", "#6030363D", 3);
        btnBack.setOnClickListener(v -> close());
        topBar.addView(btnBack);

        // 居中标题
        LinearLayout titleCol = new LinearLayout(activity);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        titleCol.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleCol.setLayoutParams(tlp);

        TextView titleText = new TextView(activity);
        titleText.setText("CLIProxy|默认密码:[cliproxy123]");
        titleText.setTextSize(12);
        titleText.setTextColor(Color.parseColor(UiTheme.C_TEXT));
        titleText.setTypeface(Typeface.DEFAULT_BOLD);
        titleCol.addView(titleText);
        topBar.addView(titleCol);

        // 刷新按钮
        TextView btnRefresh = UiTheme.createButton(activity, "⟳", UiTheme.C_TEXT, "#8021262D", "#6030363D", 3);
        topBar.addView(btnRefresh);

        // 外部浏览器打开按钮
        TextView btnBrowser = UiTheme.createButton(activity, "外部打开", UiTheme.C_BLUE, "#8021262D", "#6030363D", 3);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.leftMargin = UiTheme.dp(activity, 5);
        btnBrowser.setLayoutParams(blp);
        btnBrowser.setOnClickListener(v -> {
            try {
                activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Exception e) {
                Toast.makeText(activity, "无法打开浏览器: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
        topBar.addView(btnBrowser);
        layout.addView(topBar);

        // 2. 加载进度指示条
        ProgressBar pb = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        pb.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, UiTheme.dp(activity, 2)));
        pb.setMax(100);
        pb.setVisibility(View.GONE);
        layout.addView(pb);

        // 3. 核心 WebView 配置
        WebView webView = new WebView(activity);
        activeWebView = webView;
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setBuiltInZoomControls(true);
        webView.getSettings().setDisplayZoomControls(false);
        webView.setBackgroundColor(Color.parseColor(UiTheme.C_BG));
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        webView.setLayoutParams(wlp);

        btnRefresh.setOnClickListener(v -> webView.reload());

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (newProgress < 100) {
                    pb.setVisibility(View.VISIBLE);
                    pb.setProgress(newProgress);
                } else {
                    pb.setVisibility(View.GONE);
                }
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String reqUrl = request.getUrl().toString();
                if (reqUrl.startsWith("http://localhost") || reqUrl.startsWith("http://127.0.0.1")) {
                    return false;
                }
                try {
                    activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(reqUrl)));
                    return true;
                } catch (Exception ignored) {
                    return false;
                }
            }
        });

        webView.loadUrl(url);
        layout.addView(webView);
        return layout;
    }

    /** 响应系统物理返回键 */
    public boolean handleBackPressed() {
        if (activeWebView != null) {
            if (activeWebView.canGoBack()) {
                activeWebView.goBack();
                return true;
            }
            close();
            return true;
        }
        return false;
    }

    /** 关闭内嵌面板并释放资源 */
    public void close() {
        if (activeWebView != null) {
            activeWebView.destroy();
            activeWebView = null;
        }
        if (closeCallback != null) {
            closeCallback.onClose();
        }
    }
}
