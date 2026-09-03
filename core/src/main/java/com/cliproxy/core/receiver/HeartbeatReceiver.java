package com.cliproxy.core.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;
import com.cliproxy.core.service.ProxyService;

/**
 * 守护保活广播接收器：
 * 监听开机自启 (BOOT_COMPLETED)、应用覆盖安装 (MY_PACKAGE_REPLACED) 以及定时心跳事件，
 * 若服务先前处于运行状态，则自动恢复拉起后台前台服务。
 */
public class HeartbeatReceiver extends BroadcastReceiver {
    private static final String TAG = "HeartbeatReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : "";
        Log.d(TAG, "收到保活广播: " + action);

        SharedPreferences prefs = context.getSharedPreferences("cliproxy_prefs", Context.MODE_PRIVATE);
        boolean wasRunning = prefs.getBoolean("running", false);

        if (Intent.ACTION_BOOT_COMPLETED.equals(action) ||
            Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            if (wasRunning) {
                Log.i(TAG, "设备重启/应用升级，自动恢复服务");
                ProxyService.start(context);
            }
        } else if (ProxyService.ACTION_HEARTBEAT.equals(action)) {
            if (wasRunning) {
                ProxyService.start(context);
            }
        }
    }
}
