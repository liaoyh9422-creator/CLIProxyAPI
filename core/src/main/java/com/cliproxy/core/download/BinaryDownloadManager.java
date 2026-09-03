package com.cliproxy.core.download;

import android.content.Context;
import android.util.Log;
import com.cliproxy.core.util.AssetExtractor;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 核心二进制组件按需下载管理器：
 * 支持针对 Lite 版本的 cliproxy, cloudflared, tailscale 高速直连下载、
 * SHA-256 完整性校验、tar.gz 解压与可执行权限赋予。
 */
public class BinaryDownloadManager {
    private static final String TAG = "BinaryDownloadManager";

    public static final String KEY_GLIBC = "glibc";
    public static final String KEY_CLIPROXY = "cliproxy";
    public static final String KEY_CLOUDFLARED = "cloudflared";
    public static final String KEY_TAILSCALE = "tailscale";

    // 纯公开直链，无需任何 Token 凭据，单文件 <10MB 免登录
    private static final String BASE_URL = "https://gitee.com/ishark666/cliproxy-release/raw/master/";
    private static final String BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36";

    private static final String[] PARTS_GLIBC = {
        "glibc-libs-arm64.tar.gz"
    };
    private static final String[] PARTS_CLIPROXY = {
        "cliproxy-arm64.tar.gz.part00",
        "cliproxy-arm64.tar.gz.part01"
    };
    private static final String[] PARTS_CLOUDFLARED = {
        "cloudflared-arm64.tar.gz"
    };
    private static final String[] PARTS_TAILSCALE = {
        "tailscale-arm64.tar.gz.part00",
        "tailscale-arm64.tar.gz.part01"
    };

    public static final String SHA256_GLIBC = "cfd1adb3303defae5ff58a68d7bdfc423d61998034a342562d5528e21a9a7b3d";
    public static final String SHA256_CLIPROXY = "a01f32c0dba0c131e9e4fb1e42a2b654b5b6b6733448a322aea8ee9a4bac90aa";
    public static final String SHA256_CLOUDFLARED = "17d4de0fa39b7b166aa88893b45f826947a50a0a48b82aeefd0242499ee64dde";
    public static final String SHA256_TAILSCALE = "a3d577e6f98ead125c1e50894e7fabfd4abab5b578d671b6200c57ebbc8d4208";

    private static final ExecutorService downloadPool = Executors.newSingleThreadExecutor();

    public interface DownloadListener {
        void onProgress(long bytesRead, long totalBytes, int percent, double speedMBs);
        void onStatus(String statusText);
        void onSuccess(File targetDir);
        void onFailure(String errorMessage);
    }

    /** 检测 Glibc 基础运行库是否就绪（内置 assets 或已解压在 filesDir/glibc） */
    public static boolean isGlibcReady(Context context) {
        File glibcDir = new File(context.getFilesDir(), "glibc");
        File marker = new File(glibcDir, ".done");
        File libc = new File(glibcDir, "usr/lib/aarch64-linux-gnu/libc.so.6");
        if (marker.exists() && libc.exists()) return true;

        try (InputStream is = context.getAssets().open("glibc-libs-arm64.tar.bin")) {
            return is != null;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** 检测 CLIProxy 核心是否就绪（内置 nativeLibraryDir 或已外置下载至 filesDir） */
    public static boolean isCliproxyReady(Context context) {
        String nativeDir = context.getApplicationInfo().nativeLibraryDir;
        if (new File(nativeDir, "libcliproxy.so").exists()) return true;

        File f1 = new File(context.getFilesDir(), "libcliproxy.so");
        if (f1.exists() && f1.length() > 5_000_000) return true;

        File f2 = new File(context.getFilesDir(), "cliproxy");
        return f2.exists() && f2.length() > 5_000_000;
    }

    /** 检测 Cloudflared 隧道程序是否就绪（内置 assets 或已解压在 filesDir） */
    public static boolean isCloudflaredReady(Context context) {
        File cf = new File(context.getFilesDir(), "cloudflared");
        if (cf.exists() && cf.length() > 1_000_000) return true;

        try {
            String[] list = context.getAssets().list("");
            if (list != null) {
                for (String s : list) {
                    if ("cloudflared.bin".equals(s)) return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    /** 检测 Tailscale 局域网组件是否就绪（内置 assets 或已解压在 filesDir） */
    public static boolean isTailscaleReady(Context context) {
        File ts = new File(context.getFilesDir(), "tailscale");
        File tsd = new File(context.getFilesDir(), "tailscaled");
        if (ts.exists() && ts.length() > 3_000_000 && tsd.exists() && tsd.length() > 3_000_000) {
            return true;
        }

        try {
            String[] list = context.getAssets().list("");
            if (list != null) {
                for (String s : list) {
                    if ("tailscale-bin.tar.bin".equals(s)) return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    /**
     * 启动组件异步下载（支持分卷拉取、合并与校验）
     */
    public static void downloadComponent(Context context, String key, DownloadListener listener) {
        downloadPool.execute(() -> {
            String[] parts;
            String expectedSha;
            String compName;
            long approxTotalBytes;

            switch (key) {
                case KEY_GLIBC:
                    parts = PARTS_GLIBC;
                    expectedSha = SHA256_GLIBC;
                    compName = "Glibc 基础运行库";
                    approxTotalBytes = 4_874_682L;
                    break;
                case KEY_CLIPROXY:
                    parts = PARTS_CLIPROXY;
                    expectedSha = SHA256_CLIPROXY;
                    compName = "CLIProxy 核心代理";
                    approxTotalBytes = 19_154_341L;
                    break;
                case KEY_CLOUDFLARED:
                    parts = PARTS_CLOUDFLARED;
                    expectedSha = SHA256_CLOUDFLARED;
                    compName = "Cloudflare 穿透组件";
                    approxTotalBytes = 9_027_548L;
                    break;
                case KEY_TAILSCALE:
                    parts = PARTS_TAILSCALE;
                    expectedSha = SHA256_TAILSCALE;
                    compName = "Tailscale 组网组件";
                    approxTotalBytes = 18_133_348L;
                    break;
                default:
                    if (listener != null) listener.onFailure("未知组件标识: " + key);
                    return;
            }

            File cacheDir = context.getCacheDir();
            File mergedArchive = new File(cacheDir, key + "-merged.tar.gz");
            File filesDir = context.getFilesDir();
            File[] partFiles = new File[parts.length];

            try {
                long totalBytesDownloaded = 0;
                long lastSpeedCalcTime = System.currentTimeMillis();
                long lastSpeedBytes = 0;
                double speedMBs = 0.0;

                for (int i = 0; i < parts.length; i++) {
                    String partName = parts[i];
                    String downloadUrl = BASE_URL + partName;
                    File partTarget = new File(cacheDir, partName);
                    partFiles[i] = partTarget;

                    if (parts.length > 1) {
                        if (listener != null) listener.onStatus("正在下载 " + compName + " (分卷 " + (i + 1) + "/" + parts.length + ")...");
                    } else {
                        if (listener != null) listener.onStatus("正在连接 Gitee 下载 " + compName + "...");
                    }

                    totalBytesDownloaded = downloadSingleFileWithRedirect(
                            downloadUrl,
                            partTarget,
                            totalBytesDownloaded,
                            approxTotalBytes,
                            listener
                    );
                }

                if (listener != null) {
                    listener.onProgress(approxTotalBytes, approxTotalBytes, 100, 0.0);
                    listener.onStatus("分卷下载完成，正在合并为完整资源包...");
                }

                // 合并分卷
                try (FileOutputStream fos = new FileOutputStream(mergedArchive)) {
                    byte[] mergeBuf = new byte[64 * 1024];
                    for (File pf : partFiles) {
                        try (FileInputStream fis = new FileInputStream(pf)) {
                            int r;
                            while ((r = fis.read(mergeBuf)) != -1) {
                                fos.write(mergeBuf, 0, r);
                            }
                        }
                        pf.delete();
                    }
                }

                if (listener != null) {
                    listener.onStatus("正在进行 SHA-256 完整性安全校验...");
                }

                // 校验 SHA-256
                String actualSha = calculateSha256(mergedArchive);
                if (!expectedSha.equalsIgnoreCase(actualSha)) {
                    mergedArchive.delete();
                    throw new Exception("文件完整性校验未通过！期望 " + expectedSha.substring(0, 8) + "，实际 " + (actualSha != null ? actualSha.substring(0, 8) : "null"));
                }

                if (listener != null) listener.onStatus("校验通过，正在解压安装...");

                // 解压安装
                if (KEY_GLIBC.equals(key)) {
                    File glibcDir = new File(filesDir, "glibc");
                    glibcDir.mkdirs();
                    unpackTarGz(mergedArchive, glibcDir);
                    mergedArchive.delete();

                    File libDir = new File(glibcDir, "usr/lib/aarch64-linux-gnu");
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
                    try {
                        int versionCode = 0;
                        try {
                            versionCode = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionCode;
                        } catch (Exception ignored) {}
                        File marker = new File(glibcDir, ".done");
                        try (FileOutputStream fos = new FileOutputStream(marker)) {
                            fos.write(String.valueOf(versionCode).getBytes());
                        }
                    } catch (Exception ignored) {}
                } else {
                    unpackTarGz(mergedArchive, filesDir);
                    mergedArchive.delete();

                    // 针对特定组件处理可执行权限
                    if (KEY_CLIPROXY.equals(key)) {
                        File so = new File(filesDir, "libcliproxy.so");
                        if (so.exists()) so.setExecutable(true, false);
                    } else if (KEY_CLOUDFLARED.equals(key)) {
                        File bin = new File(filesDir, "cloudflared.bin");
                        File target = new File(filesDir, "cloudflared");
                        if (bin.exists() && !target.exists()) {
                            bin.renameTo(target);
                        }
                        if (target.exists()) target.setExecutable(true, false);
                    } else if (KEY_TAILSCALE.equals(key)) {
                        File ts = new File(filesDir, "tailscale");
                        File tsd = new File(filesDir, "tailscaled");
                        if (ts.exists()) ts.setExecutable(true, false);
                        if (tsd.exists()) tsd.setExecutable(true, false);
                    }
                }

                if (listener != null) {
                    listener.onStatus("组件安装完成！");
                    listener.onSuccess(filesDir);
                }

            } catch (Exception e) {
                Log.e(TAG, "Download failed for " + key, e);
                for (File pf : partFiles) {
                    if (pf != null) pf.delete();
                }
                mergedArchive.delete();
                if (listener != null) listener.onFailure(e.getMessage() != null ? e.getMessage() : "下载失败");
            }
        });
    }

    private static long downloadSingleFileWithRedirect(
            String initialUrl,
            File targetFile,
            long initialTotalRead,
            long grandTotalBytes,
            DownloadListener listener) throws Exception {

        URL currentUrl = new URL(initialUrl);
        HttpURLConnection conn = null;
        int redirects = 0;
        int code;

        while (redirects < 6) {
            conn = (HttpURLConnection) currentUrl.openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(false); // 手动跟踪以传递标准浏览器请求头
            conn.setRequestProperty("User-Agent", BROWSER_UA);
            conn.setRequestProperty("Accept", "*/*");
            conn.setRequestProperty("Connection", "keep-alive");
            conn.connect();

            code = conn.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                String loc = conn.getHeaderField("Location");
                conn.disconnect();
                if (loc == null || loc.isEmpty()) {
                    throw new Exception("重定向地址为空 (HTTP " + code + ")");
                }
                currentUrl = new URL(loc);
                redirects++;
            } else {
                break;
            }
        }

        code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            conn.disconnect();
            throw new Exception("服务器响应异常 HTTP " + code);
        }

        long totalRead = initialTotalRead;
        try (InputStream is = conn.getInputStream();
             FileOutputStream fos = new FileOutputStream(targetFile)) {

            byte[] buf = new byte[64 * 1024];
            int n;
            long lastTime = System.currentTimeMillis();
            long lastBytes = totalRead;
            double speedMBs = 0.0;

            while ((n = is.read(buf)) != -1) {
                fos.write(buf, 0, n);
                totalRead += n;

                long now = System.currentTimeMillis();
                if (now - lastTime >= 250) {
                    double seconds = (now - lastTime) / 1000.0;
                    speedMBs = ((totalRead - lastBytes) / (1024.0 * 1024.0)) / seconds;
                    lastTime = now;
                    lastBytes = totalRead;

                    int pct = (grandTotalBytes > 0) ? (int) (totalRead * 100 / grandTotalBytes) : 0;
                    if (pct > 100) pct = 100;
                    if (listener != null) {
                        listener.onProgress(totalRead, grandTotalBytes, pct, speedMBs);
                    }
                }
            }
            fos.flush();
        } finally {
            conn.disconnect();
        }

        return totalRead;
    }

    private static String calculateSha256(File file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (FileInputStream fis = new FileInputStream(file)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = fis.read(buf)) != -1) {
                    digest.update(buf, 0, n);
                }
            }
            byte[] hash = digest.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static void unpackTarGz(File tarGzFile, File destDir) throws Exception {
        destDir.mkdirs();
        // 尝试通过系统原生 tar 解压
        ProcessBuilder pb = new ProcessBuilder("tar", "-xzf", tarGzFile.getAbsolutePath(), "-C", destDir.getAbsolutePath());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        int exit = p.waitFor();
        if (exit != 0) {
            // 回退到 AssetExtractor 提取
            AssetExtractor.extractTarFile(tarGzFile, destDir);
        }
    }
}
