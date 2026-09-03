package com.cliproxy.core.cache;

import android.content.Context;
import android.util.Log;
import com.cliproxy.core.metrics.MetricsTracker;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 智能响应缓存反向代理：
 * 监听公开端口（默认 8317），拦截 /v1/chat/completions 智能命中与回放缓存。
 * 支持热插拔一键开关，未命中或关闭时 100% 原生 TCP 双向透明管道转发，绝不降级！
 */
public class SmartCacheProxy {
    private static final String TAG = "SmartCacheProxy";

    private final Context context;
    private final int publicPort;
    private final int backendPort;

    private ServerSocket serverSocket;
    private ExecutorService threadPool;
    private volatile boolean isRunning = false;

    private static volatile boolean globalCacheEnabled = false;

    // 多客用 Key 毫秒级防刷限流盾牌 (支持按次 / 按 Token 10M~100M 限制)
    public static class GuestPolicy {
        public String key;
        public String remark;
        public String mode; // "TOKEN" or "COUNT"
        public long quotaRemaining;
        public long quotaTotal;
        public int rpmLimit;
        public final List<Long> requestTimestamps = Collections.synchronizedList(new ArrayList<>());

        public GuestPolicy(String key, String remark, String mode, long quotaRemaining, long quotaTotal, int rpmLimit) {
            this.key = key;
            this.remark = (remark != null) ? remark : "";
            this.mode = (mode != null) ? mode : "TOKEN";
            this.quotaRemaining = quotaRemaining;
            this.quotaTotal = quotaTotal;
            this.rpmLimit = rpmLimit > 0 ? rpmLimit : 5;
        }
    }

    private static final Map<String, GuestPolicy> guestPolicyMap = new ConcurrentHashMap<>();

    public interface MultiQuotaListener {
        void onQuotaChanged(String key, long remaining, long total);
    }
    private static volatile MultiQuotaListener multiQuotaListener;

    public static void setGuestPolicies(List<GuestPolicy> list) {
        guestPolicyMap.clear();
        if (list != null) {
            for (GuestPolicy p : list) {
                if (p.key != null && !p.key.isEmpty()) {
                    guestPolicyMap.put(p.key.trim(), p);
                }
            }
        }
    }

    public static void putGuestPolicy(GuestPolicy policy) {
        if (policy != null && policy.key != null && !policy.key.isEmpty()) {
            guestPolicyMap.put(policy.key.trim(), policy);
        }
    }

    public static void removeGuestPolicy(String key) {
        if (key != null) {
            guestPolicyMap.remove(key.trim());
        }
    }

    public static Map<String, GuestPolicy> getGuestPolicies() {
        return guestPolicyMap;
    }

    public static void setMultiQuotaListener(MultiQuotaListener listener) {
        multiQuotaListener = listener;
    }

    public static String formatQuota(long quota, String mode) {
        if ("TOKEN".equals(mode)) {
            if (quota >= 1_000_000) {
                double m = quota / 1_000_000.0;
                return (m == (long) m ? String.format(java.util.Locale.US, "%.0fM", m) : String.format(java.util.Locale.US, "%.1fM", m)) + " Tokens";
            } else if (quota >= 1_000) {
                return (quota / 1_000) + "k Tokens";
            } else {
                return quota + " Tokens";
            }
        } else {
            return quota + " 次";
        }
    }

    public static void setGlobalCacheEnabled(boolean enabled) {
        globalCacheEnabled = enabled;
    }

    public static boolean isGlobalCacheEnabled() {
        return globalCacheEnabled;
    }

    public SmartCacheProxy(Context context, int publicPort, int backendPort) {
        this.context = context;
        this.publicPort = publicPort;
        this.backendPort = backendPort;
    }

    public void setCacheEnabled(boolean enabled) {
        setGlobalCacheEnabled(enabled);
    }

    public boolean isCacheEnabled() {
        return isGlobalCacheEnabled();
    }

    /** 启动缓存反向代理 */
    public synchronized void start() {
        if (isRunning) return;
        isRunning = true;
        threadPool = Executors.newCachedThreadPool();

        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(publicPort, 128);
                Log.i(TAG, "SmartCacheProxy 启动成功，监听 :" + publicPort + " -> 后端 :" + backendPort);

                while (isRunning && !serverSocket.isClosed()) {
                    try {
                        Socket client = serverSocket.accept();
                        client.setTcpNoDelay(true);
                        threadPool.execute(() -> handleClient(client));
                    } catch (Exception e) {
                        if (!isRunning) break;
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "SmartCacheProxy 监听异常", e);
            }
        }, "cache-proxy-acceptor").start();
    }

    /** 停止代理 */
    public synchronized void stop() {
        isRunning = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (Exception ignored) {}
        if (threadPool != null) {
            threadPool.shutdownNow();
        }
    }

    /** 处理客户端连接 */
    private void handleClient(Socket clientSocket) {
        Socket backendSocket = null;
        try {
            InputStream inFromClient = clientSocket.getInputStream();
            OutputStream outToClient = clientSocket.getOutputStream();

            ByteArrayOutputStream headerBuffer = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int read;
            int contentLength = -1;
            String requestLine = null;
            GuestPolicy activePolicy = null;

            while ((read = inFromClient.read(buf)) != -1) {
                headerBuffer.write(buf, 0, read);
                String current = headerBuffer.toString("UTF-8");
                int headerEnd = current.indexOf("\r\n\r\n");
                if (headerEnd != -1) {
                    String headerStr = current.substring(0, headerEnd);
                    String[] lines = headerStr.split("\r\n");
                    if (lines.length > 0) requestLine = lines[0];

                    String authBearer = null;
                    for (String line : lines) {
                        if (line.toLowerCase().startsWith("content-length:")) {
                            try {
                                contentLength = Integer.parseInt(line.substring(15).trim());
                            } catch (Exception ignored) {}
                        } else if (line.toLowerCase().startsWith("authorization:")) {
                            String val = line.substring(14).trim();
                            if (val.toLowerCase().startsWith("bearer ")) {
                                authBearer = val.substring(7).trim();
                            }
                        }
                    }

                    // 多客用 Key 本地 1ms 毫秒级盾牌拦截（RPM 限流与按次/按 Token 配额）
                    activePolicy = (authBearer != null) ? guestPolicyMap.get(authBearer) : null;
                    if (activePolicy != null) {
                        long now = System.currentTimeMillis();
                        synchronized (activePolicy.requestTimestamps) {
                            Iterator<Long> it = activePolicy.requestTimestamps.iterator();
                            while (it.hasNext()) {
                                if (now - it.next() > 60000) {
                                    it.remove();
                                }
                            }
                            if (activePolicy.requestTimestamps.size() >= activePolicy.rpmLimit) {
                                String errResp = "HTTP/1.1 429 Too Many Requests\r\n" +
                                        "Content-Type: application/json; charset=utf-8\r\n" +
                                        "Access-Control-Allow-Origin: *\r\n" +
                                        "Connection: close\r\n\r\n" +
                                        "{\"error\":{\"message\":\"客用密钥【" + activePolicy.remark + "】触发频次限制 (" + activePolicy.rpmLimit + " RPM)，请减慢请求速度。\",\"type\":\"rate_limit_error\"}}";
                                outToClient.write(errResp.getBytes(StandardCharsets.UTF_8));
                                outToClient.flush();
                                clientSocket.close();
                                return;
                            }
                            activePolicy.requestTimestamps.add(now);
                        }

                        if (activePolicy.quotaRemaining <= 0) {
                            String errResp = "HTTP/1.1 403 Forbidden\r\n" +
                                    "Content-Type: application/json; charset=utf-8\r\n" +
                                    "Access-Control-Allow-Origin: *\r\n" +
                                    "Connection: close\r\n\r\n" +
                                    "{\"error\":{\"message\":\"客用密钥【" + activePolicy.remark + "】额度已用尽 (配额 " + formatQuota(activePolicy.quotaTotal, activePolicy.mode) + ")。\",\"type\":\"quota_exceeded\"}}";
                            outToClient.write(errResp.getBytes(StandardCharsets.UTF_8));
                            outToClient.flush();
                            clientSocket.close();
                            return;
                        }

                        if ("COUNT".equals(activePolicy.mode)) {
                            activePolicy.quotaRemaining--;
                            if (multiQuotaListener != null) {
                                multiQuotaListener.onQuotaChanged(activePolicy.key, activePolicy.quotaRemaining, activePolicy.quotaTotal);
                            }
                        }
                    }
                    break;
                }
                if (headerBuffer.size() > 65536) break;
            }

            boolean isChatCompletion = (requestLine != null &&
                    requestLine.startsWith("POST") &&
                    (requestLine.contains("/chat/completions") || requestLine.contains("/responses")));

            // 开关处于启用状态 且 请求为聊天生成接口
            if (globalCacheEnabled && isChatCompletion && contentLength > 0 && contentLength < 1048576) {
                byte[] rawHeaderBytes = headerBuffer.toByteArray();
                int headerEndIdx = findHeaderEnd(rawHeaderBytes);
                int bodyAlreadyRead = rawHeaderBytes.length - headerEndIdx;

                ByteArrayOutputStream bodyBuffer = new ByteArrayOutputStream();
                if (bodyAlreadyRead > 0) {
                    bodyBuffer.write(rawHeaderBytes, headerEndIdx, bodyAlreadyRead);
                }

                while (bodyBuffer.size() < contentLength) {
                    int toRead = Math.min(buf.length, contentLength - bodyBuffer.size());
                    int n = inFromClient.read(buf, 0, toRead);
                    if (n == -1) break;
                    bodyBuffer.write(buf, 0, n);
                }

                byte[] bodyBytes = bodyBuffer.toByteArray();
                String bodyJsonStr = new String(bodyBytes, StandardCharsets.UTF_8);

                try {
                    JSONObject reqJson = new JSONObject(bodyJsonStr);
                    String model = reqJson.optString("model", "default");
                    boolean isStream = reqJson.optBoolean("stream", false);
                    JSONArray messages = reqJson.optJSONArray("messages");

                    if (messages != null && messages.length() > 0) {
                        String cacheKey = computeCacheKey(model, messages);
                        ResponseCacheDb db = ResponseCacheDb.getInstance(context);
                        ResponseCacheDb.CacheEntry cached = db.get(cacheKey);

                        if (cached != null) {
                            // 缓存命中：5ms 秒回
                            replayCachedResponse(outToClient, cached, isStream);
                            MetricsTracker.getInstance().parseLogLine("200 | 5ms | 127.0.0.1 | POST \"/v1/chat/completions\" [⚡缓存秒回]");
                            clientSocket.close();
                            return;
                        }

                        // 缓存未命中：中继后端并捕获内容存库
                        backendSocket = new Socket("127.0.0.1", backendPort);
                        backendSocket.setTcpNoDelay(true);

                        OutputStream outToBackend = backendSocket.getOutputStream();
                        InputStream inFromBackend = backendSocket.getInputStream();

                        outToBackend.write(rawHeaderBytes, 0, headerEndIdx);
                        outToBackend.write(bodyBytes);
                        outToBackend.flush();

                        relayAndCaptureResponse(inFromBackend, outToClient, cacheKey, model, messages, isStream, activePolicy);
                        return;
                    }
                } catch (Exception parseErr) {
                    Log.w(TAG, "Cache parsing fallback: " + parseErr.getMessage());
                }

                // 解析异常，平滑降级
                backendSocket = new Socket("127.0.0.1", backendPort);
                backendSocket.getOutputStream().write(rawHeaderBytes, 0, headerEndIdx);
                backendSocket.getOutputStream().write(bodyBytes);
                backendSocket.getOutputStream().flush();
                pipeSockets(clientSocket, backendSocket, activePolicy);
                return;
            }

            // 缓存关闭 或 非聊天接口：原生 TCP 双向零拷贝直通
            backendSocket = new Socket("127.0.0.1", backendPort);
            backendSocket.setTcpNoDelay(true);

            if (headerBuffer.size() > 0) {
                backendSocket.getOutputStream().write(headerBuffer.toByteArray());
                backendSocket.getOutputStream().flush();
            }

            pipeSockets(clientSocket, backendSocket, activePolicy);

        } catch (Exception e) {
            // 客户端断开连接，安全忽略
        } finally {
            closeQuietly(clientSocket);
            closeQuietly(backendSocket);
        }
    }

    private void replayCachedResponse(OutputStream out, ResponseCacheDb.CacheEntry cached, boolean isStream) throws Exception {
        String content = cached.responseContent;
        if (isStream) {
            StringBuilder sse = new StringBuilder();
            sse.append("HTTP/1.1 200 OK\r\n");
            sse.append("Content-Type: text/event-stream; charset=utf-8\r\n");
            sse.append("Cache-Control: no-cache\r\n");
            sse.append("Connection: close\r\n");
            sse.append("Access-Control-Allow-Origin: *\r\n");
            sse.append("X-Cache: HIT-LOCAL-5MS\r\n\r\n");
            out.write(sse.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();

            int chunkSize = Math.max(1, content.length() / 8);
            for (int i = 0; i < content.length(); i += chunkSize) {
                int end = Math.min(content.length(), i + chunkSize);
                String sub = content.substring(i, end);

                JSONObject delta = new JSONObject();
                delta.put("content", sub);

                JSONObject choice = new JSONObject();
                choice.put("index", 0);
                choice.put("delta", delta);

                JSONArray choices = new JSONArray();
                choices.put(choice);

                JSONObject chunkObj = new JSONObject();
                chunkObj.put("id", "chatcmpl-cache");
                chunkObj.put("object", "chat.completion.chunk");
                chunkObj.put("model", cached.model);
                chunkObj.put("choices", choices);

                out.write(("data: " + chunkObj.toString() + "\n\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
                Thread.sleep(3);
            }

            out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
        } else {
            JSONObject msg = new JSONObject();
            msg.put("role", "assistant");
            msg.put("content", content);

            JSONObject choice = new JSONObject();
            choice.put("index", 0);
            choice.put("message", msg);
            choice.put("finish_reason", "stop");

            JSONArray choices = new JSONArray();
            choices.put(choice);

            JSONObject resObj = new JSONObject();
            resObj.put("id", "chatcmpl-cache");
            resObj.put("object", "chat.completion");
            resObj.put("model", cached.model);
            resObj.put("choices", choices);

            byte[] jsonBytes = resObj.toString().getBytes(StandardCharsets.UTF_8);

            StringBuilder res = new StringBuilder();
            res.append("HTTP/1.1 200 OK\r\n");
            res.append("Content-Type: application/json; charset=utf-8\r\n");
            res.append("Content-Length: ").append(jsonBytes.length).append("\r\n");
            res.append("Connection: close\r\n");
            res.append("Access-Control-Allow-Origin: *\r\n");
            res.append("X-Cache: HIT-LOCAL-5MS\r\n\r\n");

            out.write(res.toString().getBytes(StandardCharsets.UTF_8));
            out.write(jsonBytes);
            out.flush();
        }
    }

    private void relayAndCaptureResponse(InputStream inBackend, OutputStream outClient,
                                         String cacheKey, String model, JSONArray messages, boolean isStream, GuestPolicy policy) {
        StringBuilder capturedText = new StringBuilder();
        try {
            byte[] buffer = new byte[4096];
            int n;
            while ((n = inBackend.read(buffer)) != -1) {
                outClient.write(buffer, 0, n);
                outClient.flush();

                String chunk = new String(buffer, 0, n, StandardCharsets.UTF_8);
                if (isStream) {
                    String[] lines = chunk.split("\n");
                    for (String line : lines) {
                        if (line.startsWith("data:") && line.contains("\"content\":")) {
                            try {
                                String jsonPart = line.substring(5).trim();
                                if (!jsonPart.equals("[DONE]")) {
                                    JSONObject obj = new JSONObject(jsonPart);
                                    JSONArray choices = obj.optJSONArray("choices");
                                    if (choices != null && choices.length() > 0) {
                                        JSONObject d = choices.getJSONObject(0).optJSONObject("delta");
                                        if (d != null && d.has("content")) {
                                            capturedText.append(d.getString("content"));
                                        }
                                    }
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                } else {
                    capturedText.append(chunk);
                }
            }

            if (capturedText.length() > 0) {
                String fullContent = capturedText.toString();
                if (!isStream) {
                    try {
                        int jsonStart = fullContent.indexOf("{");
                        if (jsonStart != -1) {
                            JSONObject res = new JSONObject(fullContent.substring(jsonStart));
                            JSONArray choices = res.optJSONArray("choices");
                            if (choices != null && choices.length() > 0) {
                                fullContent = choices.getJSONObject(0).getJSONObject("message").getString("content");
                            }
                        }
                    } catch (Exception ignored) {}
                }

                String summary = messages.length() > 0 ? messages.getJSONObject(messages.length() - 1).optString("content", "") : "";
                if (summary.length() > 60) summary = summary.substring(0, 60) + "...";
                int tokens = Math.max(1, fullContent.length() / 4);

                ResponseCacheDb.getInstance(context).put(cacheKey, model, summary, fullContent, tokens);

                if (policy != null && "TOKEN".equals(policy.mode)) {
                    policy.quotaRemaining = Math.max(0, policy.quotaRemaining - tokens);
                    if (multiQuotaListener != null) {
                        multiQuotaListener.onQuotaChanged(policy.key, policy.quotaRemaining, policy.quotaTotal);
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private void pipeSockets(Socket s1, Socket s2, GuestPolicy policy) {
        threadPool.execute(() -> {
            try {
                InputStream is = s1.getInputStream();
                OutputStream os = s2.getOutputStream();
                byte[] b = new byte[8192];
                int r;
                while ((r = is.read(b)) != -1) {
                    os.write(b, 0, r);
                    os.flush();
                }
            } catch (Exception ignored) {}
            closeQuietly(s2);
        });

        try {
            InputStream is = s2.getInputStream();
            OutputStream os = s1.getOutputStream();
            byte[] b = new byte[8192];
            int r;
            long capturedTokens = 0;
            long totalBytes = 0;
            Pattern tokenPattern = Pattern.compile("\"total_tokens\"\\s*:\\s*(\\d+)");

            while ((r = is.read(b)) != -1) {
                os.write(b, 0, r);
                os.flush();
                if (policy != null && "TOKEN".equals(policy.mode)) {
                    totalBytes += r;
                    if (capturedTokens == 0 && r > 20) {
                        String chunkStr = new String(b, 0, r, StandardCharsets.UTF_8);
                        Matcher m = tokenPattern.matcher(chunkStr);
                        if (m.find()) {
                            try {
                                capturedTokens = Long.parseLong(m.group(1));
                            } catch (Exception ignored) {}
                        }
                    }
                }
            }

            if (policy != null && "TOKEN".equals(policy.mode)) {
                long toDeduct = (capturedTokens > 0) ? capturedTokens : Math.max(1, totalBytes / 4);
                policy.quotaRemaining = Math.max(0, policy.quotaRemaining - toDeduct);
                if (multiQuotaListener != null) {
                    multiQuotaListener.onQuotaChanged(policy.key, policy.quotaRemaining, policy.quotaTotal);
                }
            }
        } catch (Exception ignored) {}
        closeQuietly(s1);
    }

    private int findHeaderEnd(byte[] data) {
        for (int i = 0; i < data.length - 3; i++) {
            if (data[i] == '\r' && data[i + 1] == '\n' && data[i + 2] == '\r' && data[i + 3] == '\n') {
                return i + 4;
            }
        }
        return data.length;
    }

    private String computeCacheKey(String model, JSONArray messages) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(model.getBytes(StandardCharsets.UTF_8));
            md.update((byte) '\n');
            md.update(messages.toString().getBytes(StandardCharsets.UTF_8));
            byte[] hash = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(messages.toString().hashCode());
        }
    }

    private void closeQuietly(Socket s) {
        if (s != null && !s.isClosed()) {
            try { s.close(); } catch (Exception ignored) {}
        }
    }
}
