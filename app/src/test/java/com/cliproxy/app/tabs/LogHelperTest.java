package com.cliproxy.app.tabs;

import org.junit.Test;

import static org.junit.Assert.*;

public class LogHelperTest {

    @Test
    public void testStripDateTime() {
        assertEquals("", LogHelper.stripDateTime(null));

        String log1 = "2026-09-04 06:30:15 [info] service started";
        assertEquals("[info] service started", LogHelper.stripDateTime(log1));

        String log2 = "[2026/09/04 06:30:15] [info ] core listening on 8317";
        assertEquals("[info] core listening on 8317", LogHelper.stripDateTime(log2));

        String log3 = "06:30:15 message without date";
        assertEquals("message without date", LogHelper.stripDateTime(log3));

        String log4 = "Normal log without any time";
        assertEquals("Normal log without any time", LogHelper.stripDateTime(log4));
    }

    @Test
    public void testGetLogTypeRules() {
        // 错误日志
        assertEquals(LogHelper.LogType.ERROR, LogHelper.getLogType("❌ 启动失败: connection refused"));
        assertEquals(LogHelper.LogType.ERROR, LogHelper.getLogType("Fatal Error occurred in proot"));

        // 警告日志
        assertEquals(LogHelper.LogType.WARN, LogHelper.getLogType("⚠ 端口已被占用，尝试重试"));
        assertEquals(LogHelper.LogType.WARN, LogHelper.getLogType("Warning: high memory usage"));

        // 成功日志
        assertEquals(LogHelper.LogType.SUCCESS, LogHelper.getLogType("✅ 穿透成功！公网地址: https://test.trycloudflare.com"));
        assertEquals(LogHelper.LogType.SUCCESS, LogHelper.getLogType("Service started and listening on 8317"));

        // 普通信息日志
        assertEquals(LogHelper.LogType.INFO, LogHelper.getLogType("Executing routine checks"));
        assertEquals(LogHelper.LogType.INFO, LogHelper.getLogType(null));
    }
}
