# Privacy and data flow / 隐私与数据流

Anchor Watch has no user account, advertising, analytics, telemetry or project-operated cloud backend. Android cloud/device-transfer backup is disabled. Operational data remains local until the user explicitly connects, shares, opens or exports something.

Anchor Watch 没有用户账号、广告、分析、遥测或项目自营云后端。Android 云备份/设备迁移备份已关闭。除非用户主动连接、共享、打开或导出，运行数据均保留在本机。

| Flow / 数据流 | Direction / 方向 | Content / 内容 | User control / 用户控制 |
|---|---|---|---|
| Boat NMEA TCP/UDP | LAN → App | Navigation, wind, heading, depth, time, raw diagnostics | Explicit endpoint and connect action |
| System GNSS | Android → App | Position, accuracy, motion | Android permission and source selection |
| NMEA Sharing | App → trusted LAN clients | Filtered/re-encoded NMEA; accepted positions only | Explicit server switch; no TLS/auth |
| Google Maps SDK | App ↔ Google | Base-map requests and normal SDK metadata | Map use; key restricted at build time |
| LINZ | App ↔ LINZ services | Optional chart tiles, depth/tide queries | Layer/reference settings; NZ-only optional feature |
| YouTube / Buy Me a Coffee | App → external browser/app | Destination URL only | Explicit tap; support adds a confirmation |
| Feedback email | App → user-selected email app | Editable `mailto:` recipient, subject and request body; App/Android version and device model, but no location or NMEA metadata | Explicit tap; Anchor Watch never sends or tracks it |
| `.yokuli-backup`, GPX, sonar, support ZIP | App → user-selected document | User-selected local export | Android document picker |
| Global GPS proxy | App → Android location stack | Accepted NMEA position as mock location | Developer Options + explicit App switch |

## Retention / 保留

- Raw NMEA UI history is bounded to the latest 200 sentences and is not placed in Support Bundles.
- Incident Log is a local 72-hour / 10,000-record ring and sanitizes coordinates, raw sentences and credentials at entry.
- Anchor and sonar history persist until the user deletes or replaces them.
- LINZ and derived caches are bounded/rebuildable; Google content is never prefetched or cached by Anchor Watch.

## Sensitive exports / 敏感导出

Backups, GPX and sonar exports can contain precise vessel locations. The UI warns users to protect them like a vessel logbook. Support Bundles intentionally omit raw NMEA, exact coordinates and API keys. Build credentials belong only in untracked local properties or CI secrets and must never be written to diagnostics.

备份、GPX 与声呐导出可能包含精确船位，用户应像保护航海日志一样保护它们。Support Bundle 主动排除原始 NMEA、精确坐标与 API key。构建凭据只能放在未跟踪的本机配置或 CI Secret 中，绝不能写入诊断。
