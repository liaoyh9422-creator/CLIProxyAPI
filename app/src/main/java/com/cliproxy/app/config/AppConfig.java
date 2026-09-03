package com.cliproxy.app.config;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 应用配置管理类：负责从 assets/fullstack_config.json 以及本地配置文件读取与更新端口、密钥等参数
 */
public class AppConfig {
    public static final String PREFS_NAME = "cliproxy_prefs";
    public static final String DEFAULT_API_KEY = "sk-cliproxy-default";

    private final Context context;
    private final SharedPreferences prefs;
    private int defaultPort = 8317;
    private String projectName = "CLIProxy API 网关";

    public AppConfig(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        loadDefaultConfig();
    }

    private void loadDefaultConfig() {
        try (InputStream is = context.getAssets().open("fullstack_config.json")) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int len;
            while ((len = is.read(buf)) != -1) baos.write(buf, 0, len);
            JSONObject json = new JSONObject(baos.toString("UTF-8"));
            defaultPort = json.optInt("port", 8317);
            projectName = json.optString("projectName", "CLIProxy API 网关");
        } catch (Exception ignored) {
            defaultPort = 8317;
            projectName = "CLIProxy API 网关";
        }
    }

    public int getPort() {
        return prefs.getInt("custom_port", defaultPort);
    }

    public void setPort(int port) {
        prefs.edit().putInt("custom_port", port).apply();
    }

    public String getApiKey() {
        return prefs.getString("custom_api_key", DEFAULT_API_KEY);
    }

    public void setApiKey(String key) {
        prefs.edit().putString("custom_api_key", key).apply();
    }

    public String getProjectName() { return projectName; }
    public String getBaseUrl() { return "http://localhost:" + getPort() + "/v1"; }
    public String getChatUrl() { return "http://localhost:" + getPort() + "/v1/chat/completions"; }
    public String getResponsesUrl() { return "http://localhost:" + getPort() + "/v1/responses"; }
    public String getManagementUrl() { return "http://localhost:" + getPort() + "/management.html"; }
}
