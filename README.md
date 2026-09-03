<div align="center">

# 🚀 CLIProxyAPI for Android

**基于 Android PRoot Linux 虚拟化环境的高性能掌上 AI 网关与穿透中心**

[![Android](https://img.shields.io/badge/Android-7.0%2B%20(API%2024%2B)-3DDC84?style=flat-square&logo=android&logoColor=white)](https://developer.android.com)
[![Arch](https://img.shields.io/badge/Arch-arm64--v8a%20(aarch64)-blue?style=flat-square&logo=arm)](https://github.com/router-for-me/CLIProxyAPI)
[![Lite APK](https://img.shields.io/badge/Lite%20APK-1.2%20MB-brightgreen?style=flat-square&logo=speedtest)](https://gitee.com/ishark666/cliproxy-release)
[![Full APK](https://img.shields.io/badge/Full%20APK-50%20MB%20(Offline)-orange?style=flat-square)](https://github.com/router-for-me/CLIProxyAPI)
[![Gradle](https://img.shields.io/badge/Gradle-8.2-02303A?style=flat-square&logo=gradle)](https://gradle.org)
[![License](https://img.shields.io/badge/License-MIT-purple?style=flat-square)](LICENSE)

<p align="center">
  <b>无需 Root 权限</b> · <b>5ms 智能缓存</b> · <b>1.2MB 极简安装包</b> · <b>双通道穿透</b> · <b>多 Key 共享配额</b>
</p>

</div>

---

## 📖 项目简介

**CLIProxyAPI for Android** 是专门为 Android 移动平台量身定制的高性能 AI 代理网关客户端。

本项目基于移动端轻量级 **PRoot Linux 用户态虚拟化容器沙箱**，免 Root 权限直接在手机内部运行官方原生静态编译的 [CLIProxyAPI](https://github.com/router-for-me/CLIProxyAPI) 代理引擎。它能将各类主流 AI 官方渠道平滑转换为标准的 OpenAI API 接口规范（`/v1/chat/completions`、`/v1/models`），并在宿主层集成了**毫秒级响应缓存**、**Cloudflare / Tailscale 双轨远程穿透**、**mDNS 局域网自发现**以及**外网共享多 Key 双轨计量扣费**等强大企业级功能。

---

## 🌟 核心特性一览

### 1. ⚡️ 极致轻量双构建体系 (Dual Flavors)
* **Lite 极速轻量版 (仅 1.2 MB)**：安装包精简至极限，仅保留纯 Native 壳工程，底层核心二进制（glibc 运行时、CLIProxy 内核、Cloudflare、Tailscale）在初次启动时按需从国内镜像极速下载与自动化部署。
* **Full 离线完整版 (50 MB)**：内置全部运行时库与静态二进制文件，完全离线运行，开箱即用。

### 2. 🧠 本地智能响应缓存 (Smart Semantic Cache)
* 内置轻量级 SQLite 高性能响应缓存引擎，支持精确哈希匹配与语义特征识别。
* 针对历史相同/高频 Prompt 实现 **5ms 毫秒级极速秒回**，消耗 **0 Token**，极大节省 API 费用并提升使用体验。

### 3. 🛡️ 外网共享多 Key 管理与双轨配额控制 (Multi-Key Policy)
* **多租户 Guest 密钥分发**：支持为主机创建专属的外网共享 Key（如 `sk-guest-xxxx`），支持一键脱敏、重置与快速复制。
* **双轨扣费机制**：
  - **按 Token 扣减**：支持 10M / 20M / 50M / 100M Token 额度设定，支持精确流式扣减。
  - **按调用次数扣减**：支持 100 次 / 500 次 / 1000 次计次扣减，额度耗尽自动拦截并返回 HTTP 429。
* **动态流控与黑名单**：支持实时 RPM（每分钟请求数）限制与单 Key 禁用/解封。

### 4. 🌐 双通道内网穿透与异地组网 (Dual Tunnels)
* **Cloudflare Tunnel 快速穿透**：无需公网 IP 与服务器，一键生成临时公网安全 HTTPS 直连域名。
* **Tailscale 虚拟局域网**：集成原生 `tailscale` 与 `tailscaled`，支持自定义 AuthKey 与主机名，跨设备实现点对点私有安全互联。

### 5. 📡 mDNS 局域网自发现 (Zero-Config Discovery)
* 服务启动后自动广播 `_cliproxy._tcp` 局域网服务，同一 Wi-Fi 下的客户端（如 NextChat、Cherry Studio、OpenWebUI）无需手动配置 IP，自动感知在线网关。

### 6. 💻 内置 Web 管理后台与持久化
* 随时一键切入内置可视化 Web 控制台（`/management.html`），在线完成 OAuth 授权与配置热重载。
* 所有配置文件与凭据统一保存在 `/sdcard/Download/CLIProxyAPI/`，应用卸载升级数据永不丢失。

---

## 🏗️ 系统架构设计

```mermaid
flowchart TD
    subgraph Client ["客户端生态接入"]
        C1["OpenWebUI / NextChat / Cherry Studio"]
        C2["Python SDK / LangChain / LlamaIndex"]
        C3["第三方外部共享调用者 (Guest Keys)"]
    end

    subgraph AndroidHost ["CLIProxyAPI Android 宿主 (Java / Kotlin)"]
        UI["4-Tab 极客终端面板<br/>(状态监控 / 外网分享 / 仪表盘 / 局域网)"]
        SC["SmartCacheProxy 智能缓存拦截器<br/>(5ms 响应 · Token/计次双轨扣费 · RPM 限频)"]
        mDNS["mDNS 局域网发现广播器"]
        Downloader["BinaryDownloadManager<br/>(Lite 分卷拉取 · SHA-256 校验 · 自动挂载)"]
    end

    subgraph ProotCore ["PRoot 用户态 Linux 虚拟环境 (免 Root)"]
        Linker["ld-linux-aarch64.so.1 + Glibc Libs"]
        ProxyBin["cli-proxy-api 原生静态服务 (端口 :8317)"]
        WebAdmin["/management.html 管理后台"]
    end

    subgraph TunnelGate ["远程穿透双通道"]
        CF["Cloudflare Tunnel (公网临时 HTTPS 直链)"]
        TS["Tailscale Daemon (P2P 虚拟局域网组网)"]
    end

    subgraph Upstream ["上游 AI 服务生态"]
        U1["OpenAI / Anthropic Claude / Google Gemini"]
        U2["DeepSeek / Kimi / MiniMax / 智谱 GLM"]
    end

    Client -->|HTTP / HTTPS| SC
    SC -->|命中缓存 (5ms 秒回)| Client
    SC -->|未命中 / 扣减额度通过| ProxyBin
    ProxyBin --> Upstream

    ProxyBin <--> CF
    ProxyBin <--> TS
    CF --> Client
    TS --> Client

    UI --> Downloader
    UI --> ProotCore
    UI --> SC
    mDNS -.-> Client
```

---

## 🚀 快速上手

### 1. 下载与安装

* 前往 [Releases](https://github.com/router-for-me/CLIProxyAPI/releases) 或国内镜像 [Gitee Release](https://gitee.com/ishark666/cliproxy-release) 获取最新 APK：
  * **日常推荐**：下载 **`app-lite-release.apk` (1.2 MB)**，启动时自动拉取核心组件；
  * **完全离线**：下载 **`app-full-release.apk` (50 MB)**，开箱即用。

### 2. 默认运行参数

| 配置项 | 默认值 | 说明 |
| :--- | :--- | :--- |
| **监听端口** | `8317` | 本地回环与局域网监听端口 |
| **API Base URL** | `http://localhost:8317/v1` | 标准 OpenAI 格式兼容接口 |
| **主管理员 API Key** | `sk-cliproxy-default` | 宿主全局默认管理密钥 |
| **Web 管理后台** | `http://localhost:8317/management.html` | 可视化管理控制台 |
| **默认后台密码** | `cliproxy123` | 进入 Web 管理后台凭证 |
| **存储主目录** | `/sdcard/Download/CLIProxyAPI/` | 统一配置与凭据存储目录 |

---

### 3. 客户端接入示例

#### cURL 测试
```bash
curl http://localhost:8317/v1/chat/completions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer sk-cliproxy-default" \
  -d '{
    "model": "gpt-4o",
    "messages": [{"role": "user", "content": "你好，CLIProxyAPI！"}]
  }'
```

#### Python (OpenAI SDK)
```python
from openai import OpenAI

client = OpenAI(
    base_url="http://127.0.0.1:8317/v1",
    api_key="sk-cliproxy-default"  # 或外网分配的 guest 密钥
)

response = client.chat.completions.create(
    model="gpt-4o",
    messages=[{"role": "user", "content": "写一首赞美开源的十四行诗"}]
)
print(response.choices[0].message.content)
```

#### NextChat / Cherry Studio / OpenWebUI
* **接口地址 (API Endpoint)**：`http://<手机局域网IP>:8317`
* **API 密钥 (API Key)**：`sk-cliproxy-default` 或 `sk-guest-xxxx`

---

## 🛠️ 源码编译与构建

本项目采用标准 Gradle 构建系统，预配置了完整的签名配置与自动化依赖合并。

```bash
# 克隆本仓库
git clone https://github.com/liaoyh9422-creator/CLIProxyAPI.git
cd CLIProxyAPI

# 1. 编译 Lite 极速轻量版 (1.2MB)
gradle assembleLiteRelease
# 产物位置: app/build/outputs/apk/lite/release/app-lite-release.apk

# 2. 编译 Full 离线完整版 (50MB)
gradle assembleFullRelease
# 产物位置: app/build/outputs/apk/full/release/app-full-release.apk

# 3. 编译 Debug 开发版
gradle assembleDebug
```

---

## 📂 项目目录结构

```text
CLIProxyAPI/
├── app/                              # Android 宿主应用模块
│   ├── src/
│   │   ├── main/                     # 通用代码与通用轻量资产
│   │   │   ├── assets/               # 基础配置 (config.default.yaml)
│   │   │   ├── java/                 # 主界面 (MainActivity)、UI 组件
│   │   │   └── jniLibs/arm64-v8a/    # PRoot 原生库 (libproot.so 等)
│   │   ├── full/                     # Full 专用源集 (内置大型二进制包)
│   │   │   ├── assets/               # cloudflared, tailscale, glibc 运行时
│   │   │   └── jniLibs/arm64-v8a/    # libcliproxy.so 核心内核
│   │   └── lite/                     # Lite 专用源集 (极简，外置按需下载)
│   └── build.gradle                  # 包含 full/lite 变体多渠道定义
├── core/                             # 核心逻辑模块
│   └── src/main/java/com/cliproxy/core/
│       ├── cache/                    # SmartCacheProxy 本地智能响应缓存
│       ├── download/                 # BinaryDownloadManager 组件下载与分卷合并
│       ├── proot/                    # PRootManager Linux 虚拟环境挂载与引导
│       ├── tunnel/                   # TunnelManager Cloudflare 隧道穿透
│       ├── tailscale/                # TailscaleManager 虚拟组网守护
│       └── mdns/                     # MdnsManager 局域网服务自发现
└── build.gradle                      # 根构建脚本
```

---

## 🔒 免责声明与许可协议

* 本项目仅供技术研究与个人学习使用，请遵守当地相关法律法规及上游服务商的服务条款。
* 本项目基于 [MIT 协议](LICENSE) 开源。
