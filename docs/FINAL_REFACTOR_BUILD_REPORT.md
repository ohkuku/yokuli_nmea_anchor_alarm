# Final vessel-data refactor build report

中文说明见下半部分。

## Build identity

- Date: 2026-08-24 (Pacific/Auckland)
- Working branch: `codex/develop`
- Recorded starting commit: `2807142 fix: preserve fragile NMEA server connections`
- Database: Room schema 17 upgraded sequentially to schema 18; exported schema is committed as `app/schemas/com.yokuli.anchorwatch.data.database.AppDatabase/18.json`.
- Delivery scope: the single final product refactor defined by `Anchor_Watch_FINAL_MASTER_产品优化与Codex施工总文档.md`. Older requirement documents were not executed in parallel.

## Automated gates

| Gate | Command | Result |
| --- | --- | --- |
| Unit and accelerated story tests | `./gradlew --no-daemon testDebugUnitTest` | PASS — 451 tests, 0 failures, 0 errors, 0 skipped |
| Android lint | `./gradlew --no-daemon lintDebug` | PASS |
| Debug package | `./gradlew --no-daemon assembleDebug` | PASS — `app-debug.apk` |
| Instrumented stories and migrations | `./gradlew --no-daemon connectedDebugAndroidTest` | PASS — 82 tests, 0 failures, 0 errors, 0 skipped; API 34 Pixel 7 emulator |

The connected suite includes fake TCP NMEA input, retained live sockets, explicit disconnect/reconnect, source locking, foreground-service recovery, Room migrations, 500,000-sample backup/restore, sonar grid scale, anchor-centre decisions, Sail MFD layout, map flows and accessibility layout.

## Safety invariants checked

- An NMEA source is identified by origin and source ID; HDT, HDG, VHW and phone candidates are not collapsed into one anonymous value.
- Per-metric selection is deterministic and sticky, with explicit fallback/recovery hysteresis, manual pinning and conflict reporting.
- Phone device heading and calibrated vessel heading are distinct. A moved or suspect mount cannot silently remain anchor-estimation evidence.
- External and derived true wind retain field-level provenance and reference; missing input remains missing instead of being invented.
- Phone NMEA publishing defaults to OFF, uses independent stream clocks, bounded latest-per-stream queues and never replays stale samples after reconnect.
- User Disconnect is authoritative. Background owners cannot silently reopen a latched endpoint. Explicit Resume may reopen NMEA only because it is a direct user confirmation for that paused session.
- Adopted safety centre and latest track estimate remain separate; applying or rejecting one estimate does not stop continuous estimation.
- Anchor heading-evidence enable/source changes open a new persisted epoch while retaining GPS, wind and COG evidence.
- Sonar depth is paired only with position from the same live NMEA stream.

## Physical limitations before a signed release candidate

The local workspace has no physical helm device or real NMEA gateway attached. Therefore these required release-candidate gates are **not claimed as run**:

- physical-device mount, magnetometer and IMU behaviour on representative phones/tablets;
- real Raymarine or equivalent gateway RX/TX validation, including separate receive and transmit ports;
- 8-hour Trip Watch soak;
- 12-hour Boat Watch soak;
- signed Release APK/AAB and store installation path.

Use [PHYSICAL_SOAK_CHECKLIST.md](PHYSICAL_SOAK_CHECKLIST.md) on the target hardware. A final signed public release should be promoted only after those results are recorded.

## Manual verification focus

1. Connect the real boat NMEA input and confirm Vessel source detail lists separate candidates and stable source IDs.
2. Exercise `OFF`, `BACKUP` and `ALWAYS` publication policies against the real receive endpoint and a separate transmit endpoint; confirm the plotter never selects duplicate/self-echoed position or heading.
3. Mount and calibrate the phone, move it deliberately, and confirm Device heading remains visible while Vessel heading is quarantined until the configured recovery criteria are met.
4. Pause an NMEA-backed anchor session, disconnect, then explicitly Resume; verify the same session reconnects and no track/centre/radius is replaced.
5. Record physical battery, memory and database growth using the soak checklist.

---

# 最终船舶数据重构构建报告

## 构建身份

- 日期：2026-08-24（Pacific/Auckland）
- 工作分支：`codex/develop`
- 起始提交：`2807142 fix: preserve fragile NMEA server connections`
- 数据库：Room schema 从 17 连续迁移到 18，并导出 schema 18。
- 范围：只执行最终总文档定义的这一版；没有并行执行旧需求文档。

## 自动门禁结果

- 单元与加速 story：451 项通过，0 失败、0 错误、0 跳过。
- Android lint：通过。
- Debug APK：编译通过。
- API 34 Pixel 7 模拟器：82 项设备链路与 migration 测试通过，0 失败、0 错误、0 跳过。

设备套件覆盖 Fake TCP NMEA、保留同一实时 socket、明确断开/重连、来源锁定、前台服务恢复、Room migration、50 万声呐样本备份恢复、声呐网格压力、锚中心决策、帆航 MFD、地图流程和无障碍布局。

## 尚未声称完成的物理门禁

本地没有接入真实驾驶台设备和 NMEA 网关，因此不能虚报以下项目已经执行：

- 多款实体手机/平板的安装姿态、磁力计和 IMU；
- Raymarine 或同类真实网关的收发验证，包括接收端口与发送端口不同的情况；
- 8 小时 Trip Watch soak；
- 12 小时 Boat Watch soak；
- 正式签名 APK/AAB 与商店安装链路。

请按 [PHYSICAL_SOAK_CHECKLIST.md](PHYSICAL_SOAK_CHECKLIST.md) 在目标船载硬件上执行。只有这些结果也被记录后，才应把本版本晋升为正式签名的公开 Release Candidate。
