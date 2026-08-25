# NMEA Publisher P0 verification report

Date: 2026-08-25  
Branch: `codex/nmea-publisher-hard-stop`

## Fixed behavior

- Heading is scheduled independently at 5 Hz rather than inside a 1 Hz canonical batch.
- Depth is independently scheduled at 1 Hz, STW at 2 Hz, and selected Boat ROT is published by the 2 Hz Motion stream even without phone attitude.
- Numeric measurement time and same-source heartbeat time are evaluated separately.
- Position cannot be kept alive by an empty coordinate heartbeat.
- Held Heading remains publishable while the same physical source continues heartbeating.
- Explicit invalid status creates an invalid tombstone and immediately clears the publication lease.
- Every published metric has a last-complete-value lease with a stable source key.
- A READY source switch is atomic and increments the visible source epoch.
- Every queued batch carries a publication generation; Stop invalidates and clears old batches.
- Same-input batches also carry the exact RX/TX transport generation. A batch queued before reconnect is dropped with `STALE_TRANSPORT_GENERATION` and can never cross onto the replacement socket.
- Exact and talker/checksum-transformed echoes consume one quarantine occurrence per successfully written App frame; repeated real instrument values are not hidden indefinitely.
- Generic NMEA fields expire on a one-second timer even when the source becomes completely silent. `XDR,P,...,B,PHONE_BARO` is decoded as hPa pressure rather than an untyped raw field.
- Every generated/diagnostic frame is checked for ASCII, checksum, single-frame CRLF and the 82-byte NMEA 0183 limit before any socket write.
- Dedicated blocking connect registers its candidate socket before connect, allowing Stop to interrupt it.
- TCP-server Stop closes all clients and waits for old writer jobs before returning.
- Only `YokuliRuntimeCoordinator` owns normal runtime configuration.
- Legacy Sharing is migration-only and has no raw-sentence publication consumer.
- The recommended/default full-duplex same-input Socket, plus explicitly selected Advanced TCP client/server and UDP modes, consume the same canonical scheduler.
- Live Output UI shows session, generation, destination, actual write time, per-stream rates/source epoch/suppression and recent packet-path diagnostics.

## Automated gates

| Gate | Result | Evidence |
|---|---|---|
| Red tests before implementation | PASS | Missing measured-time model and held Heading behavior failed before the fix |
| Targeted Source/Lease/Publisher/Socket JVM tests | PASS | `VesselSourceArbitratorTest`, `VesselSourceRegistryTest`, `AnchorWatchNmeaPublisherTest`, `NmeaDeviceOutputPolicyTest`, `NmeaSharingServerTest` |
| Post-audit transport/framing/echo/field/GPS-policy tests | PASS | Old-transport rejection, 300 randomized TCP fragmentations, exact + transformed echo occurrence consumption, silent field expiry, PHONE_BARO semantics, frame validation, localized Arm result and GNSS startup resource hand-off |
| Final focused close-out | PASS | 89/89 across Stop barrier, output/server policy, reconnect generation, field retention, parser/framing, source invalidation and System-GPS startup policy |
| Full Debug unit tests | PASS | Final post-audit run: 549 total, 0 failed/errors, 1 opt-in wall-clock soak skipped by default. |
| Android lint Debug | PASS | `lintDebug`; HTML report generated at `app/build/reports/lint-results-debug.html` |
| Debug APK assemble | PASS | `assembleDebug`; `app/build/outputs/apk/debug/app-debug.apk` |
| Android test APK compile/package | PASS | `assembleDebugAndroidTest`; includes `freshSystemGpsArmCreatesAnActiveSessionWithoutNmea`, but that new device story was compiled rather than executed in this pass |
| Connected Android tests | PASS (aggregate) | Full run passed 91 and exposed five regressions; only those five were corrected and rerun 5/5, for aggregate 96/96. After the final Output diagnostics change, its directly related Compose test reran 1/1. A second monolithic run was intentionally avoided. |
| Deterministic 10-minute 5 Hz Heading soak | PASS | 3,000–3,001 complete HDT writes, maximum scheduled gap ≤ 400 ms |
| Real-time 10-minute 5 Hz Fake TCP soak | PASS | Final forced uncached execution completed in 13m04s; exactly 3,000 complete non-blank HDT lines and every observed receiver gap ≤ 400 ms |
| In-flight Stop byte barrier | PASS | Stop was held while a real loopback OutputStream was blocked; after release/join, local socket byte count did not increase beyond the count captured at Stop return |
| Loopback stopped zero-byte test | PASS | The same wall-clock test closed the only writer, then observed a real 60-second window with no reconnect and no additional line |
| Real fragile gateway | UNVERIFIED_HARDWARE | Must be tested once, with packet capture; do not repeatedly reconnect |

## Release status

The post-audit full JVM suite passed before the final Stop-barrier/default-route close-out. The final delta then passed its focused 89/89 JVM group plus a fresh `lintDebug` and `assembleDebug`; the long real-time soak was not repeated because 5 Hz scheduling was intentionally unchanged. Connected-device results listed above are earlier aggregate evidence; the new Phone-GPS startup flow has not been rerun on a device in this pass. The work remains an uncommitted candidate on `codex/nmea-publisher-hard-stop`; it has not been merged, pushed or released. Real hardware remains explicitly `UNVERIFIED_HARDWARE`, so perform one controlled fragile-gateway run with packet capture before treating this as a production safety release.
