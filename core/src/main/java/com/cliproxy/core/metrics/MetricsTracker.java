package com.cliproxy.core.metrics;

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
 * 实时从底层服务日志流中解析 HTTP 访问请求，汇总成功率、延迟、请求数并维护最近 50 条审计流水。
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

        public RequestRecord(String time, int statusCode, String latency, String clientIp, String method, String path) {
            this.time = time;
            this.statusCode = statusCode;
            this.latency = latency;
            this.clientIp = clientIp;
            this.method = method;
            this.path = path;
        }
    }

    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong successRequests = new AtomicLong(0);
    private final AtomicLong failedRequests = new AtomicLong(0);
    private final AtomicLong totalLatencyMs = new AtomicLong(0);
    private long serverStartTimeMs = System.currentTimeMillis();

    private final List<RequestRecord> recentRecords = Collections.synchronizedList(new LinkedList<>());
    private static final int MAX_HISTORY = 50;

    // 匹配 Gin 日志: 200 | 15ms | 127.0.0.1 | GET "/"
    private static final Pattern GIN_PATTERN = Pattern.compile(
            "(\\d{3})\\s*\\|\\s*([0-9.]+(?:µs|ms|s))\\s*\\|\\s*([^\\|]+?)\\s*\\|\\s*(GET|POST|PUT|DELETE|HEAD|OPTIONS)\\s*\"([^\"]+)\"");

    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    private MetricsTracker() {}

    /** 记录服务启动基准时间 */
    public void markServerStart() {
        serverStartTimeMs = System.currentTimeMillis();
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

                totalRequests.incrementAndGet();
                if (code >= 200 && code < 400) {
                    successRequests.incrementAndGet();
                } else {
                    failedRequests.incrementAndGet();
                }

                long ms = parseLatencyToMs(latencyStr);
                totalLatencyMs.addAndGet(ms);

                String nowTime = timeFmt.format(new Date());
                RequestRecord rec = new RequestRecord(nowTime, code, latencyStr, clientIp, method, path);

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

    /** 计算已运行时间（毫秒） */
    public long getUptimeMs() {
        return Math.max(0, System.currentTimeMillis() - serverStartTimeMs);
    }

    /** 获取格式化运行时长字符串（如 01小时 25分 10秒） */
    public String getFormattedUptime() {
        long sec = getUptimeMs() / 1000;
        long h = sec / 3600;
        long m = (sec % 3600) / 60;
        long s = sec % 60;
        if (h > 0) {
            return String.format(Locale.getDefault(), "%02d小时 %02d分 %02d秒", h, m, s);
        }
        return String.format(Locale.getDefault(), "%02d分 %02d秒", m, s);
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
        totalLatencyMs.set(0);
        serverStartTimeMs = System.currentTimeMillis();
        synchronized (recentRecords) {
            recentRecords.clear();
        }
    }
}
