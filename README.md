# Anchor Watch

<p align="center">
  <img src="docs/images/anchor-watch-logo.png" width="150" alt="Anchor Watch pixel-art anchor logo">
</p>

<p align="center">
  <a href="README.zh-CN.md">简体中文</a>
  ·
  <a href="https://github.com/ohkuku/yokuli_nmea_anchor_alarm/actions/workflows/android.yml">
    <img src="https://github.com/ohkuku/yokuli_nmea_anchor_alarm/actions/workflows/android.yml/badge.svg" alt="Android CI">
  </a>
</p>

Anchor Watch is an Android anchor-watch and NMEA 0183 navigation companion. It accepts live TCP or UDP NMEA, monitors anchor drag and data loss, displays the vessel track and raw source data, maps personal sonar soundings, and can share or proxy accepted GPS positions when the user explicitly enables those features.

> **Safety:** Anchor Watch is an auxiliary aid. It does not replace proper watchkeeping, seamanship, official charts, depth instruments, or an independent alarm. GPS, power, Wi-Fi, NMEA equipment and Android background execution can all fail.

The app starts in English. Switch between 🇬🇧 and 🇨🇳 from the welcome screen or Settings.

## Product gallery

These are real screens from the current Android 14 Debug build. The gallery now uses the latest English product captures rather than the older mixed-language UI set.

| Watch map | Settings |
|---|---|
| <img src="docs/images/watch-current-en.png" width="320" alt="Anchor Watch map and vessel position"> | <img src="docs/images/settings-current-en.png" width="320" alt="Anchor Watch settings sections and visible support entry"> |

| NMEA input | Same-stream sonar gate |
|---|---|
| <img src="docs/images/data-final-en.png" width="320" alt="Validated NMEA connection settings"> | <img src="docs/images/sonar-final-en.png" width="320" alt="Personal sonar survey requires same-stream NMEA position and depth"> |

## Made aboard Yokuli

Before turning his life towards the sea, **kuku** worked as a programmer. In New Zealand, our crew refitted **Yokuli**, a **Lotus 10.6** designed by New Zealand yacht designer **Alan Wright**. **Yoyo is the captain**; kuku and lili complete the crew.

We hope first to explore New Zealand's islands and bays and, if wind, time and life allow, one day sail farther into the world. Anchor Watch grew from that life aboard. The complete anchor-watch, NMEA, sonar and offline-map feature set remains free, with no account, ads, paid unlocks or supporter-only features.

- [Watch Yokuli on YouTube](https://www.youtube.com/@yokuli_ocean_diary)
- [Voluntarily support the crew on Buy Me a Coffee](https://buymeacoffee.com/ukus3yya8a) — support unlocks no features.
- Feature requests and feedback: `kuku.the.developer@gmail.com`

## Highlights

- Validated NMEA TCP client and UDP listener. An endpoint is not accepted until the app receives usable NMEA traffic.
- RMC, GGA, GLL, VTG, ZDA, HDG, HDM, HDT, DPT, DBT, MWD and MWV support across common talker IDs.
- A single accepted-position pipeline that quarantines one-off GPS jumps before alarms, centre estimation, sharing or sonar mapping.
- System GPS and NMEA GPS in normal operation. NMEA cannot be selected without a connected source and a fresh valid fix.
- Persistent anchor sessions with Pause, safe Resume, live radius adjustment and permanent Lift anchor actions.
- Known-centre placement from the current fix, decimal coordinates or a dedicated map picker.
- Conservative automatic centre estimation using rode geometry, multi-sector GPS coverage, physical heading and corroborated wind evidence.
- Session-bound shallow/deep water, wind speed and wind-shift guards. Save buttons enable only when the validated values differ from the stored configuration.
- A 24-hour breadcrumb: the newest section stays strongly visible before distance-based fading; retained calculation points are not deleted by rendering simplification.
- Foreground drag alarm dialog plus looping alarm, notification, Snooze, Adjust range, Pause and Lift anchor actions.
- Watch Preflight and continuous Watch Health for GPS, NMEA, alarm audibility, notifications, battery/background restrictions, network, storage and sonar.
- Raw NMEA inspection, parsed values, checksum diagnostics and connection statistics.
- Local anchorage library with details, Google Maps opening and coordinate-QR image sharing. Saved places are references, never remote Set anchor commands.
- Saved-anchorage Approach: only explicitly saved places form reference clusters, show a once-per-episode prompt within 1 NM of the area boundary, and provide a large direct-bearing/distance guide. It is not route planning or safe-passage advice.
- NMEA Sharing server for trusted boat LAN/VPN clients, with regenerated accepted position sentences and bounded per-client queues.
- Personal sonar surveys using depth and position from the **same NMEA server**. System GPS is intentionally not used to locate real soundings.
- Map, Satellite and Nautical styles, a regional LINZ Local depth chart, bounded legal non-Google caches, and licensed raster MBTiles import.
- Exported Room schemas, tested migrations, bounded incident history, storage health tools, backup/restore and a privacy-safe Support Bundle.
- Optional Android global NMEA GPS proxy with explicit developer/mock-location preflight and loop prevention.

## How to use

1. Grant precise location and notification permissions. Review **Settings → Background reliability** and remove manufacturer battery restrictions before an overnight watch.
2. Open **Data → NMEA**, select TCP or UDP, enter the endpoint, then choose **Test, save & connect**.
3. Confirm live sentences and parsed coordinates in **Data → Raw data**. A verified position stream automatically selects NMEA GPS.
4. On the Watch map, choose Map, Satellite or Nautical and enable the Local depth chart only where coverage is available.
5. Tap **Set anchor** and complete Watch Preflight. Blockers prevent arming; warnings explain the risk that must be accepted.
6. Choose a known centre or conservative automatic estimation, then set the alarm radius and required geometry.
7. During the watch, adjust only the alarm radius, Pause without losing the session, or Lift anchor to end it permanently.
8. If an open NMEA-backed watch loses its connection, the session remains intact, warns immediately and attempts to reconnect. It never silently changes GPS source.
9. After a centre is resolved, open it in Google Maps for inspection or coordinate copying.
10. When returning to an explicitly saved anchorage, choose **Approach** for direct bearing and distance. The guide stops at the saved reference-area boundary; re-check current depth, traffic, weather and hazards before setting a new watch.

## Range and centre estimation

For a known anchor, Basic uses a manual radius. Advanced can calculate a radius from depth, rode, bow height and a Strict/Balanced/Tolerant preset.

Automatic centre estimation has no Basic/Advanced split. The radius is set directly; depth, rode and bow height constrain the possible centre. Horizontal rode is:

```text
sqrt(rode² - (depth + bowHeight)²)
```

The possible-centre region begins conservatively, then tightens only after enough compatible GPS discs and genuinely different bearing sectors exist. Direction evidence changes likelihood but cannot resolve a centre on its own. The fastest corroborated path still needs multi-angle coverage and reversal; GPS-only learning takes longer. The user must accept a high-confidence candidate before the working centre moves.

See [Anchor centre estimation](docs/ANCHOR_CENTRE_ESTIMATION.md) for the mathematical, physical and implementation design.

## Maps, depth and offline use

- Google content is never prefetched or cached.
- Nautical mode uses a quiet base style plus OpenSeaMap seamarks.
- The LINZ Local depth chart is a separate regional overlay and is the only chart layer with an opacity control.
- Recently viewed OpenSeaMap and LINZ tiles use separate bounded caches and may remain as stale offline fallbacks.
- Users may import raster MBTiles that they are licensed to store and use.
- Personal sonar cells remain available offline after a survey.

See [Offline map strategy](docs/OFFLINE_MAPS.md) and [Regional data providers](docs/REGIONAL_DATA_PROVIDERS.md).

## Background safety, diagnostics and data

The same safety model runs before and throughout a watch. A full device reboot cannot honestly promise uninterrupted Android location monitoring: an unfinished watch is recovered into a safe paused state and the user is asked to verify GPS, NMEA, alarm volume, power and network before resuming.

The incident ring retains a bounded recent history of service lifecycle, GPS disposition, NMEA reconnects, alarm transitions, battery, centre, sonar and sharing events. Exported Support Bundles omit raw NMEA, API credentials and exact positions by default.

Sessions and surveys stay on the device. There is no account, analytics, advertising or project-owned cloud backend. Data leaves only through user-initiated backup, survey, QR or diagnostic exports.

## Build, CI and downloads

JDK 17 and Android SDK 36 are required. API credentials are not committed. Put local development values in untracked `local.properties`; use GitHub Actions Secrets for CI:

- `MAPS_API_KEY`
- `LINZ_API_KEY` (optional)
- `LINZ_HYDRO_TILE_TEMPLATE` (optional HTTPS template containing `{z}`, `{x}` and `{y}`)

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest lintDebug
./gradlew connectedDebugAndroidTest
```

The main workflow runs unit tests, lint, Debug compilation, three Android 14 device-story shards, and an Android 16/API 36 launch and accessibility smoke. A downloadable `anchor-watch-debug-verified-<commit SHA>` artifact is published only after every gate passes. Production publishing is a separate manual signed release workflow.

Local project policy is to write tests with changes but run them only when explicitly requested. The commit-specific GitHub Actions result is the authoritative full quality gate.

Branch and release conventions are documented in [Branching and releases](docs/BRANCHING_AND_RELEASES.md). Before a real overnight release candidate, complete the [physical-device and 72-hour boat soak checklist](docs/PHYSICAL_SOAK_CHECKLIST.md).

## Project references

- [Product identity](docs/PRODUCT_IDENTITY.md)
- [Support policy](docs/SUPPORT_POLICY.md)
- [Privacy and data flow](docs/PRIVACY_DATA_FLOW.md)
- [Play release checklist](docs/PLAY_RELEASE_CHECKLIST.md)
- [Third-party notices](THIRD_PARTY_NOTICES.md)

The software licence remains an owner decision; no licence is implied by the absence of a licence file.
