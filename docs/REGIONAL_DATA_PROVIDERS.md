# Regional marine-data providers / 地区海洋数据源

## Global core / 全球核心

Anchor alarm, System/NMEA GPS, NMEA input/sharing, local history, demo mode, sonar recording and user-imported licensed MBTiles are not tied to New Zealand. They must continue working when every regional provider is unavailable.

锚警、System/NMEA GPS、NMEA 输入/共享、本地历史、演示模式、声呐记录与用户导入的合法 MBTiles 不依赖新西兰服务。所有地区数据源不可用时，这些核心功能仍必须工作。

## New Zealand provider / 新西兰数据源

LINZ is an optional regional provider for hydrographic chart imagery, vector depth reference and tide prediction. Build configuration uses `LINZ_API_KEY` or a complete HTTPS `LINZ_HYDRO_TILE_TEMPLATE`. Missing configuration, timeout, invalid response or offline use must degrade visibly without affecting watch state or alarms.

LINZ 是可选的新西兰地区数据源，用于水文海图影像、矢量水深参考与潮汐预测。构建时使用 `LINZ_API_KEY` 或完整的 HTTPS `LINZ_HYDRO_TILE_TEMPLATE`。缺少配置、超时、响应无效或离线时必须明确降级，且不得影响锚警状态与报警。

LINZ attribution and source/provenance must remain visible. Cached LINZ tiles are bounded and stale-aware. Anchor Watch never caches Google map content. See [OFFLINE_MAPS.md](OFFLINE_MAPS.md).

LINZ 署名与来源信息必须持续可见；LINZ 缓存有容量与新鲜度限制。Anchor Watch 不缓存 Google 地图内容。详见 [OFFLINE_MAPS.md](OFFLINE_MAPS.md)。

The on-map generic name is **Local depth chart**; LINZ is shown as the concrete `LINZ · New Zealand` provider. Availability follows the accepted boat position while locked and the settled camera target while browsing. Leaving coverage hides rendering without clearing the user's enabled preference.

地图上的通用名称为**区域水深海图**；LINZ 只作为具体的 `LINZ · New Zealand` 提供方显示。锁定跟船时按已接受船位判断可用性，自由浏览时按停止移动后的相机中心判断。离开覆盖区只隐藏渲染，不会清除用户的开启偏好。

## Adding another region / 扩展其他地区

A future provider must be an optional adapter with explicit region, coverage, attribution, licence, network/cache behavior, update timestamp, units, datum/reference and failure state. It must not masquerade as live sonar or silently become safety-critical. Provider code must have fixture tests for parsing, provenance, time zone/datum conversion, cache expiry and outage fallback before UI enablement.

未来新增地区数据源必须作为可选适配器，明确地区、覆盖范围、署名、许可、网络/缓存行为、更新时间、单位、基准面与失败状态；不得冒充实时声呐，也不得静默成为安全关键依赖。UI 开启前，必须用固定样本测试解析、来源、时区/基准转换、缓存过期与断网降级。

The future abstraction names are `ChartProvider`, `DepthReferenceProvider` and `TideProvider`; `LINZ` remains the name of one concrete New Zealand integration, never the generic name for the whole App architecture. These interfaces are intentionally architectural TODOs rather than unused production abstractions in this release.

未来的抽象名称为 `ChartProvider`、`DepthReferenceProvider` 与 `TideProvider`；`LINZ` 只代表一个具体的新西兰实现，绝不作为整个 App 架构的通用名称。本版本只记录这些架构待办，不提前加入没有实际使用者的生产接口。

Architecture TODO only, not release commitments: Australia, USA and Europe. Each one still needs a legal/licensing review and an authoritative provider selected before implementation.

以下仅为架构待办，并非版本承诺：澳大利亚、美国和欧洲。每个地区都必须先完成法律/许可核对并选定权威数据源，才会进入实现。
