package com.cliproxy.core.mdns;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

/**
 * mDNS 局域网服务广播管理器：
 * 1. 声明并获取 WifiManager.MulticastLock 突破 Android 硬件休眠过滤；
 * 2. 注册系统级 NsdManager 服务广播；
 * 3. 运行极轻量级 UDP 组播应答器 (224.0.0.251:5353)，秒回 cliproxy.local A 记录解析，
 *    使 Windows / macOS / Linux 客户端在同一家庭或私有 Wi-Fi 下直接免输 IP 访问。
 */
public class MdnsManager {
    private static final String TAG = "MdnsManager";
    public static final String HOST_NAME = "cliproxy.local";
    private static final String SERVICE_TYPE = "_http._tcp.";
    private static final String MDNS_GROUP = "224.0.0.251";
    private static final int MDNS_PORT = 5353;

    private static volatile MdnsManager instance;

    private final Context context;
    private final NsdManager nsdManager;
    private final WifiManager wifiManager;

    private WifiManager.MulticastLock multicastLock;
    private NsdManager.RegistrationListener registrationListener;
    private MulticastSocket multicastSocket;
    private Thread listenerThread;
    private volatile boolean isRunning = false;

    private MdnsManager(Context context) {
        this.context = context.getApplicationContext();
        this.nsdManager = (NsdManager) this.context.getSystemService(Context.NSD_SERVICE);
        this.wifiManager = (WifiManager) this.context.getSystemService(Context.WIFI_SERVICE);
    }

    public static MdnsManager getInstance(Context context) {
        if (instance == null) {
            synchronized (MdnsManager.class) {
                if (instance == null) {
                    instance = new MdnsManager(context);
                }
            }
        }
        return instance;
    }

    public synchronized boolean isBroadcasting() {
        return isRunning;
    }

    /** 开启 mDNS 广播 */
    public synchronized void start(int port) {
        if (isRunning) return;
        isRunning = true;

        // 1. 申请 Wi-Fi 组播唤醒锁
        try {
            if (wifiManager != null) {
                if (multicastLock == null) {
                    multicastLock = wifiManager.createMulticastLock("cliproxy_mdns_lock");
                    multicastLock.setReferenceCounted(true);
                }
                if (!multicastLock.isHeld()) {
                    multicastLock.acquire();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "申请 MulticastLock 异常: " + e.getMessage());
        }

        // 2. 注册系统级 NsdManager 服务
        try {
            NsdServiceInfo serviceInfo = new NsdServiceInfo();
            serviceInfo.setServiceName("cliproxy");
            serviceInfo.setServiceType(SERVICE_TYPE);
            serviceInfo.setPort(port);

            registrationListener = new NsdManager.RegistrationListener() {
                @Override
                public void onServiceRegistered(NsdServiceInfo nsdServiceInfo) {
                    Log.i(TAG, "NsdManager 服务已注册: " + nsdServiceInfo.getServiceName());
                }

                @Override
                public void onRegistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                    Log.w(TAG, "NsdManager 注册失败: " + errorCode);
                }

                @Override
                public void onServiceUnregistered(NsdServiceInfo arg0) {
                    Log.i(TAG, "NsdManager 服务已注销");
                }

                @Override
                public void onUnregistrationFailed(NsdServiceInfo serviceInfo, int errorCode) {
                    Log.w(TAG, "NsdManager 注销失败: " + errorCode);
                }
            };

            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener);
        } catch (Exception e) {
            Log.w(TAG, "NsdManager 注册异常: " + e.getMessage());
        }

        // 3. 启动后台 UDP 组播应答器 (解析 cliproxy.local)
        startUdpResponder(port);
    }

    /** 停止 mDNS 广播并彻底释放系统资源 */
    public synchronized void stop() {
        if (!isRunning) return;
        isRunning = false;

        // 注销 NsdManager
        if (registrationListener != null && nsdManager != null) {
            try {
                nsdManager.unregisterService(registrationListener);
            } catch (Exception ignored) {}
            registrationListener = null;
        }

        // 关闭 UDP Socket
        if (multicastSocket != null) {
            try {
                multicastSocket.close();
            } catch (Exception ignored) {}
            multicastSocket = null;
        }

        if (listenerThread != null) {
            listenerThread.interrupt();
            listenerThread = null;
        }

        // 释放组播锁，恢复硬件休眠
        if (multicastLock != null && multicastLock.isHeld()) {
            try {
                multicastLock.release();
            } catch (Exception ignored) {}
        }

        Log.i(TAG, "mDNS 广播已完全停止并释放组播锁");
    }

    /** 极轻量级 UDP 组播应答线程 */
    private void startUdpResponder(int port) {
        listenerThread = new Thread(() -> {
            try {
                multicastSocket = new MulticastSocket(MDNS_PORT);
                multicastSocket.setReuseAddress(true);
                InetAddress group = InetAddress.getByName(MDNS_GROUP);
                multicastSocket.joinGroup(group);

                byte[] buf = new byte[1500];
                while (isRunning && !multicastSocket.isClosed()) {
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    try {
                        multicastSocket.receive(packet);
                    } catch (Exception e) {
                        if (!isRunning) break;
                        continue;
                    }

                    // 检查报文中是否包含 "cliproxy" 寻址请求
                    String received = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.ISO_8859_1);
                    if (received.contains("cliproxy")) {
                        byte[] localIp = getLocalWifiIpBytes();
                        if (localIp != null) {
                            byte[] responseData = buildDnsAResponse(packet.getData(), localIp);
                            if (responseData != null) {
                                // 单播回送给查询源
                                DatagramPacket reply = new DatagramPacket(
                                        responseData, responseData.length, packet.getAddress(), packet.getPort());
                                multicastSocket.send(reply);

                                // 组播回送以填充局域网其他客户端缓存
                                DatagramPacket multiReply = new DatagramPacket(
                                        responseData, responseData.length, group, MDNS_PORT);
                                multicastSocket.send(multiReply);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                if (isRunning) {
                    Log.w(TAG, "mDNS UDP 监听器异常: " + e.getMessage());
                }
            }
        }, "mdns-responder");
        listenerThread.setDaemon(true);
        listenerThread.start();
    }

    /** 构造标准 DNS A 记录响应包 */
    private byte[] buildDnsAResponse(byte[] query, byte[] ipBytes) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(baos);

            // Transaction ID (2 bytes)
            out.write(query[0]);
            out.write(query[1]);

            // Flags: 0x8400 (Standard query response, No error, Authoritative)
            out.writeShort(0x8400);

            // Questions count (0)
            out.writeShort(0x0000);
            // Answer RRs count (1)
            out.writeShort(0x0001);
            // Authority RRs count (0)
            out.writeShort(0x0000);
            // Additional RRs count (0)
            out.writeShort(0x0000);

            // Answer Record: Name = \x08cliproxy\x05local\x00
            out.writeByte(8);
            out.write("cliproxy".getBytes(StandardCharsets.US_ASCII));
            out.writeByte(5);
            out.write("local".getBytes(StandardCharsets.US_ASCII));
            out.writeByte(0);

            // Type A (1)
            out.writeShort(0x0001);
            // Class IN (1) with Cache-Flush bit (0x8001)
            out.writeShort(0x8001);
            // TTL: 120 seconds
            out.writeInt(120);
            // Data Length: 4 bytes
            out.writeShort(4);
            // RDATA: IPv4 地址字节
            out.write(ipBytes);

            out.flush();
            return baos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    /** 获取当前手机的有效局域网 Wi-Fi IPv4 地址字节 */
    private byte[] getLocalWifiIpBytes() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                if (intf.isLoopback() || !intf.isUp()) continue;
                String name = intf.getName().toLowerCase();
                if (name.contains("wlan") || name.contains("ap") || name.contains("eth")) {
                    List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
                    for (InetAddress addr : addrs) {
                        if (!addr.isLoopbackAddress() && addr.getAddress().length == 4) {
                            return addr.getAddress();
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }
}
