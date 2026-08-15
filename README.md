# Anchor by Yokuli

<p align="center">
  <img src="docs/images/anchor-yokuli-logo.png" width="150" alt="Anchor by Yokuli pixel-art anchor logo">
</p>

**中文产品名：Yokuli锚警系统** · [中文说明](#中文) · [English](#english)

Android 锚警与 NMEA 0183 航行数据工具。它可以通过 TCP/UDP 接收船载 NMEA、显示实时原始数据和船舶轨迹、监测走锚与 GPS 丢失，并可在用户明确授权后把 NMEA 位置代理为 Android 全局 GPS。

Android anchor watch and NMEA 0183 navigation tool with live TCP/UDP input, raw-data diagnostics, boat track, drag/GPS-loss alarms, and an explicitly authorized NMEA-to-Android GPS proxy.

> **安全提示 / Safety:** 本应用只能作为辅助工具，不能替代正规的锚泊值守、航海判断或独立报警设备。GPS、供电、Wi-Fi、NMEA 数据源和 Android 系统都可能失效。 / This app is an auxiliary aid only. It does not replace proper watchkeeping, seamanship, or an independent alarm.

## 产品界面 / Product gallery

以下图片来自本项目实际 Debug APK 在 Android 14 模拟器上的运行画面。

| English watch | 中文语言与演示设置 |
|---|---|
| <img src="docs/images/anchor-home.png" width="320" alt="Anchor by Yokuli English watch map"> | <img src="docs/images/settings-zh.png" width="320" alt="Yokuli锚警系统中文语言和演示设置"> |

| 中文下锚设置 | 中文活动锚警 |
|---|---|
| <img src="docs/images/anchor-setup-zh.png" width="320" alt="Yokuli锚警系统下锚设置"> | <img src="docs/images/anchor-active-zh.png" width="320" alt="Yokuli锚警系统活动锚警地图"> |

---

## 中文

### 主要功能

- TCP 客户端与 UDP 监听器；连接前必须完成地址校验、端点测试并收到有效 NMEA 数据。
- 支持 RMC、GGA、GLL、VTG、ZDA、HDG、HDM、HDT、DPT、DBT、MWD、MWV，以及 GP/GN/GL/GA/BD 等 talker ID；TWD、TWA、TWS、AWA、AWS 分开保存，不会互相覆盖。
- 地图仅提供默认图与卫星图，可直接在地图页切换；隐藏 Google 地图工具栏、导航入口、室内与缩放按钮。
- 船形标记随真艏向或 COG 旋转，锚点单独显示；地图保留最近 24 小时的 breadcrumb 轨迹并由旧到新渐变，渲染时只抽稀、不删除数据库中的计算与历史点。
- 正常模式可选系统 GPS 或 NMEA GPS；只有服务器已连接并持续提供新鲜有效定位时才能选择 NMEA，成功连接后它会自动成为默认数据源。演示模式会单独锁定演示 GPS。
- 锚泊会话支持暂停、原会话继续、活动中调整范围和永久起锚；暂停不会清除中心、轨迹、范围或样本。
- 告警覆盖走锚、GPS 数据丢失、NMEA 连接丢失、定位质量和代理失败；“稍后提醒”会停止当前声音与振动，但危险持续时会再次提醒。
- 可查看最近 200 条原始 NMEA 语句、解析位置、校验错误和连接统计。
- 界面与关键后台安全通知通过 🇨🇳 / 🇬🇧 两个按钮即时切换。
- 像素风锚形应用图标；桌面名称固定为 **Anchor by Yokuli**。

### 快速使用

1. 首次启动时授予精确位置与通知权限。在“设置”中检查后台可靠性，并为夜间值守关闭厂商电池限制。
2. 打开“连接”，选择 TCP 客户端或 UDP 监听，填写地址和端口，再点“测试、保存并连接”。无效地址、无法访问的端点或没有有效 NMEA 数据都不会进入已连接状态。
3. 打开“NMEA”页确认原始语句和解析坐标持续更新。成功连接后，GPS 数据源默认切换为 NMEA。
4. 回到“锚警”页，直接在地图右上角选择默认图或卫星图，然后点“设置锚点”。
5. 选择下锚方式和范围：
   - **中心下锚：** GPS 天线位于锚点上方时开始，锚点与报警圈立即固定。
   - **倒车下锚：** 在落锚点开始，然后稳定倒车。橙色临时边界立即承担告警；青色圈显示锚点估算的不确定范围。样本、持续时间、位移和稳定性达到高置信度后，两圈合并为最终锚警圈。
6. 监控中可随时“调整范围”。“暂停”会保留整次会话，“继续”会在确认所选 GPS 的新鲜定位后恢复；只有“起锚”会永久关闭本次会话。
7. 如果活动锚警正在使用 NMEA，主动断开时必须选择：先安全切到新的系统 GPS 定位，或暂停锚警再断开。被动断线会保留布防、发出通知并自动重连，超过定位超时后升级为 GPS 数据丢失报警。
8. 圆心确认后可点“在 Google 地图中打开锚点”，直接用 Google Maps App（未安装时使用浏览器）显示精确坐标，便于查看和复制。

### 报警范围

基础模式由用户直接填写报警半径；高级模式根据几何参数与严格、均衡或宽容档位自动计算报警半径。**倒车下锚无论使用哪种范围模式，都必须填写水深、放出的锚缆/锚链和船艏滚轮离水面高度**；收到 DPT/DBT 时水深会自动预填。高级模式还填写船长。水平锚缆按 `sqrt(rode² - (depth + bowHeight)²)` 计算。

倒车下锚开始时，青色圈代表锚可能存在的可行域，其初始尺度约为水平锚缆长度，而不是几米 GPS 散布。每个 GPS 点都会与该可行域做允许少量离群点的鲁棒求交。真实艏向、TWD−TWA、低 SOG 时经过 AWS/TWS 与 AWA/TWA 重复匹配的 TWD−AWA，以及航速至少 0.8 节时由 COG 反推的艏向，都会作为不同权重的方向证据；风向使用环形均值与 20 秒稳定窗口，单条缓存风句不会被重复计数。这些证据只调整可行域概率，不能单独确定中心。

时间门槛会随证据自适应：真实艏向和重复风证据都存在且与 GPS 几何中心一致时最快 5 分钟；只有其中一种方向证据时至少 8 分钟；只有 GPS 轨迹时至少 15 分钟。无论时间多久，仍必须达到至少 200° 摆动覆盖、8 个方位扇区、两次有意义的摆向反转，并让前后两个独立时间段分别拟合出一致的圆心与半径。直线倒车、单向小弧、风数据跳变或风推断艏向与几何圆心不一致时永远不会提前确认。

活动会话中的“调整范围”只修改报警半径。水深、锚链、船艏高度、船长、下锚方式以及已积累的中心学习证据都是本次下锚的一次性参数，不会被范围调整重写。走锚时，后台继续发出通知和循环警报；如果 App 正在前台，还会显示不可直接略过的操作弹窗，提供稍后提醒、调整范围、暂停监控和起锚。

设置页可选择系统警报音、系统铃声、系统通知音或用户自己的音频文件。自定义文件不可读取时自动回退到系统警报音；通知通道本身保持静音，由锚警服务统一播放所选的循环警报，避免叠加两个声音。

### 演示 GPS

“设置 → 开发者设置 → 演示模式”开启后，本应用会强制锁定演示 GPS，并隐藏系统/NMEA 数据源选项。每次设置锚点都会重新获取一个新鲜的真实系统 GPS 起点，然后平滑向外运行本次会话随机化的安全摆动、持续走锚、风向改变或 GPS 中断轨迹，不会在风向切换时瞬移。存在活动或暂停会话时，演示模式、场景和速度都不能修改；必须先“起锚”结束会话。连接 NMEA 不会夺走演示数据源。

### NMEA 全局 GPS 代理

这是独立的可选功能，不是选择 NMEA GPS 的必要条件。启用步骤：

1. Android 设置 → 关于手机 → 连续点击版本号七次。
2. Android 设置 → 系统（部分设备为“更多设置”）→ 开发者选项。
3. “选择模拟位置信息应用” → **Anchor by Yokuli**。
4. 在 App 中连接有效 NMEA，选择 NMEA GPS，再打开“设置 → NMEA → Android GPS”。
5. 检查三项前置条件后开启全局代理。

代理通过 Fused Location mock mode 发布位置，并可选同步到 `GPS_PROVIDER`。系统 GPS 可能正是 App 自己注入的位置，因此代理开启时禁止把锚警切回系统 GPS，以避免数据回环；关闭代理后系统定位会恢复。第三方 App 可以拒绝模拟位置，所以无法保证兼容所有软件。

### 语言

进入“设置 → 语言”，点击 🇨🇳 或 🇬🇧 即可。应用桌面名称在所有语言下始终是 **Anchor by Yokuli**；中文界面内产品标题为 **Yokuli锚警系统**。

### 本地编译

需要 JDK 17 与 Android SDK 35。在未跟踪的 `local.properties` 中配置：

```properties
sdk.dir=/path/to/Android/sdk
MAPS_API_KEY=your_android_maps_key
```

Google Cloud 密钥只需启用 **Maps SDK for Android**，并限制到包名 `com.yokuli.anchorwatch` 和签名证书 SHA 指纹。本应用不调用 Places、Routes、Geocoding、Street View 或 Map Tiles API。建议同时在 Google Cloud 设置配额与预算提醒。不要把密钥提交到 Git。

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease
```

可直接安装的 Debug APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。

### 测试与 GitHub Actions 下载

单元测试覆盖 NMEA 解析与分包、连接预检、锚警状态机、告警稍后提醒、中心估算、范围计算、演示轨迹、GPS 代理策略和语言选择。设备集成测试使用真实 TCP 流、前台服务、Room 和 Compose UI，覆盖连接、断开决策、双向 GPS 切换、倒车中心学习、暂停/继续、起锚、活动中调范围、断线恢复、GPS 丢失、演示模式和中英文切换。

`.github/workflows/android.yml` 会自动：

1. 运行单元测试与 Lint；
2. 编译 Debug APK；
3. 启动 Android 14 模拟器运行设备集成测试；
4. 每次 push 上传可安装的 Debug APK，并上传测试和 Lint 报告。

在 GitHub 仓库的 Actions 运行详情中打开 **Artifacts**，下载 `anchor-by-yokuli-debug`；其中的 `app-debug.apk` 可直接安装。报告分别位于 `anchor-by-yokuli-build-reports` 和 `anchor-by-yokuli-integration-reports`。仓库应配置名为 `MAPS_API_KEY` 的 Actions Secret。

真正的版本发布使用独立的 `.github/workflows/release.yml`，只允许在 Actions 页面手动运行。先配置以下 GitHub Actions Secrets：

- `ANDROID_SIGNING_KEY_BASE64`：发布 keystore 文件的 Base64 内容；
- `ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`；
- `MAPS_API_KEY`。

然后运行 **Publish Anchor by Yokuli Release**，填写唯一的 Git tag、版本名和递增的 version code。Action 会验证签名，并创建 GitHub Release，附带已签名的 APK、可提交商店的 AAB 和 SHA-256 校验文件。签名 keystore 必须长期安全备份；丢失后无法为同一安装渠道发布可升级版本。

---

## English

### Highlights

- TCP client and UDP listener with validation and a real NMEA preflight before a connection can be saved or accepted.
- RMC, GGA, GLL, VTG, ZDA, HDG, HDM, HDT, DPT, DBT, MWD and MWV support across common talker IDs, with TWD/TWA/TWS/AWA/AWS stored independently.
- Normal and satellite maps are switched on the map itself; Google toolbar, directions, indoor and zoom controls are disabled.
- A heading-aware boat marker, separate anchor marker, a fading 24-hour breadcrumb track, and a complete retained calculation/history track.
- System and NMEA GPS in normal mode, with NMEA disabled until a connected source supplies a fresh valid fix. Developer Demo mode separately locks the App to Demo GPS.
- Persistent anchoring sessions with Pause, safe Resume, live range adjustment, and permanent Lift anchor actions.
- Drag, GPS-loss, NMEA-loss, quality and proxy-failure alarms. Snooze silences the current alert while monitoring continues and reminds again if danger persists.
- A live page for the latest 200 raw NMEA sentences, parsed position and diagnostics.
- Compact 🇨🇳 / 🇬🇧 language switching, including key background safety notifications.
- A pixel-art anchor launcher icon; the launcher label remains **Anchor by Yokuli** in every language.

### How to use

1. Grant precise-location and notification permissions. Review Background reliability in Settings and remove manufacturer battery restrictions for overnight use.
2. Open Connect, choose TCP or UDP, enter the endpoint, and tap **Test, save & connect**. Invalid endpoints and sources that provide no valid NMEA are rejected.
3. Confirm live raw sentences and parsed coordinates on the NMEA page. A verified connection automatically selects NMEA GPS.
4. Return to Watch, choose Normal or Satellite directly on the map, and tap **Set anchor**.
5. Choose the placement and range:
   - **Centre drop:** start while the GPS antenna is over the anchor. The centre and boundary arm immediately.
   - **Back down:** start at the drop point and reverse steadily. An orange temporary boundary protects the session immediately while a cyan uncertainty circle tightens around the estimated anchor. They merge after high-confidence centre resolution.
6. Adjust the radius at any time. Pause preserves the session, centre, samples, range and track; Resume waits for a fresh selected-source fix. Lift anchor permanently ends the session.
7. Disconnecting NMEA from a live NMEA-based watch requires a deliberate choice: safely acquire System GPS first, or pause and disconnect. Passive loss keeps the watch armed, warns immediately, reconnects, and escalates to GPS-data-loss if positions remain stale.
8. After centre resolution, tap **Open anchor in Google Maps** to display the precise coordinate in the Google Maps app, or a browser fallback, for inspection and copying.

### Range and centre estimation

Basic mode accepts a manually chosen alarm radius. Advanced mode calculates it from geometry and a Strict/Balanced/Tolerant preset. Back down always requires water depth, rode/chain paid out and the actual bow-roller height above water, regardless of range mode; DPT/DBT depth is prefilled when available. Advanced also uses boat length. Horizontal rode is `sqrt(rode² - (depth + bowHeight)²)`.

The initial cyan region is a possible-anchor area approximately one horizontal-rode radius across, not a tiny GPS-scatter circle. GPS discs are intersected robustly with a small outlier allowance. Physical heading, TWD−TWA, TWD−AWA only after repeated low-SOG AWS/TWS and AWA/TWA agreement, and reverse COG above 0.8 kn become separately weighted direction evidence. Circular means and stable 20-second windows prevent wrap-around and spike errors; a cached wind sentence counts only once. Direction evidence changes the feasible-region likelihood but can never resolve the centre alone.

The minimum time adapts to evidence: five minutes only when repeated physical-heading and wind evidence both agree with the GPS geometry; eight minutes with one reliable direction channel; fifteen minutes for GPS-only learning. Every path still needs at least 200° of swing coverage, eight bearing sectors, two meaningful swing reversals, and independently agreeing early/late robust fits. Straight back-downs, one-way shallow arcs, wind spikes or a wind-inferred heading inconsistent with the geometric centre never resolve early.

Adjust range changes only the current alarm radius. Depth, rode, bow height, boat length, placement mode and accumulated centre evidence remain one-time session inputs. An anchor-drag condition keeps the background notification and looping alarm, and also shows an unavoidable action dialog while the App is foregrounded, with Snooze, Adjust range, Pause and Lift anchor actions.

Alarm sound choices include the system alarm, ringtone, notification tone and a user-selected audio document. If the custom document becomes unreadable, playback falls back to the system alarm. The notification channel is silent so the service-owned looping alarm does not double-play with Android's channel sound.

### Demo GPS

Enable **Settings → Developer settings → Demo mode** to lock the App to Demo GPS and hide System/NMEA source choices. Every Set anchor captures a fresh real System GPS origin before a continuously moving, per-session randomized scenario starts. An open active or paused session locks Demo mode, scenario and speed until Lift anchor. Connecting NMEA does not replace Demo GPS.

### Global NMEA GPS proxy

This optional feature is separate from using NMEA inside the app:

1. Enable Android Developer options.
2. Choose **Anchor by Yokuli** under Select mock location app.
3. Connect a valid NMEA source and select NMEA GPS.
4. Open Settings → NMEA → Android GPS, review the preflight rows, and enable the proxy.

The proxy uses Fused Location mock mode and can also publish to `GPS_PROVIDER`. System GPS cannot be selected for the watch while proxying because the app could consume its own injected fix; disable the proxy first. Third-party apps may reject mock locations, so universal compatibility is not possible.

### Language and app name

Choose 🇨🇳 or 🇬🇧 under Settings → Language. The launcher name is always **Anchor by Yokuli**; the Chinese in-app product title is **Yokuli锚警系统**.

### Build, test and download

JDK 17 and Android SDK 35 are required. Put `MAPS_API_KEY` in untracked `local.properties`, or expose it as an environment variable in CI. Restrict it to the Android package and signing SHA fingerprint, and enable only Maps SDK for Android.

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease
./gradlew connectedDebugAndroidTest
```

GitHub Actions runs unit tests, lint, the Debug build and the Android 14 instrumented suite. Every push publishes the installable APK as the `anchor-by-yokuli-debug` artifact; reports are available as `anchor-by-yokuli-build-reports` and `anchor-by-yokuli-integration-reports`.

Production publishing is intentionally separate. Configure `ANDROID_SIGNING_KEY_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`, and `MAPS_API_KEY`, then manually run **Publish Anchor by Yokuli Release** with a unique tag, version name and increasing version code. It creates a GitHub Release containing the verified signed APK, Play-ready AAB and SHA-256 checksums. Keep the release keystore backed up securely; losing it prevents future upgrades through the same distribution channel.

## Privacy and permissions

Sessions remain on the device. There is no account, analytics, telemetry or project-owned cloud backend. Network/Wi-Fi permissions are used for NMEA; location and location-foreground-service permissions are used for System GPS and proxy operation; notifications, vibration and wake locks support alarms. No contacts, camera, microphone or broad storage permission is requested.

Third-party notices are in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
