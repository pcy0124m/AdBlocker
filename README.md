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
| 垃圾短信 | `SmsReceiver` 接收短信广播，命中黑名单 / 关键词拦截 | **彻底拦截**需设为默认短信 App（须凑齐 4 个资格组件）；否则只能尽力拦截 |
| 开机自启 | `BootReceiver` 监听 `BOOT_COMPLETED`，自动拉起 VPN（需先手动授权过 VPN） | 无需额外权限（`RECEIVE_BOOT_COMPLETED` 为普通权限） |
| 拦截统计 | 显示累计拦截 DNS 查询次数与规则库域名数 | — |
| 拦截记录 | 记录每一条被拦下的**来电号码 / 短信正文 + 命中原因 + 时间**，可按电话 / 短信筛选，支持一键清空 | — |
| 暂停拦截 | 一键让 VPN 只转发不过滤，用于排查某个 App 联网异常 | — |
| 域名白名单 | 被误拦截的域名（如视频 / 短剧 CDN）加入后立即放行，支持 `*.example.com` | — |

广告域名规则支持 **在线更新**（内置 AdAway / StevenBlack 订阅源），VPN 运行中实时生效。

> **上层 DNS 说明**：VPN 使用 `223.5.5.5`（阿里）、`119.29.29.29`（腾讯）、`114.114.114.114` 作为上游并按序回退，
> 另每次转发都带 2.5s 超时 —— 避免单个上游不可达时拖垮全部域名解析。

---

## 工作原理（简图）

```
App 启动
 ├─ 开启 VPN（AdBlockVpnService）
│     └─ 仅把发往虚拟 DNS 10.0.0.1 的流量引入隧道
│           ├─ DNS 查询命中广告域名 → 返回 NXDOMAIN（广告被掐）
│           └─ 否则转发到上游 223.5.5.5 → 正常解析
│                 └─ 解析结果内存缓存 60s：重复查询直接命中，不再走网络（省电 / 防卡顿）
│
├─ 申请「来电筛查」角色（CallBlockerService）
│     └─ 来电命中黑名单 → 拒接 + 写入拦截记录
│
├─ 短信接收（SmsReceiver）
│     └─ 短信命中黑名单 / 关键词 → 拦截 + 写入拦截记录
│
└─ 开机广播（BootReceiver）
      └─ 开关为开 且 VPN 已授权 → 自动启动 VPN
```

黑名单（电话 / 短信共用）存于 Room 数据库 `BlockListDatabase`，由 `BlockListManager` 单例做内存缓存。
拦截记录（`blocked_events` 表）由 `util/BlockLog.kt` 读写：为兼容 `BroadcastReceiver` /
`CallScreeningService` 这类没有协程作用域、进程随时可能被回收的调用方，这里刻意用**同步 DAO**
写入（单行插入毫秒级，且数据库已开启 `allowMainThreadQueries`），并在写入前合并「同号码 1 分钟
内的重复拦截」，最多保留 300 条。
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
5. 点 **「开启短信拦截」** → 弹出说明框，选择你想要的强度：
   - **设为默认短信 App**（可彻底拦截）：命中短信不落库、不显示；放行的短信会写回系统短信库并弹通知，**你原来的短信 App 仍能查看和发送短信**（彩信除外）。
     > 「默认短信 App」是 Android 的系统硬性要求 —— 只有它才有权阻止短信落库。因此本应用声明了系统要求的 **4 个组件**（`SMS_DELIVER` / `WAP_PUSH_DELIVER` / `ACTION_SENDTO` / `RESPOND_VIA_MESSAGE`），**少任何一个都不会出现在「设置 → 默认应用 → 短信」列表里**，申请也会失败。
   - **仅尽力拦截**：不改系统默认短信 App，只申请 `RECEIVE_SMS`。能阻止其它第三方 App 收到命中短信，但**无法阻止系统落库**。
   - 若系统未弹出设置框（部分国产 ROM 只认手动设置），会自动跳到「设置 → 默认应用」，手动选中 AdBlocker 即可。
6. 打开 **「开机自动启动广告拦截」** 开关 → 之后每次开机/重启会自动拉起 VPN。
   - 前提是 VPN 已授权过（首次需手动开启一次）；开机广播里无法弹 VPN 授权框，未授权时会静默跳过。
7. **遇到某个 App 联网异常（如短剧 / 视频打不开）**，按顺序试：
   - 打开 **「暂停拦截」** 开关 → 立即恢复（VPN 仍在跑，但不过滤任何域名）。若这样就正常，说明是被规则误拦截；
   - 再关掉暂停，把该 App 用到的域名加入 **「域名白名单」**（支持 `*.example.com`），即可精准放行；
   - 如果加白名单后仍不行，多半是该 App 走了加密 DNS（DoH/DoT），见下方「已知限制」。

---

## 已知限制（重要）

- **加密 DNS 拦不到**：DoH / DoT（走 443）无法用 DNS 过滤拦截。要全量过滤，请在系统「Private DNS」里指向过滤型服务器（如 `dns.adguard.com`）。
- **短信「彻底拦截」必须设为默认短信 App**（Android 系统限制：只有默认短信 App 能阻止短信落库）：
  - 成为默认短信 App 的代价：**彩信（MMS）不再接收** —— 本应用声明了 `MmsReceiver` 以满足资格要求，但不解析彩信（国内彩信基本已停用）；放行的普通短信由本应用写回短信库并负责弹通知。
  - 未设默认时只能尽力拦截，部分机型因 `WRITE_SMS` 被拒而失效（属预期）。
  - 若「默认应用 → 短信」列表里看不到本应用，先确认已安装**本次版本**（2026-09-11 之后补充了 4 个资格组件），再重启手机重试。
- **VPN 前台服务类型**：targetSdk 34 下 `AdBlockVpnService` 已声明 `android:foregroundServiceType="specialUse"`（含对应 `<property>`），否则在 Android 14 上 `startForeground` 会崩溃。如需上架 Google Play，需在该类型下补充 `specialUse` 的说明。
- **开机自启受系统省电策略影响**：部分国产 ROM 需在「自启动管理」里额外放行本 App，否则开机广播可能不触发。
- **VPN 常驻略耗电**：后台保持隧道会带来少量电量开销。
- **DNS 应答缓存（默认开启）**：热门域名 60s 内重复解析命中内存缓存、不再走网络，可显著降低「开 VPN 刷视频卡」并省电；暂停 / 恢复拦截或更新规则时自动清空缓存。如需调整时长改 `util/DnsCache.kt` 的 `TTL_MS`。
- **上游 DNS 必须国内可达**：早期版本把上游写成 `8.8.8.8`，该地址在部分网络下不可达，会导致开启 VPN 后**全部域名解析失败**（表现为 App 打不开、视频加载不出来）。现已改为 `223.5.5.5` / `119.29.29.29` / `114.114.114.114` 多上游回退；`8.8.8.8` 仅作最后兜底。若你自建/替换 DNS，请注意这一点。
- **DNS 转发必须带超时**：每次转发使用独立 socket 且设置 `soTimeout`。若沿用无限阻塞的 `receive()`，上游丢包时该线程会永久卡死，并发数耗尽后**全网 DNS 瘫痪**。
- **编译状态**：已通过 GitHub Actions（debug + 签名 release 两条流水线）实际编译验证；真机行为仍建议自行走查一遍。

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
│   │   ├─ SmsReceiver.kt         # 短信拦截（默认/非默认折中）+ 放行短信通知
│   │   ├─ ComposeSmsActivity.kt  # 发送短信入口（默认短信 App 资格组件）
│   │   ├─ RespondViaMessageService.kt # 来电「用消息回复」（资格组件）
│   │   ├─ MmsReceiver.kt         # 彩信到达广播（资格组件，不做彩信解析）
│   │   ├─ BootReceiver.kt        # 开机自启 VPN
│   │   ├─ MainActivity.kt        # UI 与权限申请
│   │   ├─ BlockLogAdapter.kt     # 拦截记录列表适配器
│   │   ├─ data/BlockListDatabase.kt  # 黑名单表 + 拦截记录表（blocked_events）
│   │   ├─ BlockListManager.kt    # 黑名单单例缓存
│   │   └─ util/
│   │       ├─ DnsUtils.kt        # DNS 包解析 / NXDOMAIN / SERVFAIL 构造
│   │       ├─ HostsLoader.kt     # 内置 hosts 解析
│   │       ├─ HostsUpdater.kt    # 在线规则订阅源 + 白名单 + 拦截统计
│   │       ├─ BlockLog.kt        # 电话/短信拦截记录读写（含重复合并）
│   │       ├─ SmsNotifier.kt     # 放行短信通知（默认短信 App 场景必需）
│   │       └─ Prefs.kt           # 本地偏好（自启 / 暂停 / 白名单 / 累计拦截数）
│   └─ res/...
├─ gradlew / gradlew.bat / gradle/wrapper/  # 完整 Gradle Wrapper
└─ README.md
```

---

## License

自用 / 学习用途。拦截规则版权归各订阅源作者所有。
