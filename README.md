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
- 地图底图仅提供默认图与卫星图；可在地图页选择独立的 LINZ 水文海图影像叠加层与透明度。Google 地图工具栏、导航入口、室内与缩放按钮均隐藏。
- 船形标记随真艏向或 COG 旋转，确认前不显示锚图标；地图保留最近 24 小时的 breadcrumb，最新至少 600 米保持清晰，随后才按距离渐隐。渲染只抽稀，不删除计算点。
- 正常模式可选系统 GPS 或 NMEA GPS；只有服务器已连接并持续提供新鲜有效定位时才能选择 NMEA，成功连接后它会自动成为默认数据源。活动或暂停会话可在验证新来源后安全切换 System/NMEA，且不会丢失中心、范围或轨迹；断线不会静默切换。演示模式会单独锁定演示 GPS。
- 锚点可使用当前定位、手动十进制度坐标或地图长按选点；不知道锚点时可选自动估算。高置信度结果只作为候选显示，用户确认前绝不会移动生效中的报警圈，确认后也只移动圆心、不改半径。
- GPS 异常点先隔离；单点跳点不会进入报警或圆心学习，连续一致的真实位移会从第一个可疑点恢复进入计算。System GPS 的 bearing 始终按 COG 处理，绝不冒充船首向。
- 锚泊会话支持暂停、原会话继续、活动中调整范围和永久起锚；暂停不会清除中心、轨迹、范围或样本。
- 已结束的锚泊历史可由用户确认后删除，关联轨迹与事件时间线一并删除；活动会话不能删除。
- 告警覆盖走锚、GPS 数据丢失、NMEA 连接丢失、定位质量和代理失败；“稍后提醒”会停止当前声音与振动，但危险持续时会再次提醒。
- 可查看最近 200 条原始 NMEA 语句、解析位置、校验错误和连接统计。
- 界面与关键后台安全通知通过 🇨🇳 / 🇬🇧 两个按钮即时切换。
- 像素风锚形应用图标；桌面名称固定为 **Anchor by Yokuli**。

### 快速使用

1. 首次启动时授予精确位置与通知权限。在“设置”中检查后台可靠性，并为夜间值守关闭厂商电池限制。
2. 打开“数据”，选择 TCP 客户端或 UDP 监听，填写地址和端口，再点“测试、保存并连接”。无效地址、无法访问的端点或没有有效 NMEA 数据都不会进入已连接状态。
3. 在“数据 → 诊断”确认原始语句和解析坐标持续更新。成功连接后，GPS 数据源默认切换为 NMEA。
4. 回到“锚警”页，在地图右上角打开“图层”选择默认图、卫星图或 LINZ 海图叠加层，然后点“设置锚点”。
5. 先选本次会话的 System/NMEA 数据源，再选择锚点方式和范围：
   - **我知道锚点：** 使用当前定位、粘贴 `纬度, 经度`，或打开独立的全屏地图拖动锚标选点；锚点立即固定且不会运行自动学习。
   - **自动估算：** 没有“高级模式”。直接设置报警半径，并填写水深、锚链和船艏高度作为中心可行域约束。橙色临时边界立即承担告警；蓝色区域随多角度可信轨迹逐步缩小。达到高置信度后才显示候选锚图标，并询问是否围绕该点重画报警圈并退出学习模式。
6. 监控中可随时“调整范围”。“暂停”会保留整次会话，“继续”会在确认所选 GPS 的新鲜定位后恢复；只有“起锚”会永久关闭本次会话。
7. 活动或暂停会话都可安全切换 System/NMEA；应用会先验证新鲜可信定位，再原子保留原 session、中心、范围与轨迹。全局 NMEA GPS 代理开启时禁止切 System，避免自注入回环。主动断开 NMEA 时可选择“切换到系统 GPS 并断开”或“暂停锚警并断开”；被动断线不会静默切源，而是保留布防、通知并尝试恢复，超时后升级为 GPS 数据丢失报警。
8. 圆心确认后可点“在 Google 地图中打开锚点”，直接用 Google Maps App（未安装时使用浏览器）显示精确坐标，便于查看和复制。

### 报警范围

已知锚点时，基础模式由用户直接填写报警半径，高级模式可根据几何参数与严格、均衡或宽容档位计算半径。**自动估算锚点不存在基础/高级二选一**：报警半径始终由用户直接设置，水深、放出的锚缆/锚链和船艏滚轮离水面高度只用于约束可能圆心；收到 DPT/DBT 时水深会自动预填。水平锚缆按 `sqrt(rode² - (depth + bowHeight)²)` 计算。

自动估算开始时，蓝色圈代表锚可能存在的可行域，其初始尺度约为水平锚缆长度，而不是几米 GPS 散布。每个可信 GPS 点都会与该可行域做允许少量离群点的鲁棒求交。真实 NMEA 艏向、固定手机的可选真艏向、TWD−TWA、低 SOG 时经过 AWS/TWS 与 AWA/TWA 重复匹配的 TWD−AWA，以及航速至少 0.8 节时由 COG 反推的低权重运动方向，都会作为不同权重的辅助证据；它们不能单独确定中心。

时间门槛会随证据自适应：重复的真实艏向和风证据都与 GPS 几何一致时最快 5 分钟；只有其中一种方向证据时至少 8 分钟；只有 GPS 轨迹时至少 15 分钟。所有路径仍需至少 200° 覆盖、8 个稳健方位扇区和前后时间段独立拟合一致；快速路径至少一次完整往返反转，普通/GPS-only 路径至少两次。直线倒车、单向小弧、暂停时间、GPS 中断或不一致风向都不会被拿来提前确认。

高置信度候选中心会以低频历史样本持久化。只有至少 5 个样本跨越 8 分钟、净移动不少于 12 米、路径高度同向且不确定度没有恶化时，才显示“可能缓慢走锚”的辅助提醒；它不会改变报警状态，也不能替代正式报警半径。

活动会话中的“调整范围”只修改报警半径。水深、锚链、船艏高度、船长、下锚方式以及已积累的中心学习证据都是本次下锚的一次性参数，不会被范围调整重写。走锚时，后台继续发出通知和循环警报；如果 App 正在前台，还会显示不可直接略过的操作弹窗，提供稍后提醒、调整范围、暂停监控和起锚。

设置页只有“锚警警报音”和“自定义音频文件”两个选择。旧版本保存的铃声/通知音会自动迁移为锚警警报音。自定义文件不可读取时自动回退；通知通道本身保持静音，由服务统一循环播放，避免叠加两个声音。

### 演示 GPS

“设置 → 开发者设置 → 演示模式”开启后，本应用会强制锁定演示 GPS，并隐藏系统/NMEA 数据源选项。每次设置锚点都会重新获取一个新鲜的真实系统 GPS **船位起点**；隐藏的真实锚中心会带有随机但连续的偏移，不会错误地等于该起点。船先平滑放缆，再在一个扇区停留和随机摆动，随后缓慢换向到另一扇区；演示同时生成彼此一致、带噪声的艏向和风证据，足够长的多角度数据可让真实估算器产生候选。风向切换不会瞬移。存在活动或暂停会话时，演示模式、场景和速度都不能修改。

### NMEA Sharing

“数据 → 连接与数据 → NMEA Sharing”可启动本机 TCP 服务器（默认端口 `10111`），把应用已经接收的同一条上游 NMEA 流共享给海图仪、平板或其他船载设备；不会为了共享再创建第二套解析链路。服务器绑定所有本地网络接口，显示可连接地址、每个客户端地址/连接时长/发送量与输出状态，至少支持 5 个客户端。每个客户端都有有界队列，过慢客户端会被断开，不能拖住报警与其他客户端；监听异常时会自动尝试重绑。

- App 使用 **NMEA GPS** 时：透传所有校验有效的船载语句。
- App 使用 **System GPS** 时：按语句类型屏蔽任意 talker 的船载 RMC/GGA/GLL/VTG，保留艏向、风、深度、时间等仪表语句，再输出由可信新鲜系统 GNSS 编码的 `GNRMC`、`GNGGA`、`GNVTG`。不会把 COG 伪装为 HDT。
- 即使没有船载 NMEA 连接，System GPS 的 GN 定位输出仍可独立工作。

System GGA 不会捏造卫星数或高度：Android 未提供时字段留空；HDOP 缺失时只使用明确的兼容估算 `horizontalAccuracy / 3`。只有 COG 与 SOG 都存在时才生成 VTG，且不会定时重发过期位置。

共享服务没有 TLS 和身份验证，只应在可信的船载 LAN 或 VPN 中开启。连接预检会拒绝把本应用输入指向自己的共享地址与端口，避免自回环。

远端仍使用同一个 App：例如船上 Yokuli 显示 VPN 地址 `100.82.34.17`、端口 `10111`，岸上 Yokuli 只需新建普通 TCP 连接到 `100.82.34.17:10111`。VPN/Tailscale/WireGuard 由用户自行配置；本 App 不做公网穿透或云中继。

### LINZ 水文海图

地图“图层”面板可把 LINZ 水文海图影像 XYZ tiles 叠加在默认图或卫星图上，透明度为 30–100%。密钥与 layer ID/完整模板只能在编译阶段配置；未配置、离线或服务报错时地图和锚警继续工作，不会崩溃。该层位于 Yokuli 船位、轨迹、锚圈和报警覆盖物下方，不实现离线缓存或水深计算。

首次启用会显示航行安全免责声明。启用时地图显示强制署名：`Contains data sourced from the LINZ Data Service licensed for reuse under CC BY 4.0`。该影像仅供辅助，不能替代官方海图、航海通告、测深仪与正规航行计划。

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

需要 JDK 17 与 Android SDK 35。仓库的 Debug 配置已经包含可工作的 Android Maps 构建密钥；也可在未跟踪的 `local.properties` 或 CI 环境中覆盖：

```properties
sdk.dir=/path/to/Android/sdk
MAPS_API_KEY=your_android_maps_key
LINZ_API_KEY=your_linz_data_service_key
LINZ_HYDRO_LAYER_ID=your_hydro_chart_image_layer_id
# 或直接提供带 {z}/{x}/{y} 的 HTTPS 模板：
LINZ_HYDRO_TILE_TEMPLATE=https://.../{z}/{x}/{y}.png
```

Google Cloud 密钥只需启用 **Maps SDK for Android**，并限制到包名 `com.yokuli.anchorwatch` 和签名证书 SHA 指纹。本应用不调用 Places、Routes、Geocoding、Street View 或 Map Tiles API。建议同时设置配额与预算提醒。

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease
```

可直接安装的 Debug APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。

### 测试与 GitHub Actions 下载

单元测试覆盖 NMEA 解析、共享输出复用/系统 GN 语句编码、自回环、TCP 广播、LINZ 模板、连接预检、报警状态机、异常定位隔离、圆心估算、轨迹可见度、演示风/艏向证据和代理策略。设备测试使用真实 TCP、前台服务、Room 和 Compose，覆盖断线恢复、候选中心接受/拒绝、迁移、暂停/继续、起锚、范围、报警弹窗、历史级联删除、演示模式和双语 UI。

`.github/workflows/android.yml` 会自动：

1. 运行单元测试与 Lint；
2. 编译 Debug APK；
3. 将 Android 14 设备测试分成 3 个独立 shard 并行运行，每个 job 有明确超时，避免单个模拟器卡住整条流水线；
4. 每次 push 上传可安装的 Debug APK，并上传测试和 Lint 报告。

在 GitHub Actions 运行详情的 **Artifacts** 下载 `anchor-by-yokuli-debug-<commit SHA>`；其中 `app-debug.apk` 可直接安装。报告名包含 commit SHA，设备报告还包含 shard 编号。`MAPS_API_KEY` Secret 可覆盖仓库内的 Debug 构建密钥。

真正的版本发布使用独立的 `.github/workflows/release.yml`，只允许在 Actions 页面手动运行。先配置以下 GitHub Actions Secrets：

- `ANDROID_SIGNING_KEY_BASE64`：发布 keystore 文件的 Base64 内容；
- `ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`；
- `MAPS_API_KEY`；
- 可选：`LINZ_API_KEY` 与 `LINZ_HYDRO_LAYER_ID`，或 `LINZ_HYDRO_TILE_TEMPLATE`。

然后运行 **Publish Anchor by Yokuli Release**，填写唯一的 Git tag、版本名和递增的 version code。Action 会验证签名，并创建 GitHub Release，附带已签名的 APK、可提交商店的 AAB 和 SHA-256 校验文件。签名 keystore 必须长期安全备份；丢失后无法为同一安装渠道发布可升级版本。

---

## English

### Highlights

- TCP client and UDP listener with validation and a real NMEA preflight before a connection can be saved or accepted.
- RMC, GGA, GLL, VTG, ZDA, HDG, HDM, HDT, DPT, DBT, MWD and MWV support across common talker IDs, with TWD/TWA/TWS/AWA/AWS stored independently.
- Normal and satellite base maps plus an independent LINZ hydrographic chart-image overlay and opacity control, all selected on the map. Google toolbar, directions, indoor and zoom controls are disabled.
- A heading-aware boat marker, no premature anchor icon, and a 24-hour breadcrumb whose newest 600 m remains strongly visible before distance-based fading. Calculation/history points remain retained.
- System and NMEA GPS in normal mode, with NMEA disabled until a connected source supplies a fresh valid fix. Open sessions may switch between them only after the replacement supplies a trusted fresh fix; the centre, range and track remain intact, with no silent failover. Developer Demo mode separately locks the App to Demo GPS.
- Known anchors support current position, pasted decimal coordinates and map picking. Unknown anchors can be estimated, but a high-confidence result remains a candidate until the user accepts it; acceptance moves only the centre and never changes the radius.
- One-off GPS jumps are quarantined before both alarm and estimation. Sustained coherent displacement is released from its first suspicious fix. Android bearing is COG only and is never presented as heading.
- Persistent anchoring sessions with Pause, safe Resume, live range adjustment, and permanent Lift anchor actions.
- Confirmed deletion of completed history sessions together with their track and event timeline; active sessions cannot be deleted.
- Drag, GPS-loss, NMEA-loss, quality and proxy-failure alarms. Snooze silences the current alert while monitoring continues and reminds again if danger persists.
- A live page for the latest 200 raw NMEA sentences, parsed position and diagnostics.
- Compact 🇨🇳 / 🇬🇧 language switching, including key background safety notifications.
- A pixel-art anchor launcher icon; the launcher label remains **Anchor by Yokuli** in every language.

### How to use

1. Grant precise-location and notification permissions. Review Background reliability in Settings and remove manufacturer battery restrictions for overnight use.
2. Open Data, choose TCP or UDP, enter the endpoint, and tap **Test, save & connect**. Invalid endpoints and sources that provide no valid NMEA are rejected.
3. Confirm live raw sentences and parsed coordinates under **Data → Diagnostics**. A verified connection automatically selects NMEA GPS.
4. Return to Watch, open **Layers** to choose Normal, Satellite or the optional LINZ overlay, and tap **Set anchor**.
5. Choose this session's System/NMEA source, then the anchor mode and range:
   - **I know it:** use the selected source's current position, paste decimal coordinates, or drag the marker in a dedicated full-screen picker. The centre is authoritative and never auto-refined.
   - **Estimate it:** there is no Advanced mode. Set the alarm radius directly and enter depth, rode and bow height only as centre constraints. A temporary working boundary arms immediately while the possible-anchor region tightens. The anchor icon appears only for a high-confidence candidate; accepting redraws the unchanged-radius circle around it and leaves learning mode.
6. Adjust the radius at any time. Pause preserves the session, centre, samples, range and track; Resume waits for a fresh selected-source fix. Lift anchor permanently ends the session.
7. Active and paused sessions may switch safely between System and NMEA without losing the session, centre, range or track. The new source must first provide a fresh trusted fix. System is blocked while the global NMEA GPS proxy is active, preventing injected-location feedback. Passive NMEA loss keeps the session, warns immediately, reconnects, and escalates to GPS-data-loss if positions remain stale.
8. After centre resolution, tap **Open anchor in Google Maps** to display the precise coordinate in the Google Maps app, or a browser fallback, for inspection and copying.

### Range and centre estimation

For a known anchor, Basic accepts a manual radius and Advanced can calculate one from geometry and a Strict/Balanced/Tolerant preset. Automatic centre estimation has no Basic/Advanced choice: its radius is always set directly, while water depth, rode/chain and actual bow-roller height constrain the possible centre. DPT/DBT depth is prefilled when available. Horizontal rode is `sqrt(rode² - (depth + bowHeight)²)`.

The initial possible-anchor region is approximately one horizontal-rode radius, not a tiny GPS-scatter circle. Trusted GPS discs are intersected robustly with a small outlier allowance. Physical NMEA heading, optional fixed-phone true heading, TWD−TWA, repeatedly matched low-SOG TWD−AWA, and reverse COG above 0.8 kn become separately weighted evidence. Direction evidence changes likelihood but can never resolve the centre alone.

The minimum time adapts to evidence: five minutes only when repeated physical-heading and wind evidence agree with GPS geometry; eight minutes with one reliable direction channel; fifteen minutes for GPS-only learning. Every path still needs at least 200° coverage, eight robust bearing sectors and independently agreeing early/late fits. The fastest corroborated path requires a full out-and-back reversal; ordinary and GPS-only paths require at least two. Pauses and GPS gaps do not count as evidence time.

High-confidence candidate centres are persisted as low-rate history samples. A possible slow-drag advisory requires at least five samples over eight minutes, at least 12 m net movement, strongly consistent direction and non-degrading uncertainty. It never changes alarm state and never replaces the formal radius alarm.

Adjust range changes only the current alarm radius. Depth, rode, bow height, boat length, placement mode and accumulated centre evidence remain one-time session inputs. An anchor-drag condition keeps the background notification and looping alarm, and also shows an unavoidable action dialog while the App is foregrounded, with Snooze, Adjust range, Pause and Lift anchor actions.

Alarm sound has exactly two choices: the looping anchor alarm or a user-selected audio document. Legacy ringtone/notification selections migrate to the anchor alarm. If the custom document becomes unreadable, playback falls back safely. The notification channel stays silent so Android does not double-play another sound.

### Demo GPS

Enable **Settings → Developer settings → Demo mode** to lock the App to Demo GPS and hide System/NMEA choices. Every Set anchor captures a fresh real System-GNSS **boat origin**; the hidden simulated anchor has a continuous randomized offset and is not incorrectly placed at that origin. The vessel pays out smoothly, dwells and jitters in one sector, then turns gradually into another. Coherent noisy heading/wind evidence lets the real estimator produce a candidate after sufficient multi-angle history, without teleports or a hard-coded answer. An open session locks Demo configuration until Lift anchor.

### NMEA Sharing

Open **Data → Connection & data → NMEA Sharing** to run an on-device TCP server (default `10111`) for chartplotters, tablets and other boat devices. It reuses the App's single upstream stream and parser rather than opening a duplicate input. The server binds all local interfaces, shows each client address, connection duration and sent count, supports at least five clients, automatically rebinds after an abnormal listener failure, and uses bounded per-client queues so a slow consumer is dropped instead of blocking alarms or peers.

- With **NMEA GPS**, all checksum-valid boat sentences pass through.
- With **System GPS**, boat RMC/GGA/GLL/VTG are suppressed by sentence type regardless of talker, instrument heading/wind/depth/time sentences remain, and accepted fresh System GNSS is encoded as `GNRMC`, `GNGGA` and, when motion is available, `GNVTG`. COG is never invented as HDT.
- System-only position output works even without a boat NMEA connection.

System GGA never invents satellite count or altitude: unavailable fields stay blank. Missing HDOP uses the documented compatibility estimate `horizontalAccuracy / 3`. VTG is emitted only when both COG and SOG exist, and stale positions are never replayed by a timer.

There is no TLS or authentication; enable it only on a trusted boat LAN or VPN. Endpoint preflight rejects the App's own sharing address/port to prevent feedback loops.

The remote side uses the same APK. For example, if the boat phone shows VPN address `100.82.34.17` and port `10111`, create an ordinary Yokuli TCP input to `100.82.34.17:10111` on shore. The user supplies Tailscale/WireGuard or another VPN; Yokuli does not provide public tunnelling or a cloud relay.

### LINZ hydrographic charts

The on-map Layers panel can overlay LINZ hydrographic chart-image XYZ tiles above Normal or Satellite with 30–100% opacity. Keys and the layer ID/full template are build-time only. Missing configuration, offline use and service errors degrade to the base map without affecting alarms. The chart stays below Yokuli boat, track, anchor and alarm overlays; there is no offline chart cache or bathymetry engine.

First enablement shows a safety disclaimer. While enabled, the map displays: `Contains data sourced from the LINZ Data Service licensed for reuse under CC BY 4.0`. The imagery is an aid only and does not replace official charts, Notices to Mariners, depth instruments or a proper passage plan.

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

JDK 17 and Android SDK 35 are required. The Debug build has a bundled Android Maps build key; `MAPS_API_KEY` in untracked `local.properties` or CI can override it. Restrict it to the Android package/signing SHA and enable only Maps SDK for Android. LINZ is optional: set `LINZ_API_KEY` plus `LINZ_HYDRO_LAYER_ID`, or a complete HTTPS `LINZ_HYDRO_TILE_TEMPLATE` containing `{z}`, `{x}` and `{y}`.

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease
./gradlew connectedDebugAndroidTest
```

GitHub Actions runs unit tests (including NMEA output mux/server, self-loop, LINZ template, demo evidence and trail policy), lint and the Debug build, then executes the Android 14 suite in three parallel shards with per-job timeouts. Every push publishes `anchor-by-yokuli-debug-<commit SHA>`; build and per-shard integration reports also include the commit SHA.

Production publishing is intentionally separate. Configure `ANDROID_SIGNING_KEY_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`, and `MAPS_API_KEY`; optionally add `LINZ_API_KEY`/`LINZ_HYDRO_LAYER_ID` or `LINZ_HYDRO_TILE_TEMPLATE`. Then manually run **Publish Anchor by Yokuli Release** with a unique tag, version name and increasing version code. It creates a GitHub Release containing the verified signed APK, Play-ready AAB and SHA-256 checksums.

## Privacy and permissions

Sessions remain on the device. There is no account, analytics, telemetry or project-owned cloud backend. Network/Wi-Fi permissions are used for NMEA; location and location-foreground-service permissions are used for System GPS and proxy operation; notifications, vibration and wake locks support alarms. No contacts, camera, microphone or broad storage permission is requested.

Third-party notices are in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
