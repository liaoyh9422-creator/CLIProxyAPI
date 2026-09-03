package com.cliproxy.core.tailscale;

import android.content.Context;
import android.util.Log;
import com.cliproxy.core.util.AssetExtractor;
import com.cliproxy.core.util.ProcessUtil;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/**
 * TailscaleManager: 虚拟局域网管理器
 * 特性：
 * 1. 采用 --tun=userspace-networking 纯用户态网络模式，免 Root 且绝不占用 Android 系统的 VPN 槽位
 * 2. 调度独立 tailscaled 守护进程与 tailscale 客户端控制指令
 * 3. 自动提取 Tailnet 内部分配的 IPv4 地址与 MagicDNS 域名
 */
public class TailscaleManager {
    private static final String TAG = "TailscaleManager";

    private final Context context;
    private final File filesDir;
    private final File tailscaledBin;
    private final File tailscaleBin;
    private final File stateFile;
    private final File sockFile;
    private final File logFile;

    private Process daemonProcess;
    private String tailscaleIp = "";
    private String magicDns = "";

    public interface TailscaleListener {
        void onStatusChanged(String status, String ip, String dns);
    }

    public TailscaleManager(Context context) {
        this.context = context;
        this.filesDir = context.getFilesDir();
        this.tailscaledBin = new File(filesDir, "tailscaled");
        this.tailscaleBin = new File(filesDir, "tailscale");
        this.stateFile = new File(filesDir, "tailscaled.state");
        this.sockFile = new File(filesDir, "tailscaled.sock");
        this.logFile = new File(filesDir, "tailscale.log");
    }

    public boolean isRunning() {
        return daemonProcess != null && daemonProcess.isAlive();
    }

    public String getTailscaleIp() {
        return tailscaleIp;
    }

    public String getMagicDns() {
        return magicDns;
    }

    /** 确保 tailscale 与 tailscaled 静态可执行文件就绪 */
    public synchronized boolean ensureBinaries() {
        if (tailscaledBin.exists() && tailscaledBin.length() > 5_000_000 &&
            tailscaleBin.exists() && tailscaleBin.length() > 5_000_000) {
            tailscaledBin.setExecutable(true);
            tailscaleBin.setExecutable(true);
            return true;
        }

        try {
            writeLog("📦 从内置资源释放 Tailscale 组件 (约 18MB)...\n");
            AssetExtractor.extractAssetTar(context, "tailscale-bin.tar.bin", filesDir);
            tailscaledBin.setExecutable(true);
            tailscaleBin.setExecutable(true);
            writeLog("✅ Tailscale 组件释放完成\n");
            return true;
        } catch (Exception e) {
            writeLog("❌ 释放 Tailscale 失败: " + e.getMessage() + "\n");
            Log.e(TAG, "Failed to extract tailscale", e);
            return false;
        }
    }

    /** 启动 Tailscale 守护进程并加入网络 */
    public void start(String authKey, String hostname, TailscaleListener listener) {
        new Thread(() -> {
            try {
                logFile.delete();
                writeLog("▶ 准备启动 Tailscale 用户态局域网...\n");
                if (listener != null) listener.onStatusChanged("准备组件...", "", "");

                if (!ensureBinaries()) {
                    if (listener != null) listener.onStatusChanged("组件缺失", "", "");
                    return;
                }

                // 清理可能存在的旧 sock 文件与旧进程
                if (sockFile.exists()) sockFile.delete();
                ProcessUtil.killOrphanedProcesses(filesDir, 0);

                writeLog("▶ 启动 tailscaled 守护进程 (userspace 模式，不占系统 VPN)...\n");
                if (listener != null) listener.onStatusChanged("启动守护进程...", "", "");

                ProcessBuilder pbDaemon = new ProcessBuilder(
                        tailscaledBin.getAbsolutePath(),
                        "--tun=userspace-networking",
                        "--socks5-server=localhost:1055",
                        "--state=" + stateFile.getAbsolutePath(),
                        "--socket=" + sockFile.getAbsolutePath()
                );
                pbDaemon.redirectErrorStream(true);
                daemonProcess = pbDaemon.start();

                int pid = ProcessUtil.getPid(daemonProcess);
                writeLog("tailscaled 已启动 (pid=" + (pid > 0 ? pid : "auto") + ")\n");

                // 异步读取 tailscaled 日志
                new Thread(() -> {
                    try (FileOutputStream fos = new FileOutputStream(logFile, true)) {
                        InputStream is = daemonProcess.getInputStream();
                        byte[] buf = new byte[4096];
                        int n;
                        while ((n = is.read(buf)) != -1) {
                            fos.write(buf, 0, n);
                            fos.flush();
                        }
                    } catch (Exception ignored) {}
                }, "tailscaled-log").start();

                // 等待 sock 文件建立就绪
                int waitCount = 0;
                while (!sockFile.exists() && waitCount < 30 && isRunning()) {
                    Thread.sleep(200);
                    waitCount++;
                }

                if (!sockFile.exists()) {
                    writeLog("❌ tailscaled 启动超时或未生成 socket\n");
                    if (listener != null) listener.onStatusChanged("启动超时", "", "");
                    return;
                }

                writeLog("▶ 执行节点认证与入网...\n");
                if (listener != null) listener.onStatusChanged("入网认证中...", "", "");

                // 构建 tailscale up 命令
                String actualHost = (hostname != null && !hostname.trim().isEmpty())
                        ? hostname.trim() : "cliproxy-phone";

                java.util.List<String> upCmd = new java.util.ArrayList<>();
                upCmd.add(tailscaleBin.getAbsolutePath());
                upCmd.add("--socket=" + sockFile.getAbsolutePath());
                upCmd.add("up");
                upCmd.add("--hostname=" + actualHost);
                upCmd.add("--accept-dns=false");
                if (authKey != null && !authKey.trim().isEmpty()) {
                    upCmd.add("--authkey=" + authKey.trim());
                }

                ProcessBuilder pbUp = new ProcessBuilder(upCmd);
                pbUp.redirectErrorStream(true);
                Process pUp = pbUp.start();

                try (BufferedReader br = new BufferedReader(new InputStreamReader(pUp.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        writeLog("[tailscale] " + line + "\n");
                    }
                }
                pUp.waitFor(15, TimeUnit.SECONDS);

                // 查询分配的 Tailscale IPv4
                queryTailscaleIp();

                if (!tailscaleIp.isEmpty()) {
                    writeLog("\n✅ 成功加入 Tailnet 局域网!\n");
                    writeLog("● 节点名称: " + actualHost + "\n");
                    writeLog("● 节点 IPv4: " + tailscaleIp + "\n");
                    writeLog("● 服务入口: http://" + tailscaleIp + ":8317/v1\n\n");
                    if (listener != null) listener.onStatusChanged("已就绪", tailscaleIp, magicDns);
                } else {
                    writeLog("⚠ 已执行入网，正在协商分配 IP...\n");
                    if (listener != null) listener.onStatusChanged("协商中...", "", "");
                }

            } catch (Exception e) {
                Log.e(TAG, "Tailscale 启动异常", e);
                writeLog("❌ 启动失败: " + e.getMessage() + "\n");
                if (listener != null) listener.onStatusChanged("启动失败", "", "");
            }
        }, "tailscale-starter").start();
    }

    /** 查询并刷新节点 IP */
    public String queryTailscaleIp() {
        if (!sockFile.exists()) return "";
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    tailscaleBin.getAbsolutePath(),
                    "--socket=" + sockFile.getAbsolutePath(),
                    "ip", "-4"
            );
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line = br.readLine();
                if (line != null && line.trim().startsWith("100.")) {
                    tailscaleIp = line.trim();
                    return tailscaleIp;
                }
            }
            p.waitFor(3, TimeUnit.SECONDS);
        } catch (Exception ignored) {}
        return tailscaleIp;
    }

    /** 停止并断开 Tailscale */
    public void stop(TailscaleListener listener) {
        new Thread(() -> {
            try {
                writeLog("\n▶ 正在断开 Tailscale 局域网...\n");
                if (sockFile.exists()) {
                    try {
                        ProcessBuilder pb = new ProcessBuilder(
                                tailscaleBin.getAbsolutePath(),
                                "--socket=" + sockFile.getAbsolutePath(),
                                "down"
                        );
                        pb.start().waitFor(3, TimeUnit.SECONDS);
                    } catch (Exception ignored) {}
                }

                if (daemonProcess != null && daemonProcess.isAlive()) {
                    ProcessUtil.killProcessTree(daemonProcess);
                    daemonProcess = null;
                }
                if (sockFile.exists()) sockFile.delete();

                tailscaleIp = "";
                magicDns = "";
                writeLog("✅ Tailscale 局域网已断开\n");
                if (listener != null) listener.onStatusChanged("未连接", "", "");
            } catch (Exception e) {
                Log.e(TAG, "Tailscale stop failed", e);
            }
        }).start();
    }

    private void writeLog(String text) {
        try (FileOutputStream fos = new FileOutputStream(logFile, true)) {
            fos.write(text.getBytes("UTF-8"));
            fos.flush();
        } catch (Exception ignored) {}
    }
}
