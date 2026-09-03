package com.cliproxy.app.config;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class YamlConfigTest {

    @Test
    public void testReplaceApiKeysPreservesSubsequentSections() {
        String originalYaml = "port: 8317\n" +
                "api-keys:\n" +
                "  - \"sk-old-1\"\n" +
                "  - \"sk-old-2\"\n" +
                "\n" +
                "remote-management:\n" +
                "  allow-remote: true\n" +
                "  secret: \"admin123\"\n" +
                "logging:\n" +
                "  level: debug\n";

        List<String> newKeys = Arrays.asList("sk-guest-aaa", "sk-guest-bbb");
        String masterKey = "sk-master";

        String updated = updateApiKeysInYaml(originalYaml, masterKey, newKeys);

        // 验证旧 Key 已被替换
        assertFalse(updated.contains("sk-old-1"));
        assertFalse(updated.contains("sk-old-2"));

        // 验证主 Key 与新 Key 已被写入
        assertTrue(updated.contains("  - \"sk-master\""));
        assertTrue(updated.contains("  - \"sk-guest-aaa\""));
        assertTrue(updated.contains("  - \"sk-guest-bbb\""));

        // 核心验证：后续配置（remote-management、logging 等）未被抹除截断！
        assertTrue("Subsequent remote-management must be preserved!", updated.contains("remote-management:"));
        assertTrue(updated.contains("allow-remote: true"));
        assertTrue(updated.contains("secret: \"admin123\""));
        assertTrue(updated.contains("logging:"));
        assertTrue(updated.contains("level: debug"));
    }

    @Test
    public void testReplaceApiKeysAtEndOfFile() {
        String originalYaml = "port: 8317\n" +
                "api-keys:\n" +
                "  - \"sk-single\"\n";

        List<String> newKeys = Arrays.asList("sk-guest-1");
        String masterKey = "sk-master";

        String updated = updateApiKeysInYaml(originalYaml, masterKey, newKeys);

        assertFalse(updated.contains("sk-single"));
        assertTrue(updated.contains("  - \"sk-master\""));
        assertTrue(updated.contains("  - \"sk-guest-1\""));
    }

    private String updateApiKeysInYaml(String yaml, String masterKey, List<String> guestKeys) {
        int idx = yaml.indexOf("api-keys:");
        if (idx == -1) return yaml;

        int nextSection = -1;
        int currentPos = idx + "api-keys:".length();
        int firstNewline = yaml.indexOf('\n', currentPos);
        if (firstNewline != -1) {
            int lineStart = firstNewline + 1;
            while (lineStart < yaml.length()) {
                int lineEnd = yaml.indexOf('\n', lineStart);
                if (lineEnd == -1) lineEnd = yaml.length();
                String line = yaml.substring(lineStart, lineEnd);
                String trimmed = line.trim();

                if (!trimmed.isEmpty()) {
                    if (!line.startsWith(" ") && !line.startsWith("\t") && !line.startsWith("-")) {
                        nextSection = lineStart;
                        break;
                    }
                }
                lineStart = lineEnd + 1;
            }
        }
        if (nextSection == -1) nextSection = yaml.length();

        StringBuilder sb = new StringBuilder();
        sb.append("api-keys:\n");
        if (masterKey != null && !masterKey.isEmpty()) {
            sb.append("  - \"").append(masterKey).append("\"\n");
        }
        for (String gk : guestKeys) {
            sb.append("  - \"").append(gk).append("\"\n");
        }

        return yaml.substring(0, idx) + sb.toString() + yaml.substring(nextSection);
    }
}
