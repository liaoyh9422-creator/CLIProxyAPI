package com.cliproxy.core.metrics;

import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class MetricsTrackerTest {

    private MetricsTracker tracker;

    @Before
    public void setUp() {
        tracker = MetricsTracker.getInstance();
        tracker.reset();
    }

    @Test
    public void testTokenAccumulationAndFormatting() {
        assertEquals("0", tracker.getFormattedTotalTokens());
        assertEquals(0L, tracker.getTotalTokens());

        tracker.addTokens(500);
        assertEquals(500L, tracker.getTotalTokens());
        assertEquals("500", tracker.getFormattedTotalTokens());

        tracker.addTokens(1000);
        assertEquals(1500L, tracker.getTotalTokens());
        assertEquals("1.5 K", tracker.getFormattedTotalTokens());

        tracker.addTokens(1_498_500);
        assertEquals(1_500_000L, tracker.getTotalTokens());
        assertEquals("1.5 M", tracker.getFormattedTotalTokens());
    }

    @Test
    public void testParseLogLineSuccess() {
        String log = "200 | 25ms | 192.168.1.100 | POST \"/v1/chat/completions\"";
        tracker.parseLogLine(log);

        assertEquals(1L, tracker.getTotalRequests());
        assertEquals(1L, tracker.getSuccessRequests());
        assertEquals(0L, tracker.getFailedRequests());
        assertEquals(100.0f, tracker.getSuccessRate(), 0.01f);
        assertEquals(0.0f, tracker.getFailureRate(), 0.01f);
        assertEquals(25L, tracker.getAverageLatencyMs());

        List<MetricsTracker.RequestRecord> records = tracker.getRecentRecords();
        assertEquals(1, records.size());
        MetricsTracker.RequestRecord rec = records.get(0);
        assertEquals(200, rec.statusCode);
        assertEquals("25ms", rec.latency);
        assertEquals("192.168.1.100", rec.clientIp);
        assertEquals("POST", rec.method);
        assertEquals("/v1/chat/completions", rec.path);
        assertEquals("正常响应", rec.errorHint);
        assertTrue(rec.isSuccess());
    }

    @Test
    public void testParseLogLineErrorsAndHints() {
        tracker.parseLogLine("401 | 2ms | 10.0.0.1 | GET \"/v1/models\"");
        tracker.parseLogLine("403 | 1ms | 10.0.0.2 | POST \"/v1/chat/completions\"");
        tracker.parseLogLine("429 | 5ms | 10.0.0.3 | POST \"/v1/chat/completions\"");
        tracker.parseLogLine("500 | 120ms | 10.0.0.4 | POST \"/v1/chat/completions\"");

        assertEquals(4L, tracker.getTotalRequests());
        assertEquals(0L, tracker.getSuccessRequests());
        assertEquals(4L, tracker.getFailedRequests());
        assertEquals(100.0f, tracker.getFailureRate(), 0.01f);

        List<MetricsTracker.RequestRecord> records = tracker.getRecentRecords();
        assertEquals(4, records.size());
        // 最新的记录在列表最前端
        assertEquals(500, records.get(0).statusCode);
        assertEquals("上游大模型服务内部错误", records.get(0).errorHint);

        assertEquals(429, records.get(1).statusCode);
        assertEquals("上游限流 (Rate Limit)", records.get(1).errorHint);

        assertEquals(403, records.get(2).statusCode);
        assertEquals("无权访问 / 额度耗尽 (Forbidden)", records.get(2).errorHint);

        assertEquals(401, records.get(3).statusCode);
        assertEquals("密钥无效 / 未授权 (Unauthorized)", records.get(3).errorHint);
    }

    @Test
    public void testRecentRecordsMaxCapacity() {
        for (int i = 0; i < 70; i++) {
            tracker.parseLogLine("200 | 10ms | 127.0.0.1 | POST \"/test/" + i + "\"");
        }
        assertEquals(70L, tracker.getTotalRequests());
        // 最多保留 MAX_HISTORY (50 条)
        assertEquals(50, tracker.getRecentRecords().size());
    }

    @Test
    public void testReset() {
        tracker.addTokens(1000);
        tracker.parseLogLine("200 | 10ms | 127.0.0.1 | GET \"/v1/models\"");
        tracker.reset();

        assertEquals(0L, tracker.getTotalRequests());
        assertEquals(0L, tracker.getTotalTokens());
        assertEquals(0, tracker.getRecentRecords().size());
    }
}
