# TV 端 VPN APP 开发文档

## 1. 项目概述

### 1.1 定位
TV 端是一个**纯 VPN 执行器**，零后端 API 依赖。所有数据（节点列表、用户 UUID、订阅信息）由手机端通过局域网扫码推送，TV 端本地存储后独立运行 VPN。

### 1.2 项目信息
- 项目路径：`E:\v2borad_tv_vpn`
- 包名：`com.v2rayng.mytv`
- 最低 SDK：23（Android 6.0）
- 目标 SDK：36
- UI 框架：Leanback（TV 专用，焦点管理完善）
- VPN 核心：复用手机端 libv2ray（xray-core gomobile 绑定）

---

## 2. 核心架构

### 2.1 架构图

```
┌─────────────────────────────────────────────────────┐
│                    TV 端 APP                         │
│                                                     │
│  ┌──────────┐  ┌──────────┐  ┌───────────────────┐ │
│  │ 二维码页  │  │  主页    │  │   节点列表页      │ │
│  │ (配对)   │  │ (VPN控制)│  │ (遥控器选择)     │ │
│  └────┬─────┘  └────┬─────┘  └───────┬───────────┘ │
│       │              │                │             │
│  ┌────▼──────────────▼────────────────▼───────────┐ │
│  │              本地 HTTP Server                   │ │
│  │         (NanoHTTPD, 端口 8866)                  │ │
│  │  POST /api/pair    — 首次配对，接收全量数据     │ │
│  │  POST /api/sync    — 同步最新节点/订阅         │ │
│  │  POST /api/command — 接收指令(connect/stop等)   │ │
│  │  GET  /api/status  — 返回当前 VPN 状态         │ │
│  └────────────────────┬───────────────────────────┘ │
│                       │                             │
│  ┌────────────────────▼───────────────────────────┐ │
│  │              VPN 核心层                         │ │
│  │  V2RayConfig → MyVpnService → libv2ray         │ │
│  │  VpnConnectionManager / Tun2Socks              │ │
│  └────────────────────────────────────────────────┘ │
│                       │                             │
│  ┌────────────────────▼───────────────────────────┐ │
│  │           DataStore 持久化                      │ │
│  │  节点列表 / UUID / 订阅信息 / 配对时间         │ │
│  └────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────┘

        ↕ 局域网 HTTP (同一 WiFi)

┌─────────────────────────────────────────────────────┐
│                  手机端 APP                          │
│                                                     │
│  设置页 → "连接 TV" → 扫码 → 推送数据/指令         │
│                                                     │
│  数据来源：手机端已有的 v2board API 数据            │
│  - 用户 UUID                                        │
│  - 节点列表 (ServerDto[])                           │
│  - 订阅信息 (套餐名/到期时间/流量)                  │
│  - 品牌信息 (APP 名称/描述)                         │
└─────────────────────────────────────────────────────┘
```

### 2.2 设计原则
- TV 端不调用任何 v2board 后端 API
- 所有数据通过手机端局域网推送
- 数据本地持久化，支持离线使用
- 7 天过期 + 手动同步双重策略
- 遥控器可独立操作（作为手机不在身边时的 fallback）

---

## 3. 页面设计

### 3.1 页面流程

```
启动 → 检查本地缓存
  ├─ 无数据 → 二维码配对页
  ├─ 数据过期(>7天) → 二维码配对页 + 提示"数据已过期，请重新扫码同步"
  └─ 有效数据 → 主页
```

### 3.2 二维码配对页

**布局（横屏 1920x1080）：**

```
┌──────────────────────────────────────────────────────────┐
│                                                          │
│                                                          │
│              ┌─────────────┐                             │
│              │             │     APP 名称（品牌名）       │
│              │   二维码     │                             │
│              │  250x250    │     请使用手机 APP           │
│              │             │     扫描二维码连接           │
│              └─────────────┘                             │
│                                                          │
│              IP: 192.168.1.100:8866                      │
│                                                          │
│              数据已过期，请重新扫码同步（过期时显示）      │
│                                                          │
└──────────────────────────────────────────────────────────┘
```

- 背景：深色渐变（与手机端一致）
- 二维码内容：`http://{TV局域网IP}:8866`
- 配对成功后自动跳转主页
- 底部显示 TV 的 IP 地址（方便手动输入）

### 3.3 主页

**布局（横屏 1920x1080）：**

```
┌──────────────────────────────────────────────────────────┐
│  APP名称                              同步 ⟳  设置 ⚙   │
│                                                          │
│  ┌────────────────────────┐  ┌────────────────────────┐  │
│  │                        │  │  当前节点               │  │
│  │     ╭──────────╮       │  │  🇭🇰 香港 IPLC-01      │  │
│  │     │          │       │  │  ▸ 点击切换节点         │  │
│  │     │  连接按钮 │       │  ├────────────────────────┤  │
│  │     │  (大圆)  │       │  │  代理模式               │  │
│  │     │          │       │  │  ● 规则模式  ○ 全局模式 │  │
│  │     ╰──────────╯       │  ├────────────────────────┤  │
│  │                        │  │  订阅信息               │  │
│  │     00:12:34           │  │  套餐：年付套餐          │  │
│  │     已连接              │  │  到期：2025-03-15       │  │
│  │                        │  │  流量：12.3/100 GB      │  │
│  └────────────────────────┘  ├────────────────────────┤  │
│                              │  ↑ 1.2 MB/s  ↓ 5.6 MB/s│  │
│  ┌────────────┐ ┌──────────┐│  今日：↑ 120MB ↓ 890MB  │  │
│  │ 今日上传    │ │ 今日下载  ││                        │  │
│  │ 120 MB     │ │ 890 MB   ││                        │  │
│  └────────────┘ └──────────┘└────────────────────────┘  │
│                                                          │
│  上次同步：2025-02-24 03:00    7天后过期                  │
└──────────────────────────────────────────────────────────┘
```

- 左侧：连接按钮（大圆环，与手机端风格一致）+ 连接时长
- 右侧：节点信息、代理模式、订阅信息、流量统计
- 遥控器焦点顺序：连接按钮 → 节点切换 → 代理模式 → 同步按钮 → 设置
- 连接按钮：遥控器 OK 键触发连接/断开

### 3.4 节点列表页

**布局：**

```
┌──────────────────────────────────────────────────────────┐
│  ← 返回          选择节点                                │
│                                                          │
│  🇭🇰 香港                                                │
│  ├─ [●] 香港 IPLC-01          0.5x   在线               │
│  ├─ [ ] 香港 IPLC-02          1.0x   在线               │
│  └─ [ ] 香港 BGP-01           0.8x   在线               │
│                                                          │
│  🇯🇵 日本                                                │
│  ├─ [ ] 东京 IPLC-01          0.5x   在线               │
│  └─ [ ] 大阪 BGP-01           1.0x   在线               │
│                                                          │
│  🇺🇸 美国                                                │
│  ├─ [ ] 洛杉矶 IPLC-01       0.5x   在线               │
│  └─ [ ] 圣何塞 BGP-01         1.0x   离线               │
│                                                          │
└──────────────────────────────────────────────────────────┘
```

- 按国家/地区分组（与手机端一致）
- 遥控器上下键导航，OK 键选择
- 选择后自动返回主页
- 如果 VPN 已连接，切换节点后自动重连

---

## 4. UI 设计规范

### 4.1 色彩体系（与手机端完全一致）

**深色主题（TV 默认且唯一主题）：**

| 语义名 | 色值 | 用途 |
|--------|------|------|
| BackgroundPrimary | `#0B0E1A` | 页面背景 |
| BackgroundSecondary | `#111631` | 卡片/面板背景 |
| GradientStart | `#6C3CE1` | 渐变起始色（紫色） |
| GradientEnd | `#00C6FF` | 渐变结束色（蓝色） |
| Accent | `#7B61FF` | 强调色/选中态 |
| Success | `#00E676` | 已连接状态 |
| Error | `#FF5252` | 错误/断开状态 |
| Warning | `#FFB74D` | 警告（即将过期等） |
| TextPrimary | `#FFFFFF` | 主文字 |
| TextSecondary | `#A0A3BD` | 次要文字 |
| TextDisabled | `#5A5D7A` | 禁用文字 |
| CardBorder | `#267B61FF` | 卡片边框（26%透明度） |
| Divider | `#337B61FF` | 分割线 |

### 4.2 TV 适配要点

- 字体大小比手机端放大 1.5~2 倍（TV 观看距离远）
- 标题：32~36sp，正文：20~24sp，辅助文字：16~18sp
- 焦点态：选中项加 Accent 色边框 + 轻微放大（1.05x）
- 圆角：16dp（卡片），24dp（大按钮）
- 间距：比手机端宽松，padding 至少 24dp
- 安全区域：距屏幕边缘至少 48dp（TV overscan）

### 4.3 焦点管理

TV 端没有触摸，全靠遥控器 D-pad 导航：
- 每个页面必须有明确的默认焦点
- 焦点移动路径要符合直觉（左右上下）
- 选中态要有明显的视觉反馈
- 连接按钮是主页的默认焦点

---

## 5. 通信协议

### 5.1 TV 端 HTTP Server

TV 端启动时在后台运行一个 HTTP Server（NanoHTTPD），监听端口 `8866`。

### 5.2 API 接口

#### POST /api/pair — 首次配对

手机扫码后调用，推送全量数据。

**Request Body:**
```json
{
  "userUuid": "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
  "servers": [
    {
      "id": 1,
      "name": "香港 IPLC-01",
      "host": "hk1.example.com",
      "port": 443,
      "serverPort": 443,
      "type": "vmess",
      "network": "ws",
      "tls": 1,
      "networkSettings": "{\"path\":\"/ws\"}",
      "tlsSettings": "{\"serverName\":\"hk1.example.com\"}",
      "rate": "0.5",
      "countryCode": "HK",
      "isOnline": 1
    }
  ],
  "selectedServerId": 1,
  "proxyMode": "rule",
  "brand": {
    "appName": "绮梦云加速",
    "subtitle": "稳定运营四年 主打一个稳定"
  },
  "subscription": {
    "planName": "年付套餐",
    "expiredAt": 1742025600,
    "transferEnable": 107374182400,
    "uploadBytes": 1234567890,
    "downloadBytes": 9876543210,
    "resetDay": 1
  }
}
```

**Response:**
```json
{
  "success": true,
  "message": "配对成功",
  "deviceName": "TV-Living-Room"
}
```

#### POST /api/sync — 同步数据

手机端主动推送最新数据（节点列表可能变化、订阅信息更新）。

**Request Body:** 与 `/api/pair` 相同格式。

**Response:**
```json
{
  "success": true,
  "vpnState": "CONNECTED",
  "connectedServerId": 1
}
```

#### POST /api/command — 发送指令

手机端遥控 TV 的 VPN 操作。

**Request Body:**
```json
{
  "action": "connect",       // connect | disconnect | switch_node | switch_mode
  "serverId": 3,             // switch_node 时必填
  "proxyMode": "global"      // switch_mode 时必填
}
```

**Response:**
```json
{
  "success": true,
  "vpnState": "CONNECTING"
}
```

#### GET /api/status — 查询状态

手机端查询 TV 当前状态。

**Response:**
```json
{
  "vpnState": "CONNECTED",
  "connectedServerId": 1,
  "connectedServerName": "香港 IPLC-01",
  "connectedSeconds": 3600,
  "proxyMode": "rule",
  "uploadSpeed": 1234567,
  "downloadSpeed": 5678901,
  "totalUpload": 123456789,
  "totalDownload": 987654321,
  "syncedAt": 1708732800,
  "expiredAt": 1709337600
}
```

### 5.3 二维码内容

```
http://192.168.1.100:8866
```

手机端扫码后解析出 TV 地址，后续所有通信都通过这个地址。

---

## 6. 数据持久化

### 6.1 DataStore 存储

| Key | 类型 | 说明 |
|-----|------|------|
| `user_uuid` | String | 用户 UUID |
| `servers_json` | String | 节点列表 JSON |
| `selected_server_id` | Int | 当前选中节点 ID |
| `proxy_mode` | String | 代理模式 (rule/global) |
| `brand_app_name` | String | 品牌名称 |
| `brand_subtitle` | String | 品牌副标题 |
| `subscription_json` | String | 订阅信息 JSON |
| `synced_at` | Long | 上次同步时间戳（毫秒） |
| `paired_phone_id` | String | 配对手机标识（可选） |

### 6.2 过期策略

- 数据有效期：7 天（从 `synced_at` 算起）
- 过期后：显示二维码页 + 提示重新扫码
- 手动同步：主页右上角"同步"按钮，提示用户打开手机 APP 推送
- VPN 连接不受过期影响（已连接的不会断开，但不能新建连接）

---

## 7. 从手机端复用的代码

### 7.1 直接复制（需调整包名）

| 文件 | 来源 | 说明 |
|------|------|------|
| `V2RayConfig.kt` | `com.v2rayng.myvpn.vpn` | V2Ray 配置生成器 |
| `MyVpnService.kt` | `com.v2rayng.myvpn.vpn` | VPN 服务 |
| `VpnConnectionManager.kt` | `com.v2rayng.myvpn.vpn` | 连接管理 |
| `Tun2Socks.kt` | `com.v2rayng.myvpn.vpn` | TUN 实现 |
| `ServerDto.kt` | `com.v2rayng.myvpn.data.remote.dto` | 节点数据类 |

### 7.2 libv2ray AAR

从手机端项目复制 `libv2ray.aar` 到 TV 端的 `app/libs/` 目录。

### 7.3 不需要的模块

- Retrofit / OkHttp 网络层（TV 端不调 API）
- Hilt 依赖注入（TV 端结构简单，可选用）
- 登录/注册/订单/支付相关 UI
- v2board API 相关 Repository

---

## 8. 手机端改造

### 8.1 新增功能入口

在手机端 APP 的设置页或首页添加"连接 TV"入口：

```
设置页
├─ ...
├─ 连接 TV 盒子    →  扫码页
├─ ...
```

### 8.2 扫码页流程

1. 打开相机扫描 TV 上的二维码
2. 解析出 TV 地址（如 `http://192.168.1.100:8866`）
3. 调用 `POST /api/pair` 推送全量数据
4. 成功后进入"TV 遥控"面板

### 8.3 TV 遥控面板

```
┌─────────────────────────┐
│  TV 盒子控制             │
│                         │
│  状态：已连接 🟢         │
│  节点：香港 IPLC-01      │
│  时长：01:23:45          │
│                         │
│  [选择节点 ▼]            │
│  ● 规则模式  ○ 全局模式  │
│                         │
│  [ 连接 / 断开 ]         │
│  [ 同步数据 ]            │
│                         │
│  上次同步：刚刚           │
└─────────────────────────┘
```

### 8.4 需要推送的数据来源

| 数据 | 来源 |
|------|------|
| userUuid | `UserPreferences.userUuid` |
| servers | `ServerRepository.getServerList()` |
| selectedServerId | `UserPreferences.selectedNodeId` |
| proxyMode | `UserPreferences.proxyMode` |
| brand | `BuildConfig` 或 `ClientConfigRepository` |
| subscription | `UserRepository.getSubscribe()` |

---

## 9. 项目结构（TV 端）

```
app/src/main/java/com/v2rayng/mytv/
├── MainActivity.kt                    # 入口 Activity
├── TvApplication.kt                   # Application 类
│
├── ui/
│   ├── pair/
│   │   └── PairFragment.kt           # 二维码配对页
│   ├── home/
│   │   ├── HomeFragment.kt           # 主页
│   │   └── HomeViewModel.kt          # 主页 ViewModel
│   ├── nodes/
│   │   └── NodeListFragment.kt       # 节点列表页
│   └── theme/
│       └── Color.kt                  # 主题色（复用手机端深色主题）
│
├── server/
│   ├── TvHttpServer.kt               # NanoHTTPD HTTP Server
│   └── TvApiHandler.kt               # API 请求处理
│
├── vpn/
│   ├── V2RayConfig.kt                # 配置生成器（复用）
│   ├── MyVpnService.kt               # VPN 服务（复用）
│   ├── VpnConnectionManager.kt       # 连接管理（复用）
│   └── Tun2Socks.kt                  # TUN 实现（复用）
│
├── data/
│   ├── TvDataStore.kt                # DataStore 持久化
│   ├── ServerDto.kt                  # 节点数据类（复用）
│   └── SyncData.kt                   # 同步数据模型
│
└── util/
    ├── NetworkUtil.kt                # 获取局域网 IP
    └── QrCodeUtil.kt                 # 二维码生成（ZXing）
```

---

## 10. 依赖清单

```kotlin
dependencies {
    // AndroidX Leanback (TV UI)
    implementation("androidx.leanback:leanback:1.0.0")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // HTTP Server (TV 端)
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // QR Code 生成
    implementation("com.google.zxing:core:3.5.2")

    // JSON 解析
    implementation("com.google.code.gson:gson:2.10.1")

    // libv2ray (xray-core)
    implementation(files("libs/libv2ray.aar"))

    // Glide (图片加载，可选)
    implementation("com.github.bumptech.glide:glide:4.16.0")
}
```

---

## 11. 开发阶段规划

### 阶段一：基础框架（✅ 已完成）
- [x] 清理 Leanback 模板代码
- [x] 搭建项目结构（包、目录）
- [x] 集成 libv2ray AAR
- [x] 复制 VPN 核心代码（V2RayConfig、TvVpnService、VpnConnectionManager、Tun2Socks）
- [x] 实现 DataStore 持久化层（TvDataStore.kt）
- [x] 实现主题色系统（Color.kt + 深色 XML drawable）

### 阶段二：HTTP Server + 配对（✅ 已完成）
- [x] 集成 NanoHTTPD
- [x] 实现 `/api/pair`、`/api/sync`、`/api/command`、`/api/status` 接口
- [x] 实现局域网 IP 获取（NetworkUtil.kt）
- [x] 实现二维码生成（ZXing，QrCodeUtil.kt）
- [x] 实现二维码配对页 UI（PairingFragment.kt）

### 阶段三：主页 + VPN 控制（✅ 已完成）
- [x] 实现主页 UI（连接按钮、节点信息、流量统计）
- [x] 实现 VPN 连接/断开逻辑（TvVpnService.kt + VpnConnectionManager.kt）
- [x] 实现连接计时器
- [x] 实现流量统计显示（queryStats uplink/downlink）
- [x] 实现代理模式切换（规则/全局，toggleProxyMode()）

### 阶段四：节点列表 + 遥控器操作（✅ 已完成）
- [x] 实现节点列表页（ServerListFragment + ServerAdapter）
- [x] 实现遥控器焦点导航（descendantFocusability + nextFocus 完整链）
- [x] 实现节点切换 + 自动重连
- [x] 焦点态视觉反馈（bg_server_item.xml / bg_panel_card.xml selector）

### 阶段五：手机端改造（预计 2 天）
- [ ] 手机端添加"连接 TV"入口
- [ ] 实现扫码功能
- [ ] 实现数据推送（pair/sync/command）
- [ ] 实现 TV 遥控面板 UI

### 阶段六：联调测试（🔄 进行中）
- [x] TV + 手机端联调（已完成配对 + 数据同步）
- [ ] 各种网络环境测试
- [x] 遥控器操作测试（D-pad 焦点导航已修复）
- [x] 数据过期/同步测试（7 天过期 + 手动同步）
- [ ] VPN 连接稳定性测试（正在修复路由回环问题）

**总计预估：11-14 天**

---

## 已知问题与修复记录（2026-03-01）

| 问题 | 根因 | 修复方案 | 状态 |
|------|------|---------|------|
| VPN 连接闪退 | `startLoop(config)` 只传一个参数，实际 API 需 `(String, Int)` | 改为 `startLoop(config, 0)` | ✅ |
| libv2ray 初始化崩溃 | AAR assets 中的 `geoip.dat`/`geosite.dat` 未复制到 filesDir | onCreate 时从 assets 复制 dat 文件 | ✅ |
| VPN 连接后立即断线 | `startLoop` 非阻塞立即返回 → 线程结束 → `stopSelf()` → DISCONNECTED | 加 `CountDownLatch` 阻塞等待 `shutdown()` 回调 | ✅ |
| 连接后无网络（路由回环）| 全量路由 `0.0.0.0/0` + xray-core 在同进程 → 出站连接也进 TUN | `addDisallowedApplication` 排除 app 进程；Tun2Socks(TUN fd→SOCKS5) + xray-core SOCKS5 模式 | 🔄 测试中 |
| 遥控器焦点卡在连接按钮 | `descendantFocusability="afterDescendants"` 导致容器抢焦点；nextFocus 链不完整 | 改为 `beforeDescendants` + 补完整 nextFocus 链 | ✅ |
| 无法切换规则/全局模式 | 同焦点问题，panelMode 面板无法被遥控器到达 | 焦点修复后 D-pad 右键可到达 panelMode | ✅ |

### VPN 架构说明（当前实现）

```
其他TV应用流量 → TUN(10.0.0.2/24) → Tun2Socks(读 TUN fd)
                                           → SOCKS5(127.0.0.1:10808) → xray-core → 代理服务器
                                                      ↑ loopback，不进 TUN

xray-core 进程 → 代理服务器 → 物理网卡
（整个 app 进程通过 addDisallowedApplication 排除在 VPN 路由之外）
```

---

## 12. VPN 兼容性：双模式方案

### 12.1 问题背景

部分 TV 盒子（尤其是运营商定制机顶盒）会阉割 VPN 组件：
- ROM 移除了 `com.android.vpndialogs`，`VpnService.prepare()` 无法弹出授权框
- 内核未编译 `CONFIG_TUN`，`/dev/tun` 不存在
- 系统策略禁止第三方 VPN 应用

### 12.2 检测逻辑

APP 启动时自动检测设备 VPN 能力：

```kotlin
enum class ProxyEngine {
    VPN,        // 全局 VPN 模式（TUN）
    SYSTEM_PROXY // 本地代理模式（HTTP/SOCKS5 + 系统代理）
}

fun detectProxyEngine(context: Context): ProxyEngine {
    // 1. 检查 /dev/tun 是否存在
    if (!File("/dev/tun").exists()) {
        return ProxyEngine.SYSTEM_PROXY
    }

    // 2. 检查 VpnService 是否可用
    try {
        val intent = VpnService.prepare(context)
        // 不抛异常说明 VpnService 可用
        return ProxyEngine.VPN
    } catch (e: Exception) {
        return ProxyEngine.SYSTEM_PROXY
    }
}
```

### 12.3 双模式架构

```
APP 启动 → detectProxyEngine()
  │
  ├─ VPN 模式（大部分设备）
  │   xray-core 启动 → TUN 接口 → 全局流量劫持
  │   用户无感，所有应用自动走代理
  │
  └─ 代理模式（阉割设备 fallback）
      xray-core 启动 → 监听本地端口
      ├─ SOCKS5: 127.0.0.1:10808
      ├─ HTTP:   127.0.0.1:10809
      │
      ├─ 自动设置系统 WiFi 代理（优先）
      │   Settings.Global.putString(resolver, "http_proxy", "127.0.0.1:10809")
      │
      └─ 设置失败 → 提示用户手动配置
          "请前往 设置 → WiFi → 代理 → 手动
           服务器: 127.0.0.1  端口: 10809"
```

### 12.4 代理模式实现细节

**xray-core 配置差异：**

| 配置项 | VPN 模式 | 代理模式 |
|--------|---------|---------|
| inbound | socks(10808) + http(10809) | 相同 |
| TUN 接口 | 需要（tun2socks） | 不需要 |
| VpnService | 需要 | 不需要 |
| 系统代理 | 不需要（TUN 劫持） | 需要设置 |

代理模式下 xray-core 的配置和 VPN 模式完全一样（都有 SOCKS5 + HTTP inbound），区别只是不启动 TUN/VpnService。

**自动设置系统 WiFi 代理：**

```kotlin
object SystemProxyManager {

    fun setProxy(context: Context, host: String = "127.0.0.1", port: Int = 10809): Boolean {
        return try {
            // 方式1: Settings.Global（需要 WRITE_SECURE_SETTINGS 或系统签名）
            Settings.Global.putString(
                context.contentResolver,
                Settings.Global.HTTP_PROXY,
                "$host:$port"
            )
            true
        } catch (e: SecurityException) {
            // 方式2: 通过 ADB 命令（需要 root 或 ADB 授权）
            try {
                Runtime.getRuntime().exec("settings put global http_proxy $host:$port")
                true
            } catch (e2: Exception) {
                false
            }
        }
    }

    fun clearProxy(context: Context) {
        try {
            Settings.Global.putString(
                context.contentResolver,
                Settings.Global.HTTP_PROXY,
                ":0"
            )
        } catch (e: Exception) {
            try {
                Runtime.getRuntime().exec("settings put global http_proxy :0")
            } catch (_: Exception) {}
        }
    }
}
```

**需要的权限：**

```xml
<!-- AndroidManifest.xml -->
<uses-permission android:name="android.permission.WRITE_SETTINGS" />
<uses-permission android:name="android.permission.WRITE_SECURE_SETTINGS"
    tools:ignore="ProtectedPermissions" />
```

> `WRITE_SECURE_SETTINGS` 是系统级权限，普通 APP 无法获取。但很多 TV 盒子可以通过 ADB 授权：
> `adb shell pm grant com.v2rayng.mytv android.permission.WRITE_SECURE_SETTINGS`

### 12.5 代理模式的局限性

| 场景 | VPN 模式 | 代理模式 |
|------|---------|---------|
| 浏览器 | ✅ 全局 | ✅ 走系统代理 |
| YouTube/Netflix | ✅ 全局 | ⚠️ 部分支持（取决于应用是否走系统代理） |
| 游戏 | ✅ 全局 | ❌ 通常不走系统代理 |
| 系统更新 | ✅ 全局 | ⚠️ 部分支持 |
| DNS 防泄漏 | ✅ 完全 | ❌ 无法劫持 DNS |

### 12.6 UI 适配

主页需要根据当前引擎模式显示不同状态：

```
VPN 模式：
  连接按钮下方显示 "VPN 模式 · 全局代理"

代理模式：
  连接按钮下方显示 "代理模式 · 部分应用生效"
  + 黄色提示条："当前设备不支持 VPN，已切换为代理模式。
                 部分应用可能无法通过代理访问。"
```

设置页增加：
- 代理端口配置（默认 10808/10809）
- 手动切换模式（如果用户想强制使用某种模式）
- 代理模式下的手动配置指引

### 12.7 连接管理器抽象

```kotlin
interface ProxyEngineController {
    fun start(config: String): Boolean
    fun stop()
    fun isRunning(): Boolean
    fun getTrafficStats(): TrafficStats
}

class VpnEngineController(context: Context) : ProxyEngineController {
    // 通过 VpnService + tun2socks 实现
}

class LocalProxyEngineController(context: Context) : ProxyEngineController {
    // 直接启动 xray-core 进程，不需要 VpnService
    // 连接时设置系统代理，断开时清除
}
```

`VpnConnectionManager` 根据 `detectProxyEngine()` 的结果选择对应的 Controller。

---

## 13. 注意事项

### 13.1 TV 特殊限制
- TV 没有触摸屏，所有交互必须支持 D-pad（遥控器方向键 + OK 键）
- TV 没有摄像头，不能扫码（所以是 TV 显示二维码，手机来扫）
- TV 可能没有 WiFi 状态栏，需要在 APP 内显示网络状态
- 部分 TV 盒子性能较弱，避免复杂动画

### 13.2 VPN 权限
- TV 端同样需要 VPN 权限弹窗，用户需要用遥控器确认
- `VpnService.prepare()` 在 TV 上的行为与手机一致

### 13.3 后台保活
- TV 端 VPN 服务需要前台通知（Foreground Service）
- TV 系统可能更积极地杀后台进程，需要测试保活策略

### 13.4 安全性
- HTTP Server 仅监听局域网，不暴露到公网
- 可以考虑加一个简单的 token 验证（配对时生成，后续请求携带）
- 节点信息存储在本地 DataStore，不上传任何数据