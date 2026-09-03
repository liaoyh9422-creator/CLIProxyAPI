package com.cliproxy.core.cache;

import android.content.Context;
import android.util.Log;
import com.cliproxy.core.metrics.MetricsTracker;
import com.cliproxy.core.metrics.TokenEstimator;
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

    // 预编译主流 AI 厂商 Token Usage 匹配模式（OpenAI / Claude / Gemini / DeepSeek 等）
    private static final Pattern PATTERN_TOTAL_TOKENS = Pattern.compile("\"(?:total_tokens|totalTokenCount)\"\\s*:\\s*(\\d+)");
    private static final Pattern PATTERN_INPUT_TOKENS = Pattern.compile("\"input_tokens\"\\s*:\\s*(\\d+)");
    private static final Pattern PATTERN_OUTPUT_TOKENS = Pattern.compile("\"output_tokens\"\\s*:\\s*(\\d+)");

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
                    (requestLine.contains("/chat/completions") ||
                     requestLine.contains("/responses") ||
                     requestLine.contains("/v1/messages") ||
                     requestLine.contains("generateContent")));

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

                    // 检测是否包含工具调用（tools / functions / tool_choice / role=tool）
                    boolean hasTools = reqJson.has("tools") || reqJson.has("functions") || reqJson.has("tool_choice");
                    if (!hasTools && messages != null) {
                        for (int mi = 0; mi < messages.length(); mi++) {
                            JSONObject mObj = messages.optJSONObject(mi);
                            if (mObj != null) {
                                String role = mObj.optString("role");
                                if ("tool".equals(role) || "function".equals(role) || mObj.has("tool_calls")) {
                                    hasTools = true;
                                    break;
                                }
                            }
                        }
                    }

                    if (hasTools) {
                        // 带有工具调用的动态交互，智能绕过静态缓存，走极速原生双向直通管道，绝不阻塞卡死！
                        backendSocket = new Socket("127.0.0.1", backendPort);
                        backendSocket.setTcpNoDelay(true);
                        backendSocket.getOutputStream().write(rawHeaderBytes, 0, headerEndIdx);
                        backendSocket.getOutputStream().write(bodyBytes);
                        backendSocket.getOutputStream().flush();
                        pipeSockets(clientSocket, backendSocket, activePolicy, true);
                        return;
                    }

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
                pipeSockets(clientSocket, backendSocket, activePolicy, isChatCompletion);
                return;
            }

            // 缓存关闭 或 非聊天接口：原生 TCP 双向零拷贝直通
            backendSocket = new Socket("127.0.0.1", backendPort);
            backendSocket.setTcpNoDelay(true);

            if (headerBuffer.size() > 0) {
                backendSocket.getOutputStream().write(headerBuffer.toByteArray());
                backendSocket.getOutputStream().flush();
            }

            pipeSockets(clientSocket, backendSocket, activePolicy, isChatCompletion);

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
                chunkObj.put("created", System.currentTimeMillis() / 1000);
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
            resObj.put("created", System.currentTimeMillis() / 1000);
            resObj.put("model", cached.model);
            resObj.put("system_fingerprint", "fp_cliproxy_cache");
            resObj.put("choices", choices);

            JSONObject usage = new JSONObject();
            usage.put("prompt_tokens", 0);
            usage.put("completion_tokens", cached.tokenCount);
            usage.put("total_tokens", cached.tokenCount);
            resObj.put("usage", usage);

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
        long capturedOfficialTokens = 0;
        long capturedIn = 0;
        long capturedOut = 0;
        long totalPayloadBytes = 0;
        boolean settled = false;

        try {
            byte[] buffer = new byte[4096];
            int n;
            boolean streamDone = false;
            while ((n = inBackend.read(buffer)) != -1) {
                outClient.write(buffer, 0, n);
                outClient.flush();
                totalPayloadBytes += n;

                String chunk = new String(buffer, 0, n, StandardCharsets.UTF_8);

                // 嗅探 official usage
                Matcher mTotal = PATTERN_TOTAL_TOKENS.matcher(chunk);
                if (mTotal.find()) {
                    try { capturedOfficialTokens = Long.parseLong(mTotal.group(1)); } catch (Exception ignored) {}
                }
                Matcher mIn = PATTERN_INPUT_TOKENS.matcher(chunk);
                if (mIn.find()) {
                    try { capturedIn = Long.parseLong(mIn.group(1)); } catch (Exception ignored) {}
                }
                Matcher mOut = PATTERN_OUTPUT_TOKENS.matcher(chunk);
                if (mOut.find()) {
                    try { capturedOut = Long.parseLong(mOut.group(1)); } catch (Exception ignored) {}
                }

                if (isStream) {
                    String[] lines = chunk.split("\n");
                    for (String line : lines) {
                        if (line.contains("[DONE]")) {
                            streamDone = true;
                        }
                        if (line.startsWith("data:") && (line.contains("\"content\":") || line.contains("\"text\":"))) {
                            try {
                                String jsonPart = line.substring(5).trim();
                                if (!jsonPart.equals("[DONE]")) {
                                    JSONObject obj = new JSONObject(jsonPart);
                                    JSONArray choices = obj.optJSONArray("choices");
                                    if (choices != null && choices.length() > 0) {
                                        JSONObject d = choices.getJSONObject(0).optJSONObject("delta");
                                        if (d != null) {
                                            if (d.has("content")) capturedText.append(d.getString("content"));
                                            else if (d.has("text")) capturedText.append(d.getString("text"));
                                        }
                                    }
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                    if (streamDone) {
                        break;
                    }
                } else {
                    capturedText.append(chunk);
                    // 非流式下，若检测到 JSON 结尾大括号且包含 choices，可安全提前终止，杜绝 Keep-Alive 挂起
                    if (chunk.contains("\"choices\"") && (chunk.endsWith("}\n") || chunk.endsWith("}\r\n") || chunk.endsWith("}"))) {
                        break;
                    }
                }
            }

            if (capturedText.length() > 0 || totalPayloadBytes > 0) {
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

                String summary = messages != null && messages.length() > 0 ? messages.getJSONObject(messages.length() - 1).optString("content", "") : "";
                if (summary.length() > 60) summary = summary.substring(0, 60) + "...";

                long finalTokens = resolveTokens(capturedOfficialTokens, capturedIn, capturedOut, capturedText, totalPayloadBytes);
                int tokensInt = (int) Math.min(Integer.MAX_VALUE, Math.max(1, finalTokens));

                if (fullContent.length() > 0 && cacheKey != null) {
                    ResponseCacheDb.getInstance(context).put(cacheKey, model, summary, fullContent, tokensInt);
                }
                settleTokens(finalTokens, policy);
                settled = true;
            }
        } catch (Exception ignored) {
        } finally {
            if (!settled && totalPayloadBytes > 0) {
                long finalTokens = resolveTokens(capturedOfficialTokens, capturedIn, capturedOut, capturedText, totalPayloadBytes);
                settleTokens(finalTokens, policy);
                settled = true;
            }
        }
    }

    private void pipeSockets(Socket s1, Socket s2, GuestPolicy policy, boolean isChatCompletion) {
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

        boolean settled = false;
        long capturedOfficialTokens = 0;
        long capturedIn = 0;
        long capturedOut = 0;
        long totalPayloadBytes = 0;
        StringBuilder capturedTextForEstimation = new StringBuilder();

        try {
            InputStream is = s2.getInputStream();
            OutputStream os = s1.getOutputStream();
            byte[] b = new byte[8192];
            int r;

            while ((r = is.read(b)) != -1) {
                // 1. 零延迟即时转发给客户端，打字效果 100% 丝滑
                os.write(b, 0, r);
                os.flush();

                if (!isChatCompletion) {
                    continue;
                }

                totalPayloadBytes += r;

                if (r > 6) {
                    String chunkStr = new String(b, 0, r, StandardCharsets.UTF_8);

                    // 2. 嗅探官方 Usage 数据
                    Matcher mTotal = PATTERN_TOTAL_TOKENS.matcher(chunkStr);
                    if (mTotal.find()) {
                        try { capturedOfficialTokens = Long.parseLong(mTotal.group(1)); } catch (Exception ignored) {}
                    }
                    Matcher mIn = PATTERN_INPUT_TOKENS.matcher(chunkStr);
                    if (mIn.find()) {
                        try { capturedIn = Long.parseLong(mIn.group(1)); } catch (Exception ignored) {}
                    }
                    Matcher mOut = PATTERN_OUTPUT_TOKENS.matcher(chunkStr);
                    if (mOut.find()) {
                        try { capturedOut = Long.parseLong(mOut.group(1)); } catch (Exception ignored) {}
                    }

                    // 3. 收集增量文本用于未带 usage 时的多语言精准估算
                    if (capturedOfficialTokens == 0 && (capturedIn == 0 && capturedOut == 0)) {
                        if (chunkStr.contains("\"content\":") || chunkStr.contains("\"text\":")) {
                            extractChunkText(chunkStr, capturedTextForEstimation);
                        }
                    }

                    // 4. 关键：检测传输结束标志，实现“原地实时结算”，杜绝 Keep-Alive 挂起
                    boolean isEnd = chunkStr.contains("[DONE]") ||
                                    chunkStr.contains("\"type\":\"message_stop\"") ||
                                    chunkStr.contains("\"type\": \"message_stop\"") ||
                                    chunkStr.contains("0\r\n\r\n");

                    if (!isEnd && capturedOfficialTokens > 0 &&
                            (chunkStr.endsWith("}\n") || chunkStr.endsWith("}\r\n") || chunkStr.endsWith("}"))) {
                        isEnd = true;
                    }

                    if (isEnd && !settled) {
                        long finalTokens = resolveTokens(capturedOfficialTokens, capturedIn, capturedOut,
                                capturedTextForEstimation, totalPayloadBytes);
                        settleTokens(finalTokens, policy);
                        settled = true;
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            // 5. 异常中断安全兜底：若流式传输被客户端强行掐断（如点停止）且未曾结算，结算已产生的内容
            if (!settled && isChatCompletion && totalPayloadBytes > 0) {
                long finalTokens = resolveTokens(capturedOfficialTokens, capturedIn, capturedOut,
                        capturedTextForEstimation, totalPayloadBytes);
                settleTokens(finalTokens, policy);
                settled = true;
            }
            closeQuietly(s1);
        }
    }

    private static void extractChunkText(String chunkStr, StringBuilder sb) {
        try {
            String[] lines = chunkStr.split("\n");
            for (String line : lines) {
                line = line.trim();
                if (line.startsWith("data:") && !line.equals("data: [DONE]")) {
                    String json = line.substring(5).trim();
                    if (json.startsWith("{") && json.endsWith("}")) {
                        int contentIdx = json.indexOf("\"content\":\"");
                        if (contentIdx != -1) {
                            int start = contentIdx + 11;
                            int end = findJsonStringEnd(json, start);
                            if (end > start) {
                                sb.append(json, start, end);
                            }
                        } else {
                            int textIdx = json.indexOf("\"text\":\"");
                            if (textIdx != -1) {
                                int start = textIdx + 8;
                                int end = findJsonStringEnd(json, start);
                                if (end > start) {
                                    sb.append(json, start, end);
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    private static int findJsonStringEnd(String s, int start) {
        boolean escape = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (escape) {
                escape = false;
            } else if (c == '\\') {
                escape = true;
            } else if (c == '"') {
                return i;
            }
        }
        return -1;
    }

    private static long resolveTokens(long officialTotal, long inTokens, long outTokens,
                                      StringBuilder capturedText, long totalBytes) {
        if (officialTotal > 0) {
            return officialTotal;
        }
        if (inTokens > 0 || outTokens > 0) {
            return inTokens + outTokens;
        }
        if (capturedText != null && capturedText.length() > 0) {
            long textTokens = TokenEstimator.estimateTokens(capturedText.toString());
            return Math.max(1, textTokens + 30);
        }
        return TokenEstimator.estimateFromByteLength(totalBytes);
    }

    private void settleTokens(long tokens, GuestPolicy policy) {
        if (tokens <= 0) return;
        MetricsTracker.getInstance().addTokens(tokens);
        if (policy != null && "TOKEN".equals(policy.mode)) {
            policy.quotaRemaining = Math.max(0, policy.quotaRemaining - tokens);
            if (multiQuotaListener != null) {
                multiQuotaListener.onQuotaChanged(policy.key, policy.quotaRemaining, policy.quotaTotal);
            }
        }
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
