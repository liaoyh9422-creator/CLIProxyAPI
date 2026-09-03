package com.cliproxy.app.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ValueCallback;
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
 * 负责渲染全屏沉浸式 WebView、顶部精简工具栏以及处理网页导航事件与文件上传
 */
public class WebPanelManager {

    public static final int FILE_CHOOSER_REQUEST_CODE = 10001;

    public interface CloseCallback {
        void onClose();
    }

    private final Activity activity;
    private final CloseCallback closeCallback;
    private WebView activeWebView;
    private ValueCallback<Uri[]> uploadMessageCallback;

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
        webView.getSettings().setAllowFileAccess(true);
        webView.getSettings().setAllowContentAccess(true);
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

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                if (uploadMessageCallback != null) {
                    uploadMessageCallback.onReceiveValue(null);
                    uploadMessageCallback = null;
                }
                uploadMessageCallback = filePathCallback;

                Intent intent = null;
                if (fileChooserParams != null) {
                    try {
                        intent = fileChooserParams.createIntent();
                    } catch (Exception ignored) {}
                }
                if (intent == null) {
                    intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("*/*");
                }

                try {
                    activity.startActivityForResult(Intent.createChooser(intent, "选择要上传的文件"), FILE_CHOOSER_REQUEST_CODE);
                    return true;
                } catch (Exception e) {
                    if (uploadMessageCallback != null) {
                        uploadMessageCallback.onReceiveValue(null);
                        uploadMessageCallback = null;
                    }
                    Toast.makeText(activity, "无法拉起文件选择器: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    return false;
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

    /** 处理文件选择器返回结果 */
    public void handleFileChooserResult(int resultCode, Intent data) {
        if (uploadMessageCallback == null) return;
        Uri[] results = null;
        if (resultCode == Activity.RESULT_OK && data != null) {
            String dataString = data.getDataString();
            ClipData clipData = data.getClipData();
            if (clipData != null) {
                results = new Uri[clipData.getItemCount()];
                for (int i = 0; i < clipData.getItemCount(); i++) {
                    results[i] = clipData.getItemAt(i).getUri();
                }
            } else if (dataString != null) {
                results = new Uri[]{Uri.parse(dataString)};
            } else if (data.getData() != null) {
                results = new Uri[]{data.getData()};
            }
        }
        uploadMessageCallback.onReceiveValue(results);
        uploadMessageCallback = null;
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
        if (uploadMessageCallback != null) {
            uploadMessageCallback.onReceiveValue(null);
            uploadMessageCallback = null;
        }
        if (activeWebView != null) {
            activeWebView.destroy();
            activeWebView = null;
        }
        if (closeCallback != null) {
            closeCallback.onClose();
        }
    }
}
