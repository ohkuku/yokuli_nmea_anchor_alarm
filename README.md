# NMEA Anchor Watch

Native Android anchor-watch app that reads NMEA 0183 from a boat over TCP or UDP, displays the boat and swing track on Google Maps, persists anchor sessions, alarms on drag or lost GPS data, estimates the anchor centre, and can publish NMEA positions through Android's mock-location interfaces.

> **Safety:** This is an auxiliary aid, not a guaranteed marine-safety device. GPS errors, Wi-Fi loss, phone power management and OS faults can occur. It does not replace proper watchkeeping or seamanship.

## Build

Requirements: JDK 17 and Android SDK 35. Run:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

The APK is written under `app/build/outputs/apk/debug/`. No account, analytics, telemetry or cloud service is used; sessions stay on the device. Google Maps is the only external service.

## Google Maps API key

Enable **Maps SDK for Android** in Google Cloud, restrict the key to package `com.yokuli.anchorwatch` and your signing-certificate SHA fingerprint, then add this untracked property:

```properties
# local.properties
sdk.dir=/path/to/Android/sdk
MAPS_API_KEY=your_key
```

The app still starts without a key and shows an explanatory message instead of crashing.

For GitHub Actions builds, create a repository secret named `MAPS_API_KEY`. The workflow passes it to Gradle without committing it. Restrict that key to **Maps SDK for Android** only; the app does not use Places, Routes, Geocoding, Street View or Map Tiles APIs.

## NMEA connection

Open **Connect**, choose TCP client or UDP listener, enter host/port and tap **Test, save & connect**. The app does not save or connect until the endpoint supplies valid NMEA data. TCP handles partial/multiple packets and reconnects with bounded backoff. UDP binds the configured local port. Supported talkers include GP, GN, GL, GA and BD; dispatch is by sentence suffix.

Supported sentences: RMC, GGA, GLL, VTG, ZDA, HDG, HDM, HDT, DPT and DBT. Checksums are always validated when present and can be required. Diagnostics retain the latest 200 sentences in memory.

## Anchor workflow

With a valid fix, tap **Set Anchor** and choose **Centre drop** or **Back down**. Centre drop fixes the centre immediately. Back down creates and monitors the session immediately but initially shows **Learning centre**: it records the time-ordered drop cluster and movement without drawing an invented anchor marker or applying a radial alarm. GPS-data-loss protection remains active. Once sample count, duration, displacement and start-cluster stability reach high confidence, the centre becomes visible and full radius monitoring arms automatically. Later swing arcs can refine it only when angular coverage and residual checks are strong enough.

An open anchoring session has three user actions. **Pause** stops alarms and background position collection while preserving the session, centre, range and existing track; **Resume** requires a fresh selected-source fix and continues the same session; **Lift anchor** permanently closes it and leaves it in History. The alarm radius can be changed at any time without replacing the session, including after an alarm.

Basic range setup accepts a direct radius. Advanced setup uses low-tide depth, rode/chain paid out, boat length and a Strict/Balanced/Tolerant safety profile. It calculates taut horizontal rode as `sqrt(rode² - (depth + 1.5 m)²)`, then adds boat length, a GPS margin and, for Back down, a visible learning/estimation margin. Back down therefore starts with a wider recommendation instead of pretending its estimated centre is exact.

## Background monitoring

Monitoring starts from a visible, user-initiated action and runs in a location foreground service. Android 13+ notification permission and fine-location permission must be granted before starting it. While anchor watch or GPS proxy is active, the service owns the NMEA connection, reconnects it, publishes a persistent live-status notification and holds a non-reference-counted partial CPU lock plus an optional high-performance Wi-Fi lock. Locks are released whenever both safety features are off.

Disconnecting an NMEA connection used by an active anchor watch always requires an explicit safety decision. The user can cancel, acquire a fresh non-mock System GPS fix and then disconnect while keeping the watch armed, or pause the watch and disconnect while preserving its session. If System GPS is unavailable, NMEA remains connected. An unexpected NMEA loss immediately posts a high-priority notification, keeps the watch armed and reconnecting, records loss/recovery events, and escalates to the distinct GPS-data-loss alarm if valid positions do not return before the configured timeout.

The selected GPS source can also be changed in Settings while a session is actively monitoring. Handover is transactional: the service keeps the old source until the requested System or NMEA source produces a fresh fix, then records the change without replacing the session. System GPS cannot be selected while the global NMEA GPS proxy is active, because Android fused-location mock mode replaces the location stream for all clients; disable the proxy first. Paused sessions can change their next source without discarding their centre.

The watchdog checks position freshness once per second, raises a distinct GPS-data-loss alarm, stops stale mock injection, warns below 15% battery, and restores an active Room session after an ordinary service recreation. After a full phone reboot, modern Android may forbid silently starting a location foreground service; the boot receiver therefore posts a high-priority recovery notification that requires the user to reopen the app. Settings shows notification, fine-location and battery-optimization status. For overnight use, exclude the app from manufacturer battery restrictions, verify alarm volume and keep the device on reliable power.

## NMEA as Android GPS

Android does not permit an ordinary app to replace GNSS without explicit user authorization:

1. Enable Developer Options (tap Build number seven times in Android Settings).
2. Open **Select mock location app** and choose NMEA Anchor Watch.
3. Confirm live NMEA data in the NMEA page.
4. Open Settings → NMEA → Android GPS and review every preflight row.
5. Enable GPS proxy.

The implementation uses `FusedLocationProviderClient.setMockMode/setMockLocation` and optionally a same-name `GPS_PROVIDER` test provider for apps that bypass FLP. It publishes latitude/longitude, monotonic and wall-clock timestamps, HDOP-derived accuracy, SOG as m/s, COG as true bearing, and altitude when present. The rate is selectable at 1/2/5 Hz. It catches denied app-op/security access, marks the UI active only after the mock call succeeds, and always disables both providers on user stop, stale NMEA, injection failure, or service destruction. Mock positions are marked as mock; third-party apps can reject them with `Location.isMock()`, so universal compatibility cannot be guaranteed. No root, ADB runtime dependency, shell hack or hidden API is used.

Manual acceptance: confirm another ordinary fused-location app follows a moving NMEA source, then switch override off and verify the phone returns to its own location source.

## Testing

Unit tests cover parsers, stream splitting, connection preflight, geometry, learning-mode GPS loss, advanced range calculation, mock-GPS policy and centre confidence. Instrumented integration tests exercise the real TCP stream, foreground service, Room database and UI, including immediate Back-down session creation, hidden-to-resolved centre learning, pause/resume identity preservation, Lift-anchor closure, live range changes, bidirectional source handover, disconnect decisions, passive-loss escalation and automatic recovery.

The GitHub Actions workflow runs unit tests, lint and debug/release builds, uploads the APKs and reports, then starts an API 34 emulator, seeds a System GPS position and runs the instrumented suite. Download `nmea-anchor-watch-build` and `integration-test-reports` from the workflow run's **Artifacts** section.

## Permissions

Internet/network/Wi-Fi state are used for NMEA; fine/coarse location and location foreground service for Android location operation; notifications, vibration and wake lock for reliable alarms. No contacts, camera, microphone or broad storage permission is requested.

## Third-party notices

See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). The implementation was informed by common NMEA techniques described by `sankeysoft/nmea_dashboard`; no complete application code was copied.
