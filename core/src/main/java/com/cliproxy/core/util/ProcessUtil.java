package com.cliproxy.core.util;

import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 进程与系统底层工具类：
 * 提供针对 Android ART 运行时的 PID 深度反射获取、进程树终止及残留进程扫描强杀功能。
 */
public class ProcessUtil {
    private static final String TAG = "ProcessUtil";

    /**
     * 深度反射获取 Linux 原生进程 PID（兼容各 Android 版本 ART ProcessImpl 实现）
     */
    public static int getPid(Process p) {
        if (p == null) return -1;

        // 1. 尝试 Java 9+ 标准 pid() 方法
        try {
            Method m = p.getClass().getMethod("pid");
            m.setAccessible(true);
            Object res = m.invoke(p);
            if (res instanceof Number) {
                int pid = ((Number) res).intValue();
                if (pid > 0) return pid;
            }
        } catch (Throwable ignored) {}

        // 2. 递归反射遍历 class/superclass 内部私有 pid 字段
        Class<?> clazz = p.getClass();
        while (clazz != null && clazz != Object.class) {
            try {
                Field f = clazz.getDeclaredField("pid");
                f.setAccessible(true);
                int pid = f.getInt(p);
                if (pid > 0) return pid;
            } catch (Throwable ignored) {}
            clazz = clazz.getSuperclass();
        }

        // 3. 兜底解析 toString() 描述信息
        try {
            String s = p.toString();
            Matcher matcher = Pattern.compile("(?i)pid[=\\s](\\d+)").matcher(s);
            if (matcher.find()) {
                return Integer.parseInt(matcher.group(1));
            }
        } catch (Throwable ignored) {}

        return -1;
    }

    /** 终止进程及其所有的衍生子进程树 */
    public static void killProcessTree(Process process) {
        if (process == null) return;
        int pid = getPid(process);
        if (pid > 0) {
            Log.d(TAG, "killProcessTree PID=" + pid);
            try {
                // 终止进程组
                ProcessBuilder pb1 = new ProcessBuilder("/system/bin/sh", "-c",
                        "kill -9 -" + pid + " 2>/dev/null; kill -9 " + pid + " 2>/dev/null");
                pb1.redirectErrorStream(true);
                Process p1 = pb1.start();
                p1.waitFor(1, TimeUnit.SECONDS);
                p1.destroyForcibly();

                // 递归终止子进程
                ProcessBuilder pb2 = new ProcessBuilder("/system/bin/sh", "-c",
                        "pgrep -P " + pid + " 2>/dev/null | xargs kill -9 2>/dev/null");
                pb2.redirectErrorStream(true);
                Process p2 = pb2.start();
                p2.waitFor(1, TimeUnit.SECONDS);
                p2.destroyForcibly();

                // Android 原生杀死
                android.os.Process.killProcess(pid);
            } catch (Exception e) {
                Log.w(TAG, "killProcessTree error: " + e.getMessage());
            }
        }
        process.destroyForcibly();
    }

    /** 清理同应用 UID 下的孤儿/残留进程并释放监听端口 */
    public static void killOrphanedProcesses(File filesDir, int serverPort) {
        try {
            // 原生扫描 /proc 匹配并清理残留子进程
            killProcessesMatching("cli-proxy-api", "libcliproxy.so", "libproot.so", "cloudflared", "tailscaled", "tailscale");

            // 命令行辅助清理
            String[] patterns = {"cli-proxy-api", "libcliproxy.so", "proot", "cloudflared", "tailscaled", "tailscale"};
            for (String pattern : patterns) {
                try {
                    ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c",
                            "pkill -9 -f '" + pattern + "' 2>/dev/null");
                    pb.redirectErrorStream(true);
                    Process p = pb.start();
                    byte[] buf = new byte[1024];
                    while (p.getInputStream().read(buf) != -1) {}
                    p.waitFor(1, TimeUnit.SECONDS);
                    p.destroyForcibly();
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            Log.w(TAG, "killOrphanedProcesses error: " + e.getMessage());
        }
    }

    /** 扫描 /proc/ 遍历属于本应用的所有进程并杀死 */
    private static void killProcessesMatching(String... keywords) {
        try {
            File procDir = new File("/proc");
            File[] files = procDir.listFiles();
            if (files == null) return;
            int myPid = android.os.Process.myPid();

            for (File file : files) {
                if (!file.isDirectory()) continue;
                int pid;
                try {
                    pid = Integer.parseInt(file.getName());
                } catch (NumberFormatException e) {
                    continue;
                }
                if (pid == myPid || pid <= 1) continue;

                File cmdlineFile = new File(file, "cmdline");
                if (!cmdlineFile.exists()) continue;

                try (FileInputStream fis = new FileInputStream(cmdlineFile)) {
                    byte[] buf = new byte[1024];
                    int len = fis.read(buf);
                    if (len > 0) {
                        String cmd = new String(buf, 0, len);
                        for (String kw : keywords) {
                            if (cmd.contains(kw)) {
                                Log.d(TAG, "killProcess found PID=" + pid + " (" + kw + ")");
                                android.os.Process.killProcess(pid);
                                break;
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    /** 递归删除文件或目录 */
    public static void deleteRecursive(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        file.delete();
    }

    /** 快速文件复制 */
    public static void copyFile(File src, File dst) throws IOException {
        if (dst.getParentFile() != null) dst.getParentFile().mkdirs();
        try (InputStream is = new FileInputStream(src);
             OutputStream os = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) os.write(buf, 0, n);
        }
    }
}
