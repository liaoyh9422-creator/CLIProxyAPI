package com.cliproxy.core.tunnel;

import android.content.Context;
import android.util.Log;
import com.cliproxy.core.proot.ProotManager;
import com.cliproxy.core.util.AssetExtractor;
import com.cliproxy.core.util.ProcessUtil;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * 外网穿透管理器：
 * 负责 Cloudflare Tunnel 守护进程释放、参数配置、PRoot 沙箱内启动及日志转储。
 */
public class TunnelManager {
    private static final String TAG = "TunnelManager";

    private final Context context;
    private final ProotManager prootManager;
    private Process tunnelProcess;
    private final File tunnelLogFile;

    public interface StatusListener {
        void onStatus(String text);
    }

    public TunnelManager(Context context, ProotManager prootManager) {
        this.context = context;
        this.prootManager = prootManager;
        this.tunnelLogFile = new File(context.getFilesDir(), "tunnel.log");
    }

    /** 隧道进程是否存活 */
    public boolean isRunning() {
        return tunnelProcess != null && tunnelProcess.isAlive();
    }

    /** 启动穿透隧道（支持默认 Cloudflare 快速隧道与自定义指令） */
    public void startTunnel(String mode, String customCmd, int port, StatusListener listener) {
        try {
            tunnelLogFile.delete();
            writeTunnelLog("▶ 启动隧道...\n");
            if (listener != null) listener.onStatus("隧道启动中...");

            if (!prootManager.initProotEnv()) {
                writeTunnelLog("❌ PRoot 环境初始化失败，无法启动隧道\n");
                if (listener != null) listener.onStatus("隧道启动失败");
                return;
            }

            File filesDir = context.getFilesDir();
            List<String> cmdParts = prootManager.buildProotCommand("/app");

            if ("custom".equals(mode) && customCmd != null && !customCmd.isEmpty()) {
                String resolvedCmd = customCmd.replace("{PORT}", String.valueOf(port));
                resolvedCmd = resolvedCmd.replaceAll("(^|\\s)cloudflared(\\s|$)", "$1/app/cloudflared$2");

                if (resolvedCmd.contains("/app/cloudflared")) {
                    ensureCloudflaredBinary(filesDir, listener);
                }
                writeTunnelLog("▶ 自定义命令: " + resolvedCmd + "\n\n");
                for (String part : resolvedCmd.split("\\s+")) {
                    if (!part.isEmpty()) cmdParts.add(part);
                }
            } else {
                writeTunnelLog("▶ Cloudflare 一键隧道（临时域名 trycloudflare.com）\n\n");
                ensureCloudflaredBinary(filesDir, listener);

                cmdParts.add("/app/cloudflared");
                cmdParts.add("tunnel");
                cmdParts.add("--url");
                cmdParts.add("http://localhost:" + port);
                cmdParts.add("--no-autoupdate");
                cmdParts.add("--protocol");
                cmdParts.add("http2");
                writeTunnelLog("▶ 转发目标: http://localhost:" + port + "\n");
            }

            if (cmdParts.isEmpty()) {
                writeTunnelLog("❌ 无有效命令\n");
                return;
            }

            writeTunnelLog("▶ 通过 PRoot 沙箱执行\n");
            ProcessBuilder pb = new ProcessBuilder(cmdParts);
            pb.redirectErrorStream(true);
            prootManager.setupEnv(pb, port, "CLIProxy API 网关");

            tunnelProcess = pb.start();
            int pid = ProcessUtil.getPid(tunnelProcess);
            writeTunnelLog("进程已启动 (pid=" + (pid > 0 ? pid : "auto") + ")\n\n");
            if (listener != null) listener.onStatus("隧道运行中");

            // 异步捕获隧道日志
            new Thread(() -> {
                try (FileOutputStream logOs = new FileOutputStream(tunnelLogFile, true)) {
                    InputStream is = tunnelProcess.getInputStream();
                    byte[] buffer = new byte[4096];
                    int read;
                    while ((read = is.read(buffer)) != -1) {
                        logOs.write(buffer, 0, read);
                        logOs.flush();
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Tunnel output thread error", e);
                }
            }, "tunnel-output").start();

            int exitCode = tunnelProcess.waitFor();
            writeTunnelLog("\n进程退出 (code=" + exitCode + ")\n");
            if (listener != null) listener.onStatus("隧道已停止 (exit " + exitCode + ")");
        } catch (Exception e) {
            Log.e(TAG, "Tunnel start failed", e);
            writeTunnelLog("❌ 隧道启动失败: " + e.getMessage() + "\n");
            if (listener != null) listener.onStatus("隧道启动失败");
        }
    }

    /** 确保 cloudflared 可执行二进制文件就绪 */
    private void ensureCloudflaredBinary(File filesDir, StatusListener listener) throws IOException {
        File cf = new File(filesDir, "cloudflared");
        if (!cf.exists() || cf.length() < 1_000_000) {
            writeTunnelLog("📦 从内置 assets 释放 cloudflared...\n");
            if (listener != null) listener.onStatus("释放 cloudflared...");
            AssetExtractor.extractAsset(context, "cloudflared.bin", cf);
            cf.setExecutable(true);
            writeTunnelLog("✅ cloudflared 释放完成 (" + (cf.length() / 1024 / 1024) + "MB)\n");
        }
    }

    /** 停止外网穿透 */
    public void stopTunnel() {
        if (tunnelProcess != null && tunnelProcess.isAlive()) {
            writeTunnelLog("\n▶ 用户停止隧道...\n");
            ProcessUtil.killProcessTree(tunnelProcess);
            tunnelProcess = null;
        }
    }

    /** 写入隧道日志 */
    public void writeTunnelLog(String text) {
        try (FileOutputStream fos = new FileOutputStream(tunnelLogFile, true)) {
            fos.write(text.getBytes("UTF-8"));
            fos.flush();
        } catch (Exception ignored) {}
    }
}
