package com.cliproxy.core.service;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import com.cliproxy.core.cache.SmartCacheProxy;
import com.cliproxy.core.mdns.MdnsManager;
import com.cliproxy.core.metrics.MetricsTracker;
import com.cliproxy.core.proot.ProotManager;
import com.cliproxy.core.receiver.HeartbeatReceiver;
import com.cliproxy.core.tailscale.TailscaleManager;
import com.cliproxy.core.tunnel.TunnelManager;
import com.cliproxy.core.util.AssetExtractor;
import com.cliproxy.core.util.ProcessUtil;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * ProxyService: 核心后台前台服务
 * 职责：
 * 1. 管理 Linux PRoot 沙箱进程与 Go 语言 cli-proxy-api 内核的生命周期
 * 2. 调度外网穿透服务（Cloudflare Tunnel）与虚拟局域网（Tailscale）
 * 3. 驱动本地智能响应缓存反向代理（SmartCacheProxy）
 * 4. 维持 WakeLock 与 AlarmManager 心跳保活
 */
public class ProxyService extends Service {
    private static final String TAG = "ProxyService";
    private static final String CHANNEL_ID = "cliproxy_service";
    private static final int NOTIFICATION_ID = 1;
    private static final long HEARTBEAT_INTERVAL = 5 * 60 * 1000L;

    public static final String ACTION_START_TUNNEL   = "START_TUNNEL";
    public static final String ACTION_STOP_TUNNEL    = "STOP_TUNNEL";
    public static final String ACTION_START_TAILSCALE = "START_TAILSCALE";
    public static final String ACTION_STOP_TAILSCALE  = "STOP_TAILSCALE";
    public static final String ACTION_STOP_SERVER    = "STOP_SERVER";
    public static final String ACTION_HEARTBEAT      = "com.cliproxy.app.HEARTBEAT";

    public static final String EXTRA_TUNNEL_MODE     = "tunnel_mode";
    public static final String EXTRA_TUNNEL_CMD      = "tunnel_cmd";
    public static final String EXTRA_SERVER_PORT     = "server_port";
    public static final String EXTRA_TAILSCALE_AUTHKEY = "ts_authkey";
    public static final String EXTRA_TAILSCALE_HOSTNAME = "ts_hostname";

    private static PowerManager.WakeLock wakeLock;

    private Process proxyProcess;
    private boolean userInitiatedStop = false;
    private File logFile;

    private ProotManager prootManager;
    private TunnelManager tunnelManager;
    private TailscaleManager tailscaleManager;
    private SmartCacheProxy smartCacheProxy;

    private int serverPort = 8317;
    private String projectName = "CLIProxy API 网关";

    private final Object startLock = new Object();
    private volatile boolean isStarting = false;

    /** 静态便捷启动前台服务 */
    public static void start(Context context) {
        try {
            Intent intent = new Intent(context, ProxyService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Exception e) {
            Log.w(TAG, "start() failed: " + e.getMessage());
        }
        scheduleHeartbeat(context);
    }

    /** 注册 AlarmManager 定时保活闹钟 */
    private static void scheduleHeartbeat(Context context) {
        try {
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            Intent intent = new Intent(context, HeartbeatReceiver.class);
            intent.setAction(ACTION_HEARTBEAT);
            PendingIntent pi = PendingIntent.getBroadcast(
                    context, 0, intent,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + HEARTBEAT_INTERVAL, pi);
        } catch (Exception e) {
            Log.w(TAG, "scheduleHeartbeat failed: " + e.getMessage());
        }
    }

    /** 取消保活闹钟 */
    private void cancelHeartbeat() {
        try {
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            Intent intent = new Intent(this, HeartbeatReceiver.class);
            intent.setAction(ACTION_HEARTBEAT);
            PendingIntent pi = PendingIntent.getBroadcast(
                    this, 0, intent,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            am.cancel(pi);
        } catch (Exception e) {
            Log.w(TAG, "cancelHeartbeat failed: " + e.getMessage());
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        prootManager = new ProotManager(this);
        tunnelManager = new TunnelManager(this, prootManager);
        tailscaleManager = new TailscaleManager(this);

        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && (wakeLock == null || !wakeLock.isHeld())) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ProxyService:WakeLock");
                wakeLock.acquire();
            }
        } catch (Exception e) {
            Log.w(TAG, "WakeLock acquire failed: " + e.getMessage());
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;

        if (ACTION_STOP_SERVER.equals(action)) {
            userInitiatedStop = true;
            cancelHeartbeat();
            stopProxyServer();
            tunnelManager.stopTunnel();
            tailscaleManager.stop(null);

            SharedPreferences prefs = getSharedPreferences("cliproxy_prefs", MODE_PRIVATE);
            prefs.edit().putBoolean("running", false)
                        .putBoolean("tunnel_running", false)
                        .putBoolean("tailscale_running", false)
                        .apply();

            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_START_TUNNEL.equals(action)) {
            String mode = intent.getStringExtra(EXTRA_TUNNEL_MODE);
            String cmd = intent.getStringExtra(EXTRA_TUNNEL_CMD);
            int port = intent.getIntExtra(EXTRA_SERVER_PORT, serverPort);
            new Thread(() -> tunnelManager.startTunnel(mode, cmd, port, this::updateNotification)).start();
            return START_STICKY;
        }

        if (ACTION_STOP_TUNNEL.equals(action)) {
            tunnelManager.stopTunnel();
            return START_STICKY;
        }

        if (ACTION_START_TAILSCALE.equals(action)) {
            String authKey = intent.getStringExtra(EXTRA_TAILSCALE_AUTHKEY);
            String hostname = intent.getStringExtra(EXTRA_TAILSCALE_HOSTNAME);
            tailscaleManager.start(authKey, hostname, (status, ip, dns) -> {
                if (!ip.isEmpty()) {
                    updateNotification("Tailscale: " + ip + " | :" + serverPort);
                }
            });
            return START_STICKY;
        }

        if (ACTION_STOP_TAILSCALE.equals(action)) {
            tailscaleManager.stop(null);
            return START_STICKY;
        }

        userInitiatedStop = false;
        startForeground(NOTIFICATION_ID, buildNotification("服务初始化中..."));
        new Thread(this::startProxyServer, "proxy-starter").start();
        scheduleHeartbeat(this);
        return START_STICKY;
    }

    /** 核心服务启动逻辑 */
    private void startProxyServer() {
        synchronized (startLock) {
            if (isStarting || (proxyProcess != null && proxyProcess.isAlive())) {
                writeLog("服务正在启动或已在稳定运行中，忽略重复启动请求\n");
                return;
            }
            isStarting = true;
        }

        try {
            MetricsTracker.getInstance().markServerStart();

            SharedPreferences svcPrefs = getSharedPreferences("cliproxy_prefs", MODE_PRIVATE);
            int configuredPort = 8317;
            try (InputStream is = getAssets().open("fullstack_config.json")) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buffer = new byte[1024];
                int len;
                while ((len = is.read(buffer)) != -1) baos.write(buffer, 0, len);
                JSONObject config = new JSONObject(baos.toString("UTF-8"));
                configuredPort = config.optInt("port", 8317);
                projectName = config.optString("projectName", "CLIProxy API 网关");
            } catch (Exception e) {
                configuredPort = 8317;
                projectName = "CLIProxy API 网关";
            }

            serverPort = svcPrefs.getInt("custom_port", configuredPort);

            // 启动前强力清理旧残留进程并释放端口
            ProcessUtil.killOrphanedProcesses(getFilesDir(), serverPort);
            ProcessUtil.killOrphanedProcesses(getFilesDir(), serverPort + 1);
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}

            int publicPort = serverPort;
            int backendPort = serverPort + 1;

            File filesDir = getFilesDir();
            File authDir = new File(filesDir, "auth");
            authDir.mkdirs();

            File configFile = new File(filesDir, "config.yaml");

            // 方案 1: 安全私有沙箱静默自动平滑迁移
            try {
                File oldExternalDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "CLIProxyAPI");
                File oldConfigFile = new File(oldExternalDir, "config.yaml");
                File oldAuthDir = new File(oldExternalDir, "auth");
                if (oldConfigFile.exists() && (!configFile.exists() || configFile.length() == 0)) {
                    copyFileHelper(oldConfigFile, configFile);
                    writeLog("🔒 已将公共存储中的配置文件自动迁移至应用私有安全沙箱\n");
                }
                if (oldAuthDir.exists() && oldAuthDir.isDirectory()) {
                    File[] oldAuths = oldAuthDir.listFiles();
                    if (oldAuths != null) {
                        for (File f : oldAuths) {
                            File target = new File(authDir, f.getName());
                            if (!target.exists()) copyFileHelper(f, target);
                        }
                    }
                }
            } catch (Exception ignored) {}

            if (!configFile.exists() || configFile.length() == 0) {
                try {
                    AssetExtractor.extractAsset(this, "config.default.yaml", configFile);
                    writeLog("✅ 已释放初始配置文件至私有安全沙箱\n");
                } catch (Exception ce) {
                    writeLog("Warning: 提取默认配置失败: " + ce.getMessage() + "\n");
                }
            }

            // 生成指向 backendPort 与私有 auth-dir 的运行时配置
            File runtimeConfigFile = new File(filesDir, "config.runtime.yaml");
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                try (FileInputStream fis = new FileInputStream(configFile)) {
                    byte[] buf = new byte[1024];
                    int r;
                    while ((r = fis.read(buf)) != -1) baos.write(buf, 0, r);
                }
                String yaml = baos.toString("UTF-8");
                yaml = yaml.replaceAll("(?m)^port:\\s*\\d+", "port: " + backendPort);
                yaml = yaml.replaceAll("(?m)^host:\\s*\".*\"", "host: \"127.0.0.1\"");
                yaml = yaml.replaceAll("(?m)^auth-dir:\\s*\".*\"", "auth-dir: \"" + authDir.getAbsolutePath() + "\"");
                try (FileOutputStream fos = new FileOutputStream(runtimeConfigFile)) {
                    fos.write(yaml.getBytes("UTF-8"));
                }
            } catch (Exception e) {
                runtimeConfigFile = configFile;
            }

            if (!prootManager.initProotEnv()) {
                updateNotification("启动失败: 核心组件缺失");
                writeLog("Error: Proot environment initialization failed\n");
                return;
            }

            if (!prootManager.setupGlibcLibs()) {
                updateNotification("启动失败: glibc库解压失败");
                writeLog("Error: Failed to setup glibc runtime libraries\n");
                return;
            }

            prootManager.setupRootfs();

            logFile = new File(filesDir, "cliproxy.log");
            writeLog("=== 核心服务启动 ===\n");
            writeLog("Proot: " + prootManager.getProotPath() + "\n");
            writeLog("Linker: " + prootManager.getLinkerPath() + "\n");
            writeLog("CLIProxy: " + prootManager.getCliproxyPath() + "\n");
            writeLog("Config: " + configFile.getAbsolutePath() + "\n");
            writeLog("Auth: " + authDir.getAbsolutePath() + "\n");

            updateNotification("服务启动中...");

            String[] cmd = prootManager.buildProxyCommand(filesDir.getAbsolutePath(), "-config", runtimeConfigFile.getAbsolutePath());
            Log.d(TAG, "Command: " + String.join(" ", cmd));
            writeLog("启动命令: proot -> cli-proxy-api (内部端口 " + backendPort + ")\n");

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            prootManager.setupEnv(pb, backendPort, projectName);

            proxyProcess = pb.start();
            int pid = ProcessUtil.getPid(proxyProcess);
            writeLog("核心进程已启动 (pid=" + (pid > 0 ? pid : "auto") + ")\n");

            // 启动智能响应缓存代理（监听 publicPort 8317，转发至 backendPort 8318）
            SharedPreferences prefs = getSharedPreferences("cliproxy_prefs", MODE_PRIVATE);
            boolean cacheOn = prefs.getBoolean("cache_enabled", false);
            SmartCacheProxy.setGlobalCacheEnabled(cacheOn);

            if (smartCacheProxy != null) smartCacheProxy.stop();
            smartCacheProxy = new SmartCacheProxy(this, publicPort, backendPort);
            smartCacheProxy.start();
            writeLog("⚡ 本地反向代理就绪 (服务端口 " + publicPort + " -> 后端 " + backendPort + ")" + (cacheOn ? " [缓存开启]\n" : " [缓存关闭]\n"));

            // 启动 mDNS 局域网服务广播（默认不开启）
            if (prefs.getBoolean("mdns_enabled", false)) {
                MdnsManager.getInstance(this).start(publicPort);
                writeLog("🌐 mDNS 局域网广播已开启 (http://cliproxy.local:" + publicPort + "/v1)\n\n");
            } else {
                writeLog("\n");
            }

            Thread.sleep(1500);
            if (!proxyProcess.isAlive()) {
                int exitVal = proxyProcess.exitValue();
                if (exitVal == 0 || userInitiatedStop) {
                    writeLog("服务进程已退出 (code=0)\n");
                } else {
                    writeLog("!!! 进程异常退出，exit code: " + exitVal + "\n");
                }
            } else {
                writeLog("服务进程稳定运行中\n");
                updateNotification("CLIProxy 运行中 :" + publicPort);
            }

            // 实时将 Go 标准输出写入日志并送入 MetricsTracker
            new Thread(() -> {
                try (FileOutputStream logOs = new FileOutputStream(logFile, true)) {
                    InputStream is = proxyProcess.getInputStream();
                    byte[] buffer = new byte[4096];
                    int read;
                    while ((read = is.read(buffer)) != -1) {
                        logOs.write(buffer, 0, read);
                        logOs.flush();
                        String chunk = new String(buffer, 0, read);
                        MetricsTracker.getInstance().parseLogLine(chunk);
                        if (chunk.contains("started successfully") || chunk.contains("Listening") || chunk.contains("listening")) {
                            updateNotification("CLIProxy 运行中 :" + publicPort);
                        }
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Output thread error", e);
                }
            }, "service-output").start();

            int exitCode = proxyProcess.waitFor();
            if (exitCode == 0 || userInitiatedStop) {
                writeLog("服务已正常停止 (code=" + exitCode + ")\n");
            } else {
                writeLog("服务已退出，exit code: " + exitCode + "\n");
            }
            updateNotification("服务已停止 (exit " + exitCode + ")");
        } catch (Exception e) {
            Log.e(TAG, "Proxy start failed", e);
            writeLog("❌ 服务启动异常: " + e.getMessage() + "\n");
            updateNotification("启动异常");
        } finally {
            synchronized (startLock) {
                isStarting = false;
            }
        }
    }

    /** 停止底层代理进程与缓存代理 */
    private void stopProxyServer() {
        MetricsTracker.getInstance().markServerStop();
        MdnsManager.getInstance(this).stop();
        if (smartCacheProxy != null) {
            smartCacheProxy.stop();
            smartCacheProxy = null;
        }
        if (proxyProcess != null && proxyProcess.isAlive()) {
            writeLog("停止核心服务...\n");
            ProcessUtil.killProcessTree(proxyProcess);
            proxyProcess = null;
        }
        if (tailscaleManager != null) {
            tailscaleManager.stop(null);
        }
        ProcessUtil.killOrphanedProcesses(getFilesDir(), serverPort);
        ProcessUtil.killOrphanedProcesses(getFilesDir(), serverPort + 1);
    }

    /** 写入控制台日志文件 */
    private void writeLog(String text) {
        try {
            if (logFile == null) logFile = new File(getFilesDir(), "cliproxy.log");
            try (FileOutputStream fos = new FileOutputStream(logFile, true)) {
                fos.write(text.getBytes("UTF-8"));
                fos.flush();
            }
        } catch (Exception ignored) {}
    }

    /** 创建前台服务通知渠道 */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel chan = new NotificationChannel(
                    CHANNEL_ID, "CLIProxy 代理服务", NotificationManager.IMPORTANCE_LOW);
            chan.setDescription("保持 CLIProxy 后台 API 服务常驻运行");
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(chan);
        }
    }

    /** 构建前台常驻通知 */
    private Notification buildNotification(String text) {
        Intent notifIntent = new Intent();
        notifIntent.setClassName(getPackageName(), "com.cliproxy.app.MainActivity");
        notifIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, notifIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        return builder.setContentTitle(projectName)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    /** 更新常驻通知文本 */
    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(text));
    }

    private static void copyFileHelper(File src, File dst) {
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[4096];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
            out.flush();
        } catch (Exception ignored) {}
    }

    @Override
    public void onDestroy() {
        MdnsManager.getInstance(this).stop();
        if (smartCacheProxy != null) {
            smartCacheProxy.stop();
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
