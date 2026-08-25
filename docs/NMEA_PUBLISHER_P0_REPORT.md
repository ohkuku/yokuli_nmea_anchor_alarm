# NMEA Publisher P0 verification report

Date: 2026-08-25  
Branch: `codex/nmea-publisher-hard-stop`

> 2026-08-26 compatibility update: every normal stream now uses a 1 Hz
> heartbeat and values due on the same tick are coalesced into one wire
> payload/write/flush. The older 5 Hz results below are retained only as
> historical evidence and do not verify the new cadence; updated tests are
> written but have not yet been run.

## Fixed behavior

- Phone Position, Phone Heading, Phone Motion, Phone Pressure and App-derived Wind use one common 1 Hz product cadence; unchanged valid values remain complete heartbeats.
- Direct Boat Position/Heading/Motion/Pressure/Wind and Boat Depth/STW are input-only and cannot be published by any output transport.
- Starting Phone/App output does not alter `VesselDataHub` source arbitration. The App or a paused Anchor session may use NMEA position while TX independently uses only a real non-mock Android GNSS fix; the global NMEA GPS proxy therefore cannot loop back as Phone position.
- Everything due in a cadence tick is coalesced into one contiguous Socket payload/write/flush. A blocked writer retains only the newest per-stream value.
- A slow write cannot cause catch-up traffic: crossing 500 ms grants a one-second post-completion recovery period while pending values continue replacing in place.
- The live output UI shows normal/congested/stalled state plus last/maximum write latency. A three-second same-socket stall aborts only that transport generation once; the existing bounded RX reconnect policy owns recovery.
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
- The recommended/default full-duplex same-input Socket, plus explicitly selected Advanced TCP client/server and UDP modes, consume the same Phone/App-owned scheduler.
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
| Deterministic 10-minute 1 Hz Heading soak | NOT RUN AFTER CADENCE CHANGE | Updated expectation: 600–601 complete HDT writes, maximum scheduled gap ≤ 1,200 ms |
| Real-time 10-minute 1 Hz Fake TCP soak | NOT RUN AFTER CADENCE CHANGE | Updated expectation: exactly 600 complete non-blank HDT lines and every observed receiver gap ≤ 1,200 ms |
| In-flight Stop byte barrier | PASS | Stop was held while a real loopback OutputStream was blocked; after release/join, local socket byte count did not increase beyond the count captured at Stop return |
| Loopback stopped zero-byte test | PASS | The same wall-clock test closed the only writer, then observed a real 60-second window with no reconnect and no additional line |
| Real fragile gateway | UNVERIFIED_HARDWARE | Must be tested once, with packet capture; do not repeatedly reconnect |

## Release status

The earlier full JVM/lint/assemble evidence predates the 2026-08-26 cadence/coalescing change. Tests for the new 1 Hz contract are written but deliberately not run in that pass. Connected-device results listed above are historical aggregate evidence. The work remains an uncommitted candidate on `codex/nmea-publisher-hard-stop`; it has not been merged, pushed or released. Real hardware remains explicitly `UNVERIFIED_HARDWARE`, so perform one controlled fragile-gateway run with packet capture before treating this as a production safety release.
