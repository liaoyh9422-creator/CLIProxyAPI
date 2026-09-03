package com.cliproxy.core.tunnel;

import org.junit.Test;

import static org.junit.Assert.*;

public class TunnelManagerTest {

    @Test
    public void testExtractTryCloudflareUrl() {
        String logLine = "2026-09-04T06:00:00Z INF | Your quick Tunnel has been created! Visit it at (it may take some time to be reachable): https://swift-alpha-bravo-charlie.trycloudflare.com";
        String extracted = extractDomainFromLog(logLine);
        assertEquals("https://swift-alpha-bravo-charlie.trycloudflare.com", extracted);
    }

    @Test
    public void testExtractTryCloudflareUrlWithANSI() {
        String logLine = "\u001B[32mINF\u001B[0m | https://test-node-1234.trycloudflare.com/foo/bar \u001B[0m";
        String extracted = extractDomainFromLog(logLine);
        assertEquals("https://test-node-1234.trycloudflare.com", extracted);
    }

    @Test
    public void testFailedLogDetection() {
        String line1 = "ERR failed to request quick Tunnel: context deadline exceeded";
        assertTrue(line1.contains("context deadline exceeded") || line1.contains("failed to request quick Tunnel"));

        String line2 = "INF normal message without errors";
        assertFalse(line2.contains("context deadline exceeded") || line2.contains("failed to request quick Tunnel"));
    }

    private String extractDomainFromLog(String line) {
        if (line.contains("trycloudflare.com")) {
            int s = line.indexOf("https://");
            if (s != -1) {
                int e = line.indexOf(".trycloudflare.com", s);
                if (e != -1) {
                    return line.substring(s, e + 18).trim();
                }
            }
        }
        return null;
    }
}
