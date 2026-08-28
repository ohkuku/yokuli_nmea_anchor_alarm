# NMEA Publisher P0 verification report

Date: 2026-08-28

Branch: `codex/develop`

Frozen baseline: `fb9d1875e10f06da4fe113c84190bb78f97340e0`

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
- The live output UI shows normal/congested/stalled state plus last/maximum write latency. A three-second same-socket stall suppresses later TX until explicit Stop/Start; it never closes/reconnects RX or changes the RX generation.
- Numeric measurement time and same-source heartbeat time are evaluated separately.
- Position cannot be kept alive by an empty coordinate heartbeat.
- Held Heading remains publishable while the same physical source continues heartbeating.
- Explicit invalid status creates an invalid tombstone and immediately clears the publication lease.
- Every published metric has a last-complete-value lease with a stable source key.
- A READY source switch is atomic and increments the visible source epoch.
- Every queued batch carries a publication generation; Stop invalidates and clears old batches.
- Same-input batches also carry the exact RX/TX transport generation. A batch queued before reconnect is dropped with `STALE_TRANSPORT_GENERATION` and can never cross onto the replacement socket.
- Exact and semantic converter echoes remain occurrence-bounded. A short in-flight barrier exists before socket IO so a first full-duplex replay cannot race ahead of write completion; failed writes release that barrier immediately.
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
| Post-audit transport/framing/echo/field/GPS-policy tests | Historical suite passed; new pre-socket barrier tests not run | Old-transport rejection, randomized TCP fragmentation, occurrence-bounded echo quarantine, silent field expiry, PHONE_BARO semantics, frame validation, localized Arm result and GNSS startup resource hand-off |
| Final focused close-out | PASS | 89/89 across Stop barrier, output/server policy, reconnect generation, field retention, parser/framing, source invalidation and System-GPS startup policy |
| Full Debug unit tests | PASS | Current follow-up run: 620 total, 0 failed/errors, 1 opt-in soak skipped by default. |
| Android lint Debug | PASS | `lintDebug`; HTML report generated at `app/build/reports/lint-results-debug.html` |
| Debug APK assemble | PASS | `assembleDebug`; `app/build/outputs/apk/debug/boat-watch-debug-1.0.0-247a1a70-dirty.apk` |
| Android device-test source compile | PASS | `compileDebugAndroidTestKotlin`; fresh System/NMEA ARM, deterministic WAITING activation and all existing safety stories compile. The System ARM-race story establishes its raw Debug provider precondition directly because the API 34 emulator does not expose host geo injection as a repository-visible non-mock sample. |
| Product identity/free-feature policy | PASS | `.github/scripts/product_policy_guard.sh` |
| API 36 launch smoke | PASS ON RUN 61 | Android 16 / API 36 launch smoke completed successfully in 7m54s for `32abc9a`. The next follow-up must preserve it. |
| Device integration shards 1–3 | FOLLOW-UP PENDING | Run 61: shard 3 passed; shard 1 exposed only the absent emulator GNSS precondition; shard 2 exposed sonar collector ordering plus a cross-table test observation race. Findings P0-049/P1-050 contain the fixes. All three shards must pass on the next push. |
| Connected Android tests | HISTORICAL PASS; CURRENT FOLLOW-UP PENDING | Earlier aggregate execution passed 96/96 plus one Output rerun. Run 61 then passed shard 3 and produced bounded downloadable failure evidence for shards 1/2. The new focused sonar regression passes and Android-test sources compile; GitHub will execute the complete three-shard follow-up. |
| Deterministic 10-minute 1 Hz Heading soak | NOT RUN AFTER CADENCE CHANGE | Updated expectation: 600–601 complete HDT writes, maximum scheduled gap ≤ 1,200 ms |
| Real-time 10-minute 1 Hz Fake TCP soak | NOT RUN AFTER CADENCE CHANGE | Updated expectation: exactly 600 complete non-blank HDT lines and every observed receiver gap ≤ 1,200 ms |
| In-flight Stop byte barrier | PASS | Stop was held while a real loopback OutputStream was blocked; after release/join, local socket byte count did not increase beyond the count captured at Stop return |
| Loopback stopped zero-byte test | PASS | The same wall-clock test closed the only writer, then observed a real 60-second window with no reconnect and no additional line |
| Real fragile gateway | UNVERIFIED_HARDWARE | Must be tested once, with packet capture; do not repeatedly reconnect |

## Release status

The current `codex/develop` candidate restores generation-bound same-input
full-duplex output without restoring the old destructive stall abort. Local
unit, lint, Debug APK, Android-test compilation and product-policy gates pass.
Remote API 36/device-shard results must be recorded after push. Real hardware
remains explicitly `UNVERIFIED_HARDWARE`: perform one controlled KC-2W run with
Raymarine/IS42 and packet capture before treating this as a production safety
release.
