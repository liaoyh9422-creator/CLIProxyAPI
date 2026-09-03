package com.cliproxy.core.proot;

import android.content.Context;
import android.util.Log;
import com.cliproxy.core.util.ProcessUtil;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * PRoot 环境与沙箱管理器：
 * 负责解析 apk/lib 原生动态库、释放 glibc 基础运行库、构建 rootfs 虚拟文件系统并拼装 proot 启动参数。
 */
public class ProotManager {
    private static final String TAG = "ProotManager";

    private final Context context;
    private String nativeLibDir;
    private String prootPath;
    private String loaderPath;
    private String linkerPath;
    private String cliproxyPath;
    private String libPath;
    private String rootfsPath;
    private final String tmpDirPath;
    private final String filesDirPath;

    public ProotManager(Context context) {
        this.context = context;
        this.filesDirPath = context.getFilesDir().getAbsolutePath();
        this.tmpDirPath = new File(context.getCacheDir(), "tmp").getAbsolutePath();
        new File(tmpDirPath).mkdirs();
    }

    /** 初始化并检测核心 PRoot 原生库是否存在 */
    public boolean initProotEnv() {
        prootPath = getNativeLib("libproot.so");
        loaderPath = getNativeLib("libloader.so");
        linkerPath = getNativeLib("libldlinux.so");
        cliproxyPath = getNativeLib("libcliproxy.so");

        if (cliproxyPath == null) {
            File f1 = new File(context.getFilesDir(), "libcliproxy.so");
            if (f1.exists() && f1.length() > 5_000_000) {
                f1.setExecutable(true, false);
                cliproxyPath = f1.getAbsolutePath();
            } else {
                File f2 = new File(context.getFilesDir(), "cliproxy");
                if (f2.exists() && f2.length() > 5_000_000) {
                    f2.setExecutable(true, false);
                    cliproxyPath = f2.getAbsolutePath();
                }
            }
        }

        Log.d(TAG, "PRoot 核心组件探测: proot=" + prootPath + ", linker=" + linkerPath + ", cliproxy=" + cliproxyPath);
        return prootPath != null && loaderPath != null && linkerPath != null && cliproxyPath != null;
    }

    public boolean isCliproxyReady() {
        if (getNativeLib("libcliproxy.so") != null) return true;
        File f1 = new File(context.getFilesDir(), "libcliproxy.so");
        if (f1.exists() && f1.length() > 5_000_000) return true;
        File f2 = new File(context.getFilesDir(), "cliproxy");
        return f2.exists() && f2.length() > 5_000_000;
    }

    /** 检索应用的 nativeLibraryDir 或替代目录中的 .so 原生文件 */
    public String getNativeLib(String name) {
        nativeLibDir = context.getApplicationInfo().nativeLibraryDir;
        File f = new File(nativeLibDir, name);
        if (f.exists()) return f.getAbsolutePath();

        String[] altDirs = {
            nativeLibDir.replace("arm64", "arm64-v8a"),
            nativeLibDir.replace("arm64-v8a", "arm64"),
            context.getApplicationInfo().dataDir + "/lib/arm64-v8a",
            context.getApplicationInfo().dataDir + "/lib/arm64",
        };
        for (String dir : altDirs) {
            File alt = new File(dir, name);
            if (alt.exists()) {
                nativeLibDir = dir;
                return alt.getAbsolutePath();
            }
        }
        return null;
    }

    /** 释放并校验 glibc 基础运行库（带版本校验，版本更新时自动覆盖） */
    public boolean setupGlibcLibs() {
        File filesDir = context.getFilesDir();
        File glibcDir = new File(filesDir, "glibc");
        libPath = new File(glibcDir, "usr/lib64").getAbsolutePath();

        int currentVersionCode = 0;
        try {
            currentVersionCode = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionCode;
        } catch (Exception e) {
            Log.w(TAG, "Cannot get versionCode: " + e.getMessage());
        }

        File marker = new File(glibcDir, ".done");
        if (marker.exists() && new File(libPath, "libc.so.6").exists()) {
            try {
                String markerContent = new String(java.nio.file.Files.readAllBytes(marker.toPath())).trim();
                int markerVersionCode = 0;
                try { markerVersionCode = Integer.parseInt(markerContent); } catch (Exception ignored) {}
                if (markerVersionCode == currentVersionCode && currentVersionCode > 0) {
                    return true;
                }
            } catch (Exception ignored) {}
            ProcessUtil.deleteRecursive(glibcDir);
        }

        glibcDir.mkdirs();
        new File(libPath).mkdirs();

        try {
            InputStream is = context.getAssets().open("glibc-libs-arm64.tar.bin");
            File tarFile = new File(glibcDir, "glibc-libs.tar.gz");
            try (FileOutputStream fos = new FileOutputStream(tarFile)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) != -1) fos.write(buf, 0, n);
            }
            is.close();

            Process tarProc = new ProcessBuilder("tar", "xzf", tarFile.getAbsolutePath())
                    .directory(glibcDir)
                    .redirectErrorStream(true)
                    .start();
            try {
                tarProc.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            tarFile.delete();

            File libDir = new File(libPath);
            if (libDir.exists()) {
                File[] libs = libDir.listFiles();
                if (libs != null) {
                    for (File lib : libs) lib.setReadable(true, false);
                }
            }

            File binDir = new File(glibcDir, "usr/bin");
            if (binDir.exists()) {
                File[] bins = binDir.listFiles();
                if (bins != null) {
                    for (File bin : bins) {
                        bin.setExecutable(true, false);
                        bin.setReadable(true, false);
                    }
                }
            }

            boolean ok = new File(libPath, "libc.so.6").exists();
            if (ok) {
                try (FileWriter fw = new FileWriter(marker)) {
                    fw.write(String.valueOf(currentVersionCode));
                }
            }
            return ok;
        } catch (Exception e) {
            Log.e(TAG, "Failed to extract glibc libs", e);
            return false;
        }
    }

    /** 建立精简 rootfs 目录骨架与 DNS/CA 证书配置 */
    public void setupRootfs() {
        File rootfs = new File(context.getFilesDir(), "proot-rootfs");
        rootfsPath = rootfs.getAbsolutePath();

        new File(rootfs, "lib").mkdirs();
        new File(rootfs, "usr/lib64").mkdirs();
        new File(rootfs, "lib64").mkdirs();
        new File(rootfs, "usr/local/bin").mkdirs();
        new File(rootfs, "bin").mkdirs();
        new File(rootfs, "tmp").mkdirs();
        new File(rootfs, "app").mkdirs();
        new File(rootfs, "sdcard").mkdirs();
        new File(rootfs, "storage").mkdirs();
        new File(rootfs, "proc").mkdirs();
        new File(rootfs, "dev").mkdirs();
        new File(rootfs, "sys").mkdirs();
        new File(rootfs, "etc").mkdirs();

        try {
            PrintWriter pw = new PrintWriter(new File(rootfs, "etc/resolv.conf"));
            pw.println("nameserver 8.8.8.8");
            pw.println("nameserver 8.8.4.4");
            pw.println("nameserver 1.1.1.1");
            pw.println("options timeout:5 attempts:3");
            pw.close();

            PrintWriter ns = new PrintWriter(new File(rootfs, "etc/nsswitch.conf"));
            ns.println("hosts: files dns");
            ns.println("networks: files dns");
            ns.close();

            PrintWriter hosts = new PrintWriter(new File(rootfs, "etc/hosts"));
            hosts.println("127.0.0.1 localhost");
            hosts.println("::1 localhost");
            hosts.close();

            setupCaCertificates(rootfs);
        } catch (Exception e) {
            Log.w(TAG, "Failed to write resolv.conf: " + e.getMessage());
        }
    }

    /** 合并系统 CA 根证书到 rootfs 中，确保 Go 程序可建立安全的 HTTPS 连接 */
    private void setupCaCertificates(File rootfs) {
        try {
            File sslCertsDir = new File(rootfs, "etc/ssl/certs");
            sslCertsDir.mkdirs();
            File androidCaDir = new File("/system/etc/security/cacerts");
            if (!androidCaDir.exists()) return;

            File[] caFiles = androidCaDir.listFiles();
            if (caFiles == null || caFiles.length == 0) return;

            File mergedCert = new File(sslCertsDir, "ca-certificates.crt");
            PrintWriter merged = new PrintWriter(mergedCert);
            for (File caFile : caFiles) {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(caFile)))) {
                    String line;
                    while ((line = br.readLine()) != null) merged.println(line);
                } catch (Exception ignored) {}
            }
            merged.close();
        } catch (Exception e) {
            Log.w(TAG, "Failed to setup CA certificates: " + e.getMessage());
        }
    }

    /** 拼装基础 PRoot 沙箱命令参数列表 */
    public List<String> buildProotCommand(String workDir) {
        List<String> cmd = new ArrayList<>();
        cmd.add(prootPath);
        cmd.add("-r"); cmd.add(rootfsPath);
        cmd.add("--link2symlink");
        cmd.add("--root-id");
        cmd.add("--cwd=" + workDir);
        cmd.add("-b"); cmd.add("/dev");
        cmd.add("-b"); cmd.add("/proc");
        cmd.add("-b"); cmd.add("/sys");
        cmd.add("-b"); cmd.add("/data");
        cmd.add("-b"); cmd.add("/sdcard");
        cmd.add("-b"); cmd.add("/storage");
        cmd.add("-b"); cmd.add(linkerPath + ":/lib/ld-linux-aarch64.so.1");
        cmd.add("-b"); cmd.add(libPath + ":/usr/lib64");
        cmd.add("-b"); cmd.add(libPath + ":/lib64");

        File binDir = new File(new File(libPath).getParentFile(), "bin");
        if (binDir.isDirectory()) {
            cmd.add("-b"); cmd.add(binDir.getAbsolutePath() + ":/usr/bin");
        }
        cmd.add("-b"); cmd.add(filesDirPath + ":/app");
        cmd.add("-b"); cmd.add(tmpDirPath + ":/tmp");

        File etcDir = new File(rootfsPath, "etc");
        File resolvConf = new File(etcDir, "resolv.conf");
        if (resolvConf.exists()) {
            cmd.add("-b"); cmd.add(resolvConf.getAbsolutePath() + ":/etc/resolv.conf");
        }
        File nsswitchConf = new File(etcDir, "nsswitch.conf");
        if (nsswitchConf.exists()) {
            cmd.add("-b"); cmd.add(nsswitchConf.getAbsolutePath() + ":/etc/nsswitch.conf");
        }
        File hostsFile = new File(etcDir, "hosts");
        if (hostsFile.exists()) {
            cmd.add("-b"); cmd.add(hostsFile.getAbsolutePath() + ":/etc/hosts");
        }
        File mergedCert = new File(etcDir, "ssl/certs/ca-certificates.crt");
        if (mergedCert.exists()) {
            cmd.add("-b"); cmd.add(mergedCert.getAbsolutePath() + ":/etc/ssl/certs/ca-certificates.crt");
        }
        return cmd;
    }

    /** 拼装启动 cli-proxy-api 的完整 PRoot 命令行 */
    public String[] buildProxyCommand(String workDir, String... extraArgs) {
        List<String> cmd = buildProotCommand(workDir);

        String bashPath = getNativeLib("libbash.so");
        if (bashPath != null) {
            cmd.add("-b"); cmd.add(bashPath + ":/bin/sh");
        }
        if (cliproxyPath != null) {
            cmd.add("-b"); cmd.add(cliproxyPath + ":/usr/local/bin/cli-proxy-api");
        }
        cmd.add("/usr/local/bin/cli-proxy-api");
        for (String arg : extraArgs) {
            cmd.add(arg);
        }
        return cmd.toArray(new String[0]);
    }

    /** 为 PRoot 进程设置必备的运行环境变量 */
    public void setupEnv(ProcessBuilder pb, int serverPort, String projectName) {
        pb.environment().put("LD_LIBRARY_PATH", nativeLibDir != null ? nativeLibDir : "");
        pb.environment().put("PROOT_LOADER", loaderPath != null ? loaderPath : "");
        pb.environment().put("PROOT_TMP_DIR", context.getCacheDir().getAbsolutePath());
        pb.environment().put("TMPDIR", "/tmp");
        pb.environment().put("HOME", "/app");
        pb.environment().put("PORT", String.valueOf(serverPort));
        pb.environment().put("PROJECT_NAME", projectName);
        pb.environment().put("DB_DIR", "/sdcard/Download/" + projectName);
        pb.environment().put("GODEBUG", "netdns=go");
        pb.environment().put("SSL_CERT_FILE", "/etc/ssl/certs/ca-certificates.crt");
        pb.environment().put("SSL_CERT_DIR", "/etc/ssl/certs");
    }

    public String getRootfsPath() { return rootfsPath; }
    public String getProotPath() { return prootPath; }
    public String getLinkerPath() { return linkerPath; }
    public String getCliproxyPath() { return cliproxyPath; }
}
