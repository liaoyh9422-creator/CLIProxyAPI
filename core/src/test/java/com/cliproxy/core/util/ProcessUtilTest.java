package com.cliproxy.core.util;

import org.junit.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

public class ProcessUtilTest {

    @Test
    public void testGetPidNullSafety() {
        assertEquals(-1, ProcessUtil.getPid(null));
    }

    @Test
    public void testKillProcessNullSafety() {
        try {
            ProcessUtil.killProcessTree(null);
            ProcessUtil.killProcessesNamed((String[]) null);
            ProcessUtil.killProcessesNamed();
        } catch (Exception e) {
            fail("ProcessUtil should never throw on null or empty arguments: " + e.getMessage());
        }
    }

    @Test
    public void testPidRegexExtraction() {
        Pattern pattern = Pattern.compile("(?i)pid[=\\s](\\d+)");

        Matcher m1 = pattern.matcher("ProcessImpl[pid=12345, exitValue=\"not exited\"]");
        assertTrue(m1.find());
        assertEquals("12345", m1.group(1));

        Matcher m2 = pattern.matcher("UNIXProcess[PID 98765]");
        assertTrue(m2.find());
        assertEquals("98765", m2.group(1));

        Matcher m3 = pattern.matcher("InvalidProcessObject");
        assertFalse(m3.find());
    }
}
