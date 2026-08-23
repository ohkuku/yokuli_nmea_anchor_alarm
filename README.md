# Anchor Watch

<p align="center">
  <img src="docs/images/anchor-watch-logo.png" width="150" alt="Anchor Watch pixel-art anchor logo">
</p>

<p align="center">
  <a href="README.zh-CN.md">简体中文</a> ·
  <a href="https://github.com/ohkuku/yokuli_nmea_anchor_alarm/actions/workflows/android.yml">
    <img src="https://github.com/ohkuku/yokuli_nmea_anchor_alarm/actions/workflows/android.yml/badge.svg" alt="Android CI">
  </a>
</p>

Anchor Watch is an Android app for people who want one clear place to watch the boat after anchoring. Set an alarm area, see where the boat has moved, and receive a loud warning if the boat leaves that area or reliable position data disappears.

It works with the phone's GPS on a simple boat, or with live NMEA 0183 data on a more connected one. NMEA can also add heading, wind and depth, feed a personal depth map, and be shared with other equipment aboard.

> **Safety:** Anchor Watch is an extra aid, not a substitute for watchkeeping, seamanship, official charts, a depth instrument or an independent alarm. GPS, power, Wi-Fi, NMEA equipment and Android background execution can all fail.

## A quick look

These are existing product captures; the gallery will be refreshed separately.

| Anchor watch | Live boat data |
|---|---|
| <img src="docs/images/watch-current-en.png" width="320" alt="Anchor Watch map and boat position"> | <img src="docs/images/data-final-en.png" width="320" alt="NMEA connection and live-data page"> |

## What the app helps you do

### Keep an anchor watch

- Set a known anchor position, or let the app conservatively learn a possible centre while the boat moves around it.
- Use System GPS or a verified NMEA GPS source. NMEA stays unavailable until the server is connected and supplying a fresh valid position.
- See the alarm boundary, boat direction and a 24-hour breadcrumb whose newest part remains easy to see.
- Adjust the alarm radius while watching, pause without losing the session, resume later, or use **Lift anchor** to end it.
- Get a looping alarm, notification and in-app action dialog. Acknowledging can snooze continuing danger; changing the range, pausing or lifting the anchor ends the current sounding state.
- Optionally watch shallow/deep water, wind speed and wind shifts when the required NMEA data is actually available.

### Know whether the watch is healthy

- **Watch Preflight** checks position freshness and accuracy, NMEA, notification access, alarm audibility, battery/background restrictions, power, network, storage and sonar before arming.
- **Watch Health** keeps the same checks visible during the session.
- Unexpected NMEA loss never silently changes the watch to another GPS source.
- A bounded Incident Log records recent safety-state changes. A privacy-safe Support Bundle helps investigate a problem without including raw NMEA or exact positions by default.

### Use boat data without hiding it

- Connect as a TCP client or UDP listener. **Test, save & connect** succeeds only after usable NMEA traffic is received.
- Inspect raw sentences, parsed values, checksum failures and connection health.
- Supported sentences include RMC, GGA, GLL, VTG, ZDA, HDG, HDM, HDT, DPT, DBT, MWD and MWV across common talker IDs.
- Share accepted positions and boat instruments with trusted LAN/VPN clients through the bounded NMEA Sharing server.
- Optionally proxy accepted NMEA position into Android's global mock-location provider, with developer-setting guidance and loop prevention.
- Missing fields in an otherwise valid sentence are treated as “not updated”, not as invalid data. The last depth, wind, heading or speed value keeps its original receive time and visibly changes from **live** to **held** to **stale**; safety guards still require their own fresh evidence.

### Record a trip without weakening Anchor Watch

- **Watch → Trip Watch** provides live NAV, sailing, motion and weather instruments through a separate Vessel Data Hub. Its AUTO source fallback never changes the GPS source locked by an active anchor watch.
- Start, pause, resume and end a local trip session. Readiness shows which instruments are available, while genuine missing data remains a gap rather than a made-up zero.
- Phone heel/pitch recording requires a fixed vessel-mount calibration; phone barometer recording remains independent. Source and data age are stored with every observation.
- Create named instrument dashboards, bind discovered NMEA fields without storing the raw stream, and independently choose which custom fields are recorded at up to 2 Hz. Live, held, stale and source state remain visible on every tile.
- Completed trips have a bounded-memory report covering route, SOG/BSP, fastest 500 m, conservative point-of-sail/tack/gybe observations, heel, motion, depth/UKC, wind, pressure, source changes, events and waypoints. Replay can colour the route by SOG, BSP, heel, TWS, AWS, motion or depth and jump directly to events.
- Export CSV, events, waypoints, custom metrics, GPX, KML/KMZ, branded snapshots or a local AI source ZIP. UKC is shown only when the recorded depth reference is compatible with the configured draft. Anchor and Trip sessions cannot both be active.
- Completed Anchor and Trip sessions can create a local **AI source ZIP** after a precise-location privacy warning. The app never uploads these archives itself.

### Use maps, saved anchorages and depth

- Switch on the map between **Map**, **Satellite** and **Nautical**. Following the boat still permits temporary pan/zoom before returning automatically; free-browse mode keeps the chosen view.
- A scale bar follows the current latitude and zoom. The ruler button creates two draggable pins and shows their straight-line distance in metres or in both nautical miles and kilometres; tapping the ruler again clears the measurement.
- Add the regional LINZ local-depth layer, recently used legal non-Google tile caches, or a licensed raster MBTiles file. Google tiles are never cached by the app.
- Save an anchorage for later reference, view its notes and setup, or open it in Google Maps. A branded QR card can carry the coordinate plus the saved radius, depth, rode, seabed, rating and notes; another Anchor Watch user can scan it with the camera or choose a QR image from the gallery, review every field and explicitly confirm the import.
- Near a saved anchorage, use the direct distance/bearing guide. Choose vessel direction when usable NMEA HDT/HDG or trusted moving COG exists, otherwise use phone direction. This is not route planning or safe-passage advice.
- Record a personal depth chart only when depth and position come from the **same connected NMEA server**. The anchor-watch GPS choice does not move real sonar samples.
- Apply no, manual, or automatic LINZ tide correction to sonar surveys and keep chart-datum-corrected history available offline.

## Getting started

1. Grant precise location and notification permissions.
2. Open **Settings → Alarm & notifications**, test the alarm, and confirm it is audible. Select the standard anchor alarm or a custom audio file.
3. If using phone GPS, go straight to Watch. For boat data, open **Data → NMEA**, enter the TCP/UDP endpoint, then choose **Test, save & connect**.
4. Check **Data → Raw data** to make sure the values belong to your boat and keep updating.
5. On the Watch map, choose the map view and tap **Set anchor**.
6. Complete Watch Preflight, choose a known centre or automatic estimation, and set the alarm radius.
7. Keep the phone on reliable power and review **Settings → Background reliability** before an overnight watch.
8. During the watch, use **Adjust range**, **Pause**, **Resume** or **Lift anchor** deliberately; do not dismiss a warning without checking the boat.

## Automatic centre estimation

A straight back-down track cannot uniquely reveal an anchor centre, so the app does not treat one line of GPS points as a solution. It starts with a deliberately broad possible-centre region derived from depth, rode and bow height, then narrows it only when compatible position discs, genuinely different bearing sectors, reversals and repeated direction evidence agree. Heading and wind can strengthen a candidate but cannot confirm one by themselves. The user must accept a high-confidence candidate before it replaces the working centre.

See [Anchor centre estimation](docs/ANCHOR_CENTRE_ESTIMATION.md) for the mathematical, physical and implementation design.

## Demo, languages and privacy

Developer Demo mode lets a user learn the UI without a live NMEA server. Every new demo anchor starts from a fresh System-GNSS position, then generates a gradual noisy boat track and matching demo sonar. Demo GPS remains locked while the mode or its session is active so real and simulated data cannot be mixed accidentally.

The app starts in English. The welcome screen and Settings language list support English, Simplified Chinese, Traditional Chinese, Japanese, French and Spanish.

Sessions and surveys stay on the device. There is no account, analytics, advertising or project-owned cloud backend. The camera is requested only after opening the anchorage QR scanner, and frames are decoded locally. Data leaves only through an export or sharing action started by the user.

The local V3 backup includes Anchor and Trip sessions, raw source observations, waypoints, saved anchorages, vessel-source/layout preferences and vessel-mount calibration. On restore, active watches return in a safe paused state and Phone GPS Output, NMEA Sharing and GPS proxy stay off until the user deliberately enables them again.

The current V4 backup additionally includes custom Trip metric samples and named dashboards. It still excludes imported MBTiles and custom alarm audio, and every external output remains off after restore.

When explicitly enabled, the phone can act as a vessel sensor node and write fresh GNSS, heading, rate, calibrated attitude and pressure sentences back through the **same existing boat TCP connection**. It never opens a second boat socket, never writes to the local sharing-server port, and Phone Position Output is hard-conflicted with using boat NMEA position as the app GPS source.

## Build, CI and downloads

JDK 17 and Android SDK 36 are required.

```bash
./gradlew assembleDebug
```

The main Android workflow builds and verifies downloadable Debug artifacts. Long device-story integration is separate from the signed-release path, while Release still requires signing preflight, unit tests, release lint, compilation, checksums and launch smoke. If a build, lint, emulator, soak, signing or publishing job fails, its Actions run contains a 30-day `FAILURE-*` diagnostics artifact with the available reports and device logcat, but no signing or API secrets. The current workflow publishes GitHub Releases; it does not upload to Google Play.

API values are never committed. See [CI secrets setup](docs/CI_SECRETS.md) for the exact map, LINZ and signing values and the safe clipboard helper. Branch and release conventions are in [Branching and releases](docs/BRANCHING_AND_RELEASES.md).

## Made aboard Yokuli

Before turning his life towards the sea, **kuku** worked as a programmer. In New Zealand, our crew refitted **Yokuli**, a **Lotus 10.6** designed by New Zealand yacht designer **Alan Wright**. **Yoyo is the captain**; kuku and lili complete the crew.

We hope first to explore New Zealand's islands and bays and, if wind, time and life allow, one day sail farther into the world. Anchor Watch grew from that life aboard. Every anchor-watch, NMEA, sonar and offline-map feature remains free, with no account, ads, paid unlocks or supporter-only functions.

- [Watch Yokuli on YouTube](https://www.youtube.com/@yokuli_ocean_diary)
- [Voluntarily support the crew on Buy Me a Coffee](https://buymeacoffee.com/ukus3yya8a) — support unlocks no features.
- Send feedback to `kuku.the.developer@gmail.com`, or use the in-app Feedback page.

## More documentation

- [User-story safety audit and state contracts](docs/USER_STORY_SAFETY_AUDIT.md)
- [Offline maps](docs/OFFLINE_MAPS.md)
- [Regional data providers](docs/REGIONAL_DATA_PROVIDERS.md)
- [Privacy and data flow](docs/PRIVACY_DATA_FLOW.md)
- [Release signing](docs/RELEASE_SIGNING.md)
- [Physical-device and 72-hour boat soak checklist](docs/PHYSICAL_SOAK_CHECKLIST.md)
- [Third-party notices](THIRD_PARTY_NOTICES.md)

The software licence remains an owner decision; no licence is implied by the absence of a licence file.
