# Anchor Watch

<p align="center">
  <img src="docs/images/anchor-watch-logo.png" width="150" alt="Anchor Watch 像素风锚形图标">
</p>

<p align="center">
  <a href="README.md">English</a> ·
  <a href="https://github.com/ohkuku/yokuli_nmea_anchor_alarm/actions/workflows/android.yml">
    <img src="https://github.com/ohkuku/yokuli_nmea_anchor_alarm/actions/workflows/android.yml/badge.svg" alt="Android CI">
  </a>
</p>

Anchor Watch 是一款给真实锚泊使用的 Android App。下锚以后，你可以在一个清楚的页面里看到船走过的轨迹、报警范围和当前状态；如果船离开安全范围，或者可靠定位突然中断，App 会用持续警报提醒你处理。

只有手机时可以直接使用系统 GPS；船上有 NMEA 0183 网络时，也可以接入船位、艏向、风和水深数据，还能绘制自己的水深海图，并把经过校验的数据分享给其他船载设备。

> **安全提示：** Anchor Watch 只能作为辅助工具，不能替代正规的锚泊值守、航海判断、官方海图、测深仪或独立报警设备。GPS、供电、Wi-Fi、NMEA 设备和 Android 后台运行都可能失效。

## 界面一览

这里暂时保留已有的中文产品图，截图会在后续单独更新。

| 锚警监控 | 设置锚警 |
|---|---|
| <img src="docs/images/anchor-active-zh.png" width="320" alt="锚警地图、报警范围与船位"> | <img src="docs/images/anchor-setup-zh.png" width="320" alt="设置锚警范围与方式"> |

## 它能帮你做什么

### 持续看住锚泊中的船

- 已知锚位时直接设置中心；不知道中心时，可以让 App 随船摆动，保守学习可能的锚中心。
- 普通使用可以选系统 GPS 或通过验证的 NMEA GPS。NMEA 没有连接并持续提供新鲜有效定位时，界面和内部逻辑都不能选择它。
- 地图会显示报警圈、船的方向和最近 24 小时轨迹；新轨迹保持清楚，旧轨迹经过足够距离后才逐渐淡化。
- 监控过程中可以调整报警半径；暂停不会丢失这次锚泊，之后可以原地恢复；只有 **起锚** 才会永久结束 session。
- 走锚时会同时出现循环警报、通知和 App 内操作弹窗。确认报警可以让持续风险稍后再提醒；调整范围、暂停或起锚会结束当前响铃状态。
- NMEA 数据可用时，还可以选择浅水、深水、风速和风向突变警戒。

### 在布防前和监控中确认系统是否可靠

- **布防前检查（Watch Preflight）**会检查定位新鲜度和精度、NMEA、通知权限、警报是否可听见、电池与后台限制、供电、网络、存储和声呐。
- **持续监控健康（Watch Health）**会在 session 中继续显示相同的安全状态。
- NMEA 意外断开时不会偷偷切到另一个 GPS 数据源。
- 环形 Incident Log 保存最近的安全状态变化；默认隐去原始 NMEA 和精确位置的 Support Bundle 可以用来排查“昨晚为什么报警”。

### 接入 NMEA，但不把数据藏起来

- 可作为 TCP 客户端或 UDP 监听器。只有真正收到可用 NMEA 后，**测试、保存并连接**才会成功。
- 可以查看原始语句、解析值、checksum 错误和连接健康。
- 支持常见 talker ID 下的 RMC、GGA、GLL、VTG、ZDA、HDG、HDM、HDT、DPT、DBT、MWD 和 MWV。
- NMEA Sharing 可把经过校验的位置与船载仪表数据提供给可信的船上 LAN/VPN 客户端，并限制慢客户端占用资源。
- 需要时可把可信 NMEA 船位代理为 Android 全局模拟位置；App 会给出开发者设置指引，并阻止数据回环。

### 使用地图、收藏锚地和水深

- 地图内可直接切换 **默认、卫星、航海**。锁定跟船时仍可临时拖动和缩放，随后自动回到船位；自由浏览则保留当前视野。
- 可叠加新西兰区域 LINZ 本地水深、合法的非 Google 受限缓存，或用户有权使用的栅格 MBTiles。App 永远不会缓存 Google 瓦片。
- 收藏锚地后可以查看备注和参数、在 Google 地图打开，或生成带 Anchor Watch 品牌的坐标二维码图片分享给朋友。
- 接近明确收藏过的锚地时，可以打开直线距离与方位指引。NMEA 有可用 HDT/HDG 或可信的移动 COG 时可选“船方位”，否则使用“手机方位”。它不是航线规划，也不会判断航路是否安全。
- 真实个人声呐调查只接受**同一台已连接 NMEA server**提供的水深和船位；锚警选择的系统 GPS 不会移动真实测深点。
- 声呐调查支持不修正、手动潮汐修正或自动 LINZ 潮汐预测，并保留可离线查看的海图基准水深历史。

## 第一次使用

1. 授予精确定位和通知权限。
2. 打开 **设置 → 报警与通知**，试听警报并确认自己能听见。可以使用标准锚警声，也可以选择自定义音频文件。
3. 只用手机 GPS 时可以直接回到锚警地图；使用船载数据时，打开 **数据 → NMEA**，填写 TCP/UDP 端点，再点 **测试、保存并连接**。
4. 在 **数据 → 原始数据**确认这些数据确实来自你的船，而且仍在更新。
5. 回到锚警地图，选择地图样式，然后点击 **设置锚点**。
6. 完成布防前检查，选择已知中心或自动估算，再设置报警半径。
7. 夜间使用时请保持可靠供电，并检查 **设置 → 后台可靠性**，避免系统暂停 App。
8. 监控期间要有意识地使用 **调整范围、暂停、恢复、起锚**；收到警报后先检查船况，不要只关闭声音。

## 自动推测锚中心

一条直线倒车轨迹无法唯一决定圆心，所以 App 不会把一段 GPS 直线当成答案。它会根据水深、放链长度和船艏高度先建立一个故意偏大的可能中心范围，只有在多个相容位置圆盘、真正不同的方位扇区、反向运动和重复方向证据互相支持时才逐步缩小。艏向与风向只能增强候选，不能单独确认中心。高置信度候选也必须由用户接受后，才能替换工作中心。

数学、物理和实现细节见 [锚中心点推测算法](docs/ANCHOR_CENTRE_ESTIMATION.md)。

## 演示模式、语言与隐私

开发者演示模式用于在没有真实 NMEA server 时熟悉 App。每次新下锚都会先读取新鲜的系统 GPS 作为起点，再生成逐步放缆、带相关噪声的船舶轨迹和对应演示声呐。演示模式或演示 session 开启后，数据源会锁定，避免真实与模拟数据混在一起。

App 默认使用英文。欢迎页和设置中的语言列表支持 English、简体中文、繁體中文、日本語、Français 和 Español。

Session 和声呐调查保存在本机。项目没有账号、分析统计、广告或自建云端；只有用户主动导出或共享时，数据才会离开设备。

## 编译、CI 与下载

需要 JDK 17 和 Android SDK 36。

```bash
./gradlew assembleDebug
```

主 Android workflow 负责构建和验证可下载的 Debug 产物。耗时的设备 story 集成测试与正式签名发布线互相独立；正式发布仍必须通过签名预检、单元测试、release lint、编译、校验和与启动 smoke。当前 workflow 发布到 GitHub Release，不会自动上传 Google Play。

API 值不会提交到仓库。地图、LINZ 和正式签名需要哪些 GitHub Secrets，以及如何从本机安全复制，见 [CI Secrets 配置说明](docs/CI_SECRETS.md)。分支与发版关系见 [分支和发布说明](docs/BRANCHING_AND_RELEASES.md)。

## 诞生于 Yokuli 船上

走向大海以前，**kuku** 是一名程序员；来到新西兰以后，航海慢慢成了生活。我们的团队翻新了 **Yokuli**——一艘由新西兰游艇设计师 **Alan Wright** 设计的 **Lotus 10.6**。**yoyo 是船长**，kuku 和 lili 是船员。

我们想先去看看新西兰的岛屿与海湾；如果风、时间和生活都允许，也希望有一天能驶向更远的世界。Anchor Watch 正是在这段生活里长出来的。锚警、NMEA、声呐和离线地图功能全部免费，不设账号、广告、付费解锁或支持者专属功能。

- [在 YouTube 看 Yokuli](https://www.youtube.com/@yokuli_ocean_diary)
- [在 Buy Me a Coffee 自愿支持船员](https://buymeacoffee.com/ukus3yya8a)——支持不会解锁任何功能。
- 可以在 App 的反馈页直接给 `kuku.the.developer@gmail.com` 发功能建议。

## 更多文档

- [离线地图策略](docs/OFFLINE_MAPS.md)
- [区域数据提供方](docs/REGIONAL_DATA_PROVIDERS.md)
- [隐私与数据流](docs/PRIVACY_DATA_FLOW.md)
- [正式版签名管理](docs/RELEASE_SIGNING.md)
- [真机与 72 小时船上 soak 清单](docs/PHYSICAL_SOAK_CHECKLIST.md)
- [第三方声明](THIRD_PARTY_NOTICES.md)

软件许可证仍由项目所有者决定；缺少许可证文件不代表授予任何许可。
