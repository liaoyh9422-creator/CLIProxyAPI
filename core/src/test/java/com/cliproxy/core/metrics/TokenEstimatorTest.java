package com.cliproxy.core.metrics;

import org.junit.Test;
import static org.junit.Assert.*;

public class TokenEstimatorTest {

    @Test
    public void testEmptyOrNull() {
        assertEquals(0, TokenEstimator.estimateTokens(null));
        assertEquals(0, TokenEstimator.estimateTokens("   "));
        assertEquals(0, TokenEstimator.estimateFromByteLength(0));
    }

    @Test
    public void testEnglishAndCode() {
        String code = "function hello() { return 'world'; }";
        long tokens = TokenEstimator.estimateTokens(code);
        assertTrue("Tokens should be greater than 0", tokens > 5);
    }

    @Test
    public void testChineseText() {
        String chinese = "你好，请帮我分析一下这段代码的运行机制与性能瓶颈。";
        long tokens = TokenEstimator.estimateTokens(chinese);
        assertTrue("Tokens for Chinese text should be approximately 10-20", tokens >= 10 && tokens <= 25);
    }

    @Test
    public void testByteLengthEstimation() {
        long tokens = TokenEstimator.estimateFromByteLength(4096);
        assertEquals(1024, tokens);
    }
}
