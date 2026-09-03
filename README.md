# CLIProxyAPI for Android

基于 Android PRoot Linux 虚拟运行环境的高性能掌上 AI 网关应用，无需 Root 权限，直接在 Android 手机上运行官方 [CLIProxyAPI](https://github.com/router-for-me/CLIProxyAPI) 代理网关服务。

## 核心特性

- **官方原生 Go 静态编译**：采用官方 `_no-plugin` aarch64 纯静态构建，零动态链接依赖，稳定可靠。
- **掌上 API 网关**：将各类 AI 服务转换为标准的 OpenAI 兼容 API (`/v1/chat/completions`)。
- **免 Root 虚拟化**：基于轻量级 PRoot Linux 容器沙箱底座运行，安装 APK 即可启动。
- **内置 Web 管理后台**：一键进入可视化控制台 (`/management.html`)，支持在线 OAuth 鉴权与配置热重载。
- **外网穿透隧道**：内置集成 Cloudflare Tunnel 快速穿透，一键生成临时公网 HTTPS 地址。
- **持久化存储**：运行配置与凭据统一保存在 `/sdcard/Download/CLIProxyAPI/`，应用卸载或升级数据不丢失。

## 默认参数

- 默认端口：`8317`
- Base URL：`http://localhost:8317/v1`
- 管理控制台：`http://localhost:8317/management.html`
- 默认管理密码：`cliproxy123`
- 默认 API Key：`sk-cliproxy-default`

## 构建说明

本项目基于 Gradle 构建，产出标准 APK 安装包：

```bash
# Debug 构建
gradle assembleDebug

# Release 正式构建（已集成签名）
gradle assembleRelease
```
