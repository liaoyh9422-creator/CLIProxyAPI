package com.cliproxy.core.proxy;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.IOException;
import java.net.Authenticator;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.net.URL;

/**
 * 出站网络代理连通性测试工具
 * 支持 HTTP/HTTPS 与 SOCKS5 代理协议，并测试目标服务器的往返延迟与可达性
 */
public class ProxyTester {
    private static final String TAG = "ProxyTester";

    public interface TestCallback {
        void onResult(boolean success, long cfLatencyMs, long aiLatencyMs, String message);
    }

    public static void testProxy(String proxyUrlStr, TestCallback callback) {
        new Thread(() -> {
            Handler mainHandler = new Handler(Looper.getMainLooper());
            if (proxyUrlStr == null || proxyUrlStr.trim().isEmpty()) {
                mainHandler.post(() -> callback.onResult(false, -1, -1, "代理地址不能为空"));
                return;
            }

            String url = proxyUrlStr.trim();
            if (!url.contains("://")) {
                url = "http://" + url;
            }

            try {
                Uri uri = Uri.parse(url);
                String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase() : "http";
                String host = uri.getHost();
                int port = uri.getPort();

                if (host == null || host.isEmpty()) {
                    mainHandler.post(() -> callback.onResult(false, -1, -1, "无效的主机地址"));
                    return;
                }
                if (port <= 0) {
                    port = scheme.startsWith("socks") ? 10808 : 7890;
                }

                Proxy.Type proxyType = (scheme.startsWith("socks")) ? Proxy.Type.SOCKS : Proxy.Type.HTTP;
                Proxy proxy = new Proxy(proxyType, new InetSocketAddress(host, port));

                // 如果包含鉴权用户名密码
                String userInfo = uri.getUserInfo();
                if (userInfo != null && userInfo.contains(":")) {
                    String[] parts = userInfo.split(":", 2);
                    Authenticator.setDefault(new Authenticator() {
                        @Override
                        protected PasswordAuthentication getPasswordAuthentication() {
                            return new PasswordAuthentication(parts[0], parts[1].toCharArray());
                        }
                    });
                }

                // 1. 探测 Cloudflare 穿透端点 (api.trycloudflare.com)
                long cfStart = System.currentTimeMillis();
                int cfCode = probeEndpoint(proxy, "https://api.trycloudflare.com/tunnel", 6000);
                long cfLatency = System.currentTimeMillis() - cfStart;

                // 2. 探测 OpenAI 官方 API 端点 (api.openai.com)
                long aiStart = System.currentTimeMillis();
                int aiCode = probeEndpoint(proxy, "https://api.openai.com/v1/models", 6000);
                long aiLatency = System.currentTimeMillis() - aiStart;

                boolean cfOk = (cfCode > 0 && cfCode != 502 && cfCode != 504);
                boolean aiOk = (aiCode > 0 && aiCode != 502 && aiCode != 504);

                if (cfOk || aiOk) {
                    StringBuilder sb = new StringBuilder("● 连通正常 (");
                    if (cfOk) sb.append("CF穿透: ").append(cfLatency).append("ms");
                    if (cfOk && aiOk) sb.append(" · ");
                    if (aiOk) sb.append("OpenAI: ").append(aiLatency).append("ms");
                    sb.append(")");
                    String msg = sb.toString();
                    mainHandler.post(() -> callback.onResult(true, cfLatency, aiLatency, msg));
                } else {
                    String errDetail = "代理服务器无法接通目标端点";
                    mainHandler.post(() -> callback.onResult(false, -1, -1, "● " + errDetail));
                }

            } catch (Exception e) {
                Log.w(TAG, "Proxy test error", e);
                String msg = getFriendlyError(e);
                mainHandler.post(() -> callback.onResult(false, -1, -1, "● " + msg));
            }
        }, "proxy-tester").start();
    }

    private static int probeEndpoint(Proxy proxy, String targetUrl, int timeoutMs) {
        HttpURLConnection conn = null;
        try {
            URL u = new URL(targetUrl);
            conn = (HttpURLConnection) u.openConnection(proxy);
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "CLIProxy-Probe/1.0");
            conn.setInstanceFollowRedirects(false);
            return conn.getResponseCode();
        } catch (Exception e) {
            Log.d(TAG, "Probe " + targetUrl + " failed: " + e.getMessage());
            return -1;
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Exception ignored) {}
            }
        }
    }

    private static String getFriendlyError(Exception e) {
        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        if (msg.contains("refused") || msg.contains("connection refused")) {
            return "连接被拒绝（请检查代理端口是否正确、本地代理软件是否已启动）";
        }
        if (msg.contains("timeout") || e instanceof SocketTimeoutException) {
            return "连接超时（代理服务器无响应或节点不可用）";
        }
        if (msg.contains("unresolved") || msg.contains("unknownhost")) {
            return "主机域名无法解析";
        }
        return "连接失败: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
    }
}
