# Configuration Ownership Audit

Baseline inspected: `codex/develop` at `66112c0efe9d64e5a31541844ad6d40eccb47c6e`
Room schema: 20 · backup format: 5

This audit defines the only screen allowed to mutate each durable concept. A secondary screen may show status and provide a navigation link, but it must not keep its own switch or selector.

| Configuration key | Persisted field(s) | Repository / authority | Owner route | Runtime consumer | Scope | Previous duplicate or dual truth | Action |
|---|---|---|---|---|---|---|---|
| Vessel profile | boat length, bow height, antenna offsets | `SettingsRepository` | Settings → Vessel profile | Anchor geometry, reports | Global | Vessel and sensor concepts were mixed | Keep profile separate from Phone sensor calibration |
| Phone mount calibration | vessel zero, bow axis, heading alignment, mount state | `VesselMountCalibrationRepository` | Settings → Phone vessel sensors | `PhoneVesselAttitudeRepository`, Vessel Hub, NMEA output gate | Global | Output page implied it owned calibration | Output is read-only and links to calibration |
| Depth sounder calibration | NMEA sounder offset | `SettingsRepository` | Settings → Sonar calibration | live depth, survey recorder | Global | GPS error language was mixed into depth | Keep only instrument reading plus fixed offset |
| Default Anchor GPS | `gpsDataSource` while no session exists | `SettingsRepository` | Data → Position source | new Anchor/Trip setup | Default for new session | Setup could reopen on disconnected remembered NMEA | Setup selects only a currently usable source |
| Current Anchor GPS | `anchor_sessions.positionSource` | `AnchorWatchRuntime` | Data → Position source while session paused | accepted-position lock | Current session | Global selection could appear to change an active lock | Runtime lock is authoritative; UI labels the session scope |
| Instrument position source | `positionPreference`, pinned source | `VesselSettingsRepository` | Data → Vessel → Position | Vessel Hub, Data/Sail/Map/Trip | Global | Consumers could perform local fallback | Canonical Hub observation only |
| Instrument heading source | `headingPreference`, exact boat source pin | `VesselSettingsRepository` | Data → Vessel → Heading | Vessel Hub and Anchor evidence router | Global | Anchor Setup and active Anchor had enable switches | All Anchor switches and commands removed; Anchor is status-only |
| NMEA input profile | protocol, RX host, RX port, checksum, reconnect policy | `SettingsRepository` | Data → NMEA input | `NavigationRepository` | Global | Save & Connect also mutated TX | Input action now saves/connects RX only |
| NMEA output destination | transport mode, TX host, TX port | `OutputSettingsRepository` | Data → NMEA output | `PhonePositionNmeaOutputRuntime` | Global | Choosing separate TX produced validation before fields existed | Route is a local draft until valid and explicitly saved |
| NMEA publication lease | `publicationEnabled` | `OutputSettingsRepository` plus runtime | Data → NMEA output Start/Stop | output runtime | Runtime only | legacy auto-start and individual stream controls | One Phone/App-owned feed; no auto-start; calibration gate; one Start/Stop |
| Base map style | `mapType` | `SettingsRepository` | Map → Layers | Watch map renderer | Current display | Settings also exposed display controls | Settings no longer mutates display state |
| Nautical source | compatibility field `offlineMapEnabled` interpreted as `NauticalSourcePreference` | `SettingsRepository` | Map → Layers | `NauticalSourceResolver` | Current display | file installation and source selection were mixed | Map chooses Default online or imported MBTiles; Settings manages files only |
| LINZ NZ overlay | `linzHydroEnabled`, `linzHydroOpacity` | `SettingsRepository` | Map → Layers → Overlays | LINZ tile overlay | Current display | Settings duplicated visibility/opacity | Map is the only mutation surface; preference survives unsupported regions |
| Personal sonar overlay | `sonarLayerEnabled` | `SettingsRepository` | Map → Layers → Overlays | sonar grid renderer | Current display | Settings duplicated visibility | Map-only; disabled when no grid exists; fixed 75% opacity |
| Map depth readouts | `showLinzDepthReference`, `showPersonalMapReference` | `SettingsRepository` | Map → Layers → Depth readouts | map readout cards | Current display | grouped with chart files | Moved to Map and separated from chart sources |
| Chart files and storage | MBTiles metadata/file | `OfflineMapRepository` | Settings → Chart files & storage | tile provider | Global | import implicitly enabled the chart | Import never changes current map source; removal falls back online |
| Anchor defaults | preferred radius and default condition values | `SettingsRepository` | Settings → Anchor defaults | setup prefill only | Default for new session | current-session adjustments looked persistent | UI labels defaults versus current session |
| Current Anchor conditions | condition fields on active Room session | `AnchorWatchRuntime` / `ConditionRuntime` | Watch → Current anchor | alarm evaluation | Current session | could be enabled with no exact NMEA instrument | availability is checked both in UI and Runtime |
| Trip dashboards | dashboard entities/layout | `TripDashboardRepository` | Sail → Dashboard editor | Sail/Trip recorder/report | Global | no competing owner retained | Keep |
| Saved anchorage library | Place/Spot/Visit and Region tables | GIS repositories | Anchor → Anchorage library | library, nearby card, map, Approach | Global personal data | old `saved_anchorages` powered Watch while new saves went to GIS tables | GIS Place/Spot is now canonical; old table is migration/backup compatibility only |

## Compatibility fields

- `AnchorSessionEntity.usePhoneHeading` and `headingEvidenceEnabled` remain in Room and backup format 5. They are always written/upgraded to `true` and are never read as an evidence gate.
- `headingEvidenceEpoch` and `headingEvidenceSourceId` remain meaningful for source-change isolation.
- `NmeaDeviceOutputSettings` retains old per-stream fields and `CANONICAL_CLIENT_FEED` so existing DataStore and backups decode safely. Live product output always normalizes to `BOAT_BUS_INJECTION`: Phone sensors plus explicit App-derived results only. No UI or public ViewModel command can enable Boat-data forwarding or mutate the old stream switches.
- `offlineMapEnabled` remains the persisted compatibility bit for the two-value nautical preference. Domain/UI interpretation is `DEFAULT_ONLINE` versus `USER_MBTILES`.
- `saved_anchorages` remains in schema 20 for old backup compatibility. User-visible lists and approach geometry no longer collect it.

## Mutation audit result

- Anchor Current has no heading source selector or evidence enable switch. Its only action is **Manage vessel data source**.
- Settings → Chart files & storage can import, replace and remove files but cannot select the currently displayed source or overlay.
- Data → NMEA output owns only TX route, explicit Start/Stop, diagnostics and raw output. It cannot select metric sources or repeat calibration.
- Map → Layers is the only owner of map style, nautical source, LINZ, personal sonar and current-position depth readouts.

The executable registry is `domain/config/ConfigurationOwnership.kt`; its unit test requires exactly one owner entry for every key.
