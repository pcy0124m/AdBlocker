# AdBlocker

安卓端「广告 + 骚扰电话 + 垃圾短信」拦截工具。**无需 Root**，基于 `VpnService` 本地 DNS 过滤 + `CallScreeningService` + `SmsReceiver` 实现。

> 包名：`com.example.adblocker`
> 最低支持：Android 6.0（API 23） · 目标 / 编译：API 34
> 技术栈：Kotlin 1.9.22 · Gradle 8.6 · AGP 8.2.2 · Room 2.6.1 · 协程

---

## 功能一览

| 拦截类型 | 方案 | 是否需要特殊权限 |
|---|---|---|
| 广告（应用内 / 网页） | `VpnService` 建立本地 VPN 隧道，对匹配广告域名的上游 DNS 查询返回 `NXDOMAIN` | 首次开启需授予 VPN 权限 |
| 骚扰电话 | `CallScreeningService`，命中黑名单直接拒接 | 需在系统中将该 App 设为「来电筛查应用」 |
| 垃圾短信 | `SmsReceiver` 接收短信广播，命中黑名单 / 关键词拦截 | 设为默认短信 App 可真正拦截；否则尽力拦截 |
| 开机自启 | `BootReceiver` 监听 `BOOT_COMPLETED`，自动拉起 VPN（需先手动授权过 VPN） | 无需额外权限（`RECEIVE_BOOT_COMPLETED` 为普通权限） |

广告域名规则支持 **在线更新**（内置 AdAway / StevenBlack 订阅源），VPN 运行中实时生效。

---

## 工作原理（简图）

```
App 启动
 ├─ 开启 VPN（AdBlockVpnService）
 │     └─ 仅把发往虚拟 DNS 10.0.0.1 的流量引入隧道
 │           ├─ DNS 查询命中广告域名 → 返回 NXDOMAIN（广告被掐）
 │           └─ 否则转发到上游 8.8.8.8 → 正常解析
 │
 ├─ 申请「来电筛查」角色（CallBlockerService）
 │     └─ 来电命中黑名单 → 拒接
 │
 ├─ 短信接收（SmsReceiver）
 │     └─ 短信命中黑名单 / 关键词 → 拦截
 │
 └─ 开机广播（BootReceiver）
       └─ 开关为开 且 VPN 已授权 → 自动启动 VPN
```

黑名单（电话 / 短信共用）存于 Room 数据库 `BlockListDatabase`，由 `BlockListManager` 单例做内存缓存。
「开机自启」开关存于 `SharedPreferences`（`util/Prefs.kt`）。

---

## 本地构建

### 方式一：Gradle Wrapper（推荐）

仓库已包含完整 Wrapper（`gradlew` / `gradlew.bat` + `gradle-wrapper.jar 8.6`）：

```bash
# 调试版（免签名，可直接装真机测试）
./gradlew assembleDebug

# 发布版（需本地 keystore，参见下方签名配置）
./gradlew assembleRelease
```

> 产物路径：`app/build/outputs/apk/debug/`、`app/build/outputs/apk/release/`

### 方式二：Android Studio

直接 `Open` 本目录 → 等待 Gradle 同步 → 连接真机点 `Run`（debug）或 `Build → Generate Signed Bundle / APK`（release）。

---

## 云编译（GitHub Actions）

仓库内置两条工作流，推送后自动生效。

### 1. 调试版 APK（自动）

- 工作流：`.github/workflows/build.yml`
- 触发：代码 push 到 `main`
- 动作：自动安装 JDK17 + Android SDK + Gradle 8.6，构建 **未签名 debug APK**
- 取产物：仓库 **Actions → 对应 run → Artifacts** 下载 `adblocker-debug-apk`

### 2. 签名发布版 APK / AAB（手动或打 tag）

- 工作流：`.github/workflows/release.yml`
- 触发：**手动** Run workflow，或推送 `v*` 标签（如 `v1.0`）
- 动作：解码 keystore → `assembleRelease` + `bundleRelease` → 上传 **签名 APK + AAB**

#### Release 签名配置（4 个 Secret）

在仓库 **Settings → Secrets and variables → Actions → New repository secret** 依次添加：

| Secret | 内容 |
|---|---|
| `KEYSTORE_BASE64` | keystore 文件经 base64 编码后的整串 |
| `KEYSTORE_PASSWORD` | keystore 密码 |
| `KEY_ALIAS` | 密钥别名（如 `adblocker`） |
| `KEY_PASSWORD` | 密钥密码 |

> **本仓库这 4 个 Secret 已配置完成**，可直接手动触发 release 构建。
> 实际密钥文件与密码**不存放在仓库里**，请离线妥善备份（丢了就无法再对已发布的应用推更新）。

**若需自行生成一套新的 keystore（一次性，本地执行）：**

```bash
keytool -genkeypair -v -keystore release.keystore -alias adblocker \
  -keyalg RSA -keysize 2048 -validity 10000

# 转 base64
# macOS / Linux：
base64 -w0 release.keystore
# Windows：
certutil -encode release.keystore release.b64 && type release.b64
```

将 base64 的文本粘进 `KEYSTORE_BASE64` 即可。

> 本地 `app/build.gradle.kts` 的 `signingConfigs.release` 仅在检测到 `KEYSTORE_PATH` 时生效——**没有 keystore 时本地的 release 构建不会报错**，只是不签名。

---

## 设备上使用

1. 安装 APK（debug 从 Actions 下载，release 从签名产物下载）。
2. 打开 App，点 **「开启广告拦截」** → 授予 VPN 权限。
3. 点 **「在线更新广告规则」**，等待 Toast 提示已加载的域名条数。
4. 点 **「开启电话拦截」** → 在系统弹窗中将该 App 设为「来电筛查应用」，并在号码框输入要拦截的号码后点「添加黑名单」。
5. 点 **「开启短信拦截」**：
   - 设为默认短信 App → 真正的拦截（命中短信不进收件箱）；
   - 未设默认 → 尽力拦截（高优先级有序广播下 `abortBroadcast`，并 best-effort 尝试从短信库删除）。
6. 打开 **「开机自动启动广告拦截」** 开关 → 之后每次开机/重启会自动拉起 VPN。
   - 前提是 VPN 已授权过（首次需手动开启一次）；开机广播里无法弹 VPN 授权框，未授权时会静默跳过。

---

## 已知限制（重要）

- **加密 DNS 拦不到**：DoH / DoT（走 443）无法用 DNS 过滤拦截。要全量过滤，请在系统「Private DNS」里指向过滤型服务器（如 `dns.adguard.com`）。
- **短信彻底拦截需设为默认短信 App**：未设默认时只能尽力拦截，部分机型因 `WRITE_SMS` 被拒而失效（属预期）。非默认路径下 App 会先申请 `RECEIVE_SMS` 运行时权限，`abortBroadcast()` 才会真正触发。
- **VPN 前台服务类型**：targetSdk 34 下 `AdBlockVpnService` 已声明 `android:foregroundServiceType="specialUse"`（含对应 `<property>`），否则在 Android 14 上 `startForeground` 会崩溃。如需上架 Google Play，需在该类型下补充 `specialUse` 的说明。
- **开机自启受系统省电策略影响**：部分国产 ROM 需在「自启动管理」里额外放行本 App，否则开机广播可能不触发。
- **VPN 常驻略耗电**：后台保持隧道会带来少量电量开销。
- **未做真机/编译验证**：本仓库代码由 AI 生成，请在 Android Studio 或 GitHub Actions 构建后真机走查一遍。

---

## 目录结构（关键文件）

```
AdBlocker/
├─ .github/workflows/
│   ├─ build.yml        # 调试版 APK 自动构建
│   └─ release.yml      # 签名发布版 APK/AAB
├─ app/src/main/
│   ├─ AndroidManifest.xml
│   ├─ assets/hosts.txt # 内置广告域名清单
│   ├─ java/com/example/adblocker/
│   │   ├─ AdBlockVpnService.kt   # VPN 本地 DNS 过滤核心
│   │   ├─ CallBlockerService.kt  # 来电筛查拒接
│   │   ├─ SmsReceiver.kt         # 短信拦截（默认/非默认折中）
│   │   ├─ BootReceiver.kt        # 开机自启 VPN
│   │   ├─ MainActivity.kt        # UI 与权限申请
│   │   ├─ data/BlockListDatabase.kt
│   │   ├─ BlockListManager.kt    # 黑名单单例缓存
│   │   └─ util/
│   │       ├─ DnsUtils.kt        # DNS 包解析 / NXDOMAIN 构造
│   │       ├─ HostsLoader.kt     # 内置 hosts 解析
│   │       ├─ HostsUpdater.kt    # 在线规则订阅源（AdAway/StevenBlack）
│   │       └─ Prefs.kt           # 本地偏好（开机自启开关）
│   └─ res/...
├─ gradlew / gradlew.bat / gradle/wrapper/  # 完整 Gradle Wrapper
└─ README.md
```

---

## License

自用 / 学习用途。拦截规则版权归各订阅源作者所有。
