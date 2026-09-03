package com.cliproxy.core.tailscale;

import org.junit.Test;

import static org.junit.Assert.*;

public class TailscaleManagerTest {

    @Test
    public void testValidTailscaleIPv4Detection() {
        String output1 = "100.115.92.48\n";
        assertTrue(isValidTailscaleIp(output1.trim()));

        String output2 = "100.64.0.1";
        assertTrue(isValidTailscaleIp(output2.trim()));

        String invalid1 = "192.168.1.1";
        assertFalse(isValidTailscaleIp(invalid1.trim()));

        String invalid2 = "";
        assertFalse(isValidTailscaleIp(invalid2));

        String invalid3 = "error: tailscale not running";
        assertFalse(isValidTailscaleIp(invalid3));
    }

    @Test
    public void testTailscaleHostnameSanitization() {
        String rawHostname = "My Android Phone @ 2026!";
        String sanitized = sanitizeHostname(rawHostname);
        assertEquals("my-android-phone-2026", sanitized);
    }

    private boolean isValidTailscaleIp(String ip) {
        if (ip == null || ip.isEmpty()) return false;
        // Tailscale CGNAT 专有网段 100.64.0.0/10 (100.64.x.x - 100.127.x.x)
        if (!ip.startsWith("100.")) return false;
        String[] parts = ip.split("\\.");
        if (parts.length != 4) return false;
        try {
            int secondOctet = Integer.parseInt(parts[1]);
            return secondOctet >= 64 && secondOctet <= 127;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private String sanitizeHostname(String host) {
        if (host == null || host.isEmpty()) return "cliproxy-node";
        String s = host.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return s.isEmpty() ? "cliproxy-node" : s;
    }
}
