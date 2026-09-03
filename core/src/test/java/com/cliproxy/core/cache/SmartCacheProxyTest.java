package com.cliproxy.core.cache;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

public class SmartCacheProxyTest {

    @Test
    public void testFormatQuotaTokens() {
        assertEquals("10M Tokens", SmartCacheProxy.formatQuota(10_000_000L, "TOKEN"));
        assertEquals("1M Tokens", SmartCacheProxy.formatQuota(1_000_000L, "TOKEN"));
        assertEquals("1.5M Tokens", SmartCacheProxy.formatQuota(1_500_000L, "TOKEN"));
        assertEquals("500k Tokens", SmartCacheProxy.formatQuota(500_000L, "TOKEN"));
        assertEquals("120 Tokens", SmartCacheProxy.formatQuota(120L, "TOKEN"));
    }

    @Test
    public void testFormatQuotaCount() {
        assertEquals("100 次", SmartCacheProxy.formatQuota(100L, "COUNT"));
        assertEquals("0 次", SmartCacheProxy.formatQuota(0L, "COUNT"));
    }

    @Test
    public void testGuestPolicyManagement() {
        List<SmartCacheProxy.GuestPolicy> list = new ArrayList<>();
        list.add(new SmartCacheProxy.GuestPolicy("sk-guest-1", "访客A", "TOKEN", 10_000_000L, 10_000_000L, 10));
        list.add(new SmartCacheProxy.GuestPolicy("sk-guest-2", "访客B", "COUNT", 100L, 100L, 5));

        SmartCacheProxy.setGuestPolicies(list);
        assertEquals(2, SmartCacheProxy.getGuestPolicies().size());

        SmartCacheProxy.GuestPolicy p1 = SmartCacheProxy.getGuestPolicies().get("sk-guest-1");
        assertNotNull(p1);
        assertEquals("访客A", p1.remark);
        assertEquals("TOKEN", p1.mode);
        assertEquals(10_000_000L, p1.quotaRemaining);
        assertEquals(10, p1.rpmLimit);

        SmartCacheProxy.removeGuestPolicy("sk-guest-1");
        assertNull(SmartCacheProxy.getGuestPolicies().get("sk-guest-1"));
        assertEquals(1, SmartCacheProxy.getGuestPolicies().size());
    }

    @Test
    public void testRpmSlidingWindow() {
        SmartCacheProxy.GuestPolicy policy = new SmartCacheProxy.GuestPolicy("sk-test", "测试", "TOKEN", 1000L, 1000L, 3);
        long now = System.currentTimeMillis();

        // 模拟 3 次请求
        policy.requestTimestamps.add(now - 30000);
        policy.requestTimestamps.add(now - 20000);
        policy.requestTimestamps.add(now - 10000);
        assertEquals(3, policy.requestTimestamps.size());
        assertTrue(policy.requestTimestamps.size() >= policy.rpmLimit);

        // 模拟时间推移（60秒前的时间戳被自动剔除）
        long later = now + 65000;
        policy.requestTimestamps.removeIf(ts -> later - ts > 60000);
        assertEquals(0, policy.requestTimestamps.size());
    }

    @Test
    public void testMultiQuotaListenerCallback() {
        AtomicBoolean called = new AtomicBoolean(false);
        AtomicLong reportedRemaining = new AtomicLong(0);

        SmartCacheProxy.setMultiQuotaListener((key, remaining, total) -> {
            called.set(true);
            reportedRemaining.set(remaining);
        });

        SmartCacheProxy.GuestPolicy policy = new SmartCacheProxy.GuestPolicy("sk-listener-test", "回调测试", "COUNT", 100L, 100L, 5);
        // 模拟计次扣除
        policy.quotaRemaining--;
        SmartCacheProxy.MultiQuotaListener l = null;
        try {
            java.lang.reflect.Field f = SmartCacheProxy.class.getDeclaredField("multiQuotaListener");
            f.setAccessible(true);
            l = (SmartCacheProxy.MultiQuotaListener) f.get(null);
        } catch (Exception ignored) {}

        assertNotNull(l);
        l.onQuotaChanged(policy.key, policy.quotaRemaining, policy.quotaTotal);

        assertTrue(called.get());
        assertEquals(99L, reportedRemaining.get());
    }

    @Test
    public void testUsageRegexMatching() {
        Pattern pTotal = Pattern.compile("\"(?:total_tokens|totalTokenCount)\"\\s*:\\s*(\\d+)");
        Pattern pIn = Pattern.compile("\"input_tokens\"\\s*:\\s*(\\d+)");
        Pattern pOut = Pattern.compile("\"output_tokens\"\\s*:\\s*(\\d+)");

        // 1. OpenAI 格式
        String openaiChunk = "data: {\"id\":\"chatcmpl-1\",\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":45,\"total_tokens\":57}}\n\n";
        Matcher mOpenAI = pTotal.matcher(openaiChunk);
        assertTrue(mOpenAI.find());
        assertEquals("57", mOpenAI.group(1));

        // 2. Claude / Gemini 格式
        String claudeChunk = "{\"type\":\"message_delta\",\"usage\":{\"output_tokens\":128,\"totalTokenCount\":256}}";
        Matcher mClaude = pTotal.matcher(claudeChunk);
        assertTrue(mClaude.find());
        assertEquals("256", mClaude.group(1));

        Matcher mClaudeOut = pOut.matcher(claudeChunk);
        assertTrue(mClaudeOut.find());
        assertEquals("128", mClaudeOut.group(1));

        // 3. DeepSeek 拆分格式
        String deepseekChunk = "{\"usage\":{\"input_tokens\":350,\"output_tokens\":820}}";
        Matcher mDsIn = pIn.matcher(deepseekChunk);
        assertTrue(mDsIn.find());
        assertEquals("350", mDsIn.group(1));
    }
}
