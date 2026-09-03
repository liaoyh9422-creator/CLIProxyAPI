package com.cliproxy.core.metrics;

import android.os.SystemClock;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 流量与运维指标收集器：
 * 实时从底层服务日志流与智能代理中解析 HTTP 访问请求，汇总吞吐、Token、成功率、延迟、异常归因并维护最近 50 条审计流水。
 */
public class MetricsTracker {
    private static final MetricsTracker INSTANCE = new MetricsTracker();

    public static MetricsTracker getInstance() {
        return INSTANCE;
    }

    public static class RequestRecord {
        public final String time;
        public final int statusCode;
        public final String latency;
        public final String clientIp;
        public final String method;
        public final String path;
        public final boolean isCacheHit;
        public final String errorHint;

        public RequestRecord(String time, int statusCode, String latency, String clientIp, String method, String path, boolean isCacheHit) {
            this.time = time;
            this.statusCode = statusCode;
            this.latency = latency;
            this.clientIp = clientIp;
            this.method = method;
            this.path = path;
            this.isCacheHit = isCacheHit;
            this.errorHint = resolveErrorHint(statusCode);
        }

        public boolean isSuccess() {
            return statusCode >= 200 && statusCode < 400;
        }

        private static String resolveErrorHint(int code) {
            if (code >= 200 && code < 400) return "正常响应";
            if (code == 400) return "请求格式非法 (Bad Request)";
            if (code == 401) return "密钥无效 / 未授权 (Unauthorized)";
            if (code == 403) return "无权访问 / 额度耗尽 (Forbidden)";
            if (code == 404) return "接口路径不存在 (Not Found)";
            if (code == 429) return "上游限流 (Rate Limit)";
            if (code == 500) return "上游大模型服务内部错误";
            if (code == 502) return "网关错误 (Bad Gateway)";
            if (code == 503) return "上游模型不可用 (Service Unavailable)";
            if (code == 504) return "网关响应超时 (Gateway Timeout)";
            if (code >= 400 && code < 500) return "客户端请求异常 (" + code + ")";
            if (code >= 500) return "服务端处理异常 (" + code + ")";
            return "状态码 " + code;
        }
    }

    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong successRequests = new AtomicLong(0);
    private final AtomicLong failedRequests = new AtomicLong(0);
    private final AtomicLong totalTokens = new AtomicLong(0);
    private final AtomicLong totalLatencyMs = new AtomicLong(0);

    private volatile boolean isServerRunning = false;
    private long serverStartRealtime = 0;
    private long lastRunDurationMs = 0;

    private final List<RequestRecord> recentRecords = Collections.synchronizedList(new LinkedList<>());
    private static final int MAX_HISTORY = 50;

    // 匹配 Gin 日志: 200 | 15ms | 127.0.0.1 | GET "/"
    private static final Pattern GIN_PATTERN = Pattern.compile(
            "(\\d{3})\\s*\\|\\s*([0-9.]+(?:µs|ms|s))\\s*\\|\\s*([^\\|]+?)\\s*\\|\\s*(GET|POST|PUT|DELETE|HEAD|OPTIONS)\\s*\"([^\"]+)\"");

    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    private MetricsTracker() {}

    /** 记录服务启动基准时间（基于硬件单调时钟） */
    public synchronized void markServerStart() {
        isServerRunning = true;
        serverStartRealtime = SystemClock.elapsedRealtime();
        lastRunDurationMs = 0;
    }

    /** 记录服务停止并冻结本次运行统计 */
    public synchronized void markServerStop() {
        if (isServerRunning) {
            lastRunDurationMs = Math.max(0, SystemClock.elapsedRealtime() - serverStartRealtime);
            isServerRunning = false;
        }
    }

    public boolean isServerRunning() {
        return isServerRunning;
    }

    /** 累加模型吞吐 Token 数 */
    public void addTokens(long count) {
        if (count > 0) {
            totalTokens.addAndGet(count);
        }
    }

    public long getTotalTokens() {
        return totalTokens.get();
    }

    /** 格式化输出 Token 数量（如 145.2 K / 1.5 M） */
    public String getFormattedTotalTokens() {
        long t = totalTokens.get();
        if (t >= 1_000_000) {
            return String.format(Locale.US, "%.1f M", t / 1_000_000.0);
        } else if (t >= 1_000) {
            return String.format(Locale.US, "%.1f K", t / 1_000.0);
        }
        return String.valueOf(t);
    }

    /** 实时解析单行日志并收集指标 */
    public void parseLogLine(String line) {
        if (line == null || !line.contains("|")) return;

        Matcher m = GIN_PATTERN.matcher(line);
        if (m.find()) {
            try {
                int code = Integer.parseInt(m.group(1));
                String latencyStr = m.group(2).trim();
                String clientIp = m.group(3).trim();
                String method = m.group(4);
                String path = m.group(5);
                boolean isCacheHit = line.contains("[⚡缓存秒回]") || line.contains("CACHE");

                totalRequests.incrementAndGet();
                if (code >= 200 && code < 400) {
                    successRequests.incrementAndGet();
                } else {
                    failedRequests.incrementAndGet();
                }

                long ms = parseLatencyToMs(latencyStr);
                totalLatencyMs.addAndGet(ms);

                String nowTime = timeFmt.format(new Date());
                RequestRecord rec = new RequestRecord(nowTime, code, latencyStr, clientIp, method, path, isCacheHit);

                synchronized (recentRecords) {
                    recentRecords.add(0, rec);
                    if (recentRecords.size() > MAX_HISTORY) {
                        recentRecords.remove(recentRecords.size() - 1);
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    private long parseLatencyToMs(String latencyStr) {
        try {
            if (latencyStr.endsWith("ms")) {
                double val = Double.parseDouble(latencyStr.replace("ms", ""));
                return (long) val;
            } else if (latencyStr.endsWith("µs")) {
                return 1;
            } else if (latencyStr.endsWith("s")) {
                double val = Double.parseDouble(latencyStr.replace("s", ""));
                return (long) (val * 1000);
            }
        } catch (Exception ignored) {}
        return 0;
    }

    public long getTotalRequests() { return totalRequests.get(); }
    public long getSuccessRequests() { return successRequests.get(); }
    public long getFailedRequests() { return failedRequests.get(); }

    /** 计算平均请求延迟（毫秒） */
    public long getAverageLatencyMs() {
        long total = totalRequests.get();
        return total > 0 ? (totalLatencyMs.get() / total) : 0;
    }

    /** 计算请求成功率百分比 */
    public float getSuccessRate() {
        long total = totalRequests.get();
        if (total == 0) return 100.0f;
        return (float) (successRequests.get() * 100.0 / total);
    }

    /** 计算失败率百分比 */
    public float getFailureRate() {
        long total = totalRequests.get();
        if (total == 0) return 0.0f;
        return (float) (failedRequests.get() * 100.0 / total);
    }

    /** 计算已运行时间（毫秒） */
    public long getUptimeMs() {
        if (!isServerRunning) {
            return Math.max(0, lastRunDurationMs);
        }
        return Math.max(0, SystemClock.elapsedRealtime() - serverStartRealtime);
    }

    /** 稳健自适应格式化运行时长字符串（未运行 / 秒 / 分 / 小时 / 天） */
    public String getFormattedUptime() {
        if (!isServerRunning && lastRunDurationMs == 0) {
            return "未运行";
        }
        long sec = getUptimeMs() / 1000;
        long days = sec / 86400;
        long h = (sec % 86400) / 3600;
        long m = (sec % 3600) / 60;
        long s = sec % 60;

        String formatted;
        if (days > 0) {
            formatted = String.format(Locale.getDefault(), "%d天 %02d小时", days, h);
        } else if (h > 0) {
            formatted = String.format(Locale.getDefault(), "%02d小时 %02d分", h, m);
        } else {
            formatted = String.format(Locale.getDefault(), "%02d分 %02d秒", m, s);
        }

        if (!isServerRunning) {
            return "已停止 (" + formatted + ")";
        }
        return formatted;
    }

    /** 获取最近 50 条调用流水快照 */
    public List<RequestRecord> getRecentRecords() {
        synchronized (recentRecords) {
            return new ArrayList<>(recentRecords);
        }
    }

    /** 重置所有统计指标 */
    public void reset() {
        totalRequests.set(0);
        successRequests.set(0);
        failedRequests.set(0);
        totalTokens.set(0);
        totalLatencyMs.set(0);
        if (isServerRunning) {
            serverStartRealtime = SystemClock.elapsedRealtime();
        } else {
            lastRunDurationMs = 0;
        }
        synchronized (recentRecords) {
            recentRecords.clear();
        }
    }
}
