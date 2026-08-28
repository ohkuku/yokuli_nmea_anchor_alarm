# NMEA Publisher P0 fact audit

Date: 2026-08-25  
Branch: `codex/nmea-publisher-hard-stop`  
Scope: live NMEA publication only. This audit does not reopen Anchorage GIS work.

## Product invariant

There is one normal-product publisher: `AnchorWatchNmeaPublisher`. It uses `VesselDataHub` only to locate provenance-proven Phone sensor candidates and explicit `APP_DERIVED` results, then sends that same Phone/App-owned feed to every transport. Direct Boat measurements are never a publication input, whether raw or re-encoded.

## Runtime inventory

| Runtime | Owner | Purpose | Normal product state |
|---|---|---|---|
| `AnchorWatchNmeaPublisher` | `YokuliRuntimeCoordinator` | Phone/App-owned stream schedule, metric leases, bounded latest-value queue, publication generation | Active only after explicit Start |
| `NmeaDeviceOutputConnection` | `AnchorWatchNmeaPublisher` | Destination lifecycle, packet-path diagnostics, hard-stop write lease | One instance |
| `NmeaSharingServer` | `NmeaDeviceOutputConnection` | TCP-server destination only | Never an independent Sharing publisher |
| `DedicatedNmeaTcpClient` | `NmeaDeviceOutputConnection` | Write-only TCP-client destination | Owns connecting + connected sockets |
| `NmeaUdpClient` | `NmeaDeviceOutputConnection` | UDP unicast/broadcast destination | One socket |
| `NavigationRepository` / `NmeaConnection` | NMEA Input | Formal RX and authoritative/default same-socket Phone→Boat write | Not a second publisher |

There is no production `NmeaSharingRuntime`, no raw Boat repeater, and no collector from `NavigationRepository.validRawSentences` to an output runtime. `NmeaFieldRepository` legitimately consumes that raw flow for typed input discovery only.

## Socket ownership

| Socket | Sole owner | Start | Stop / interruption |
|---|---|---|---|
| Formal TCP/UDP RX | `NmeaConnection` | Explicit NMEA Input connect | NMEA Input disconnect/generation replacement |
| Dedicated TX candidate | `DedicatedNmeaTcpClient.connectingSocket` | Publisher writer actor | `close()` immediately closes candidate |
| Dedicated TX connected socket | `DedicatedNmeaTcpClient.connected socket` | Candidate promotion after connect and ownership check | `close()` immediately closes active socket |
| TCP output listener + clients | `NmeaSharingServer` as destination | Publisher config selects TCP server | Hard Stop closes listener/clients and joins old writer jobs |
| UDP TX | `NmeaUdpClient` | First Phone/App feed write | Hard Stop closes socket |
| Same-input TCP write | Existing `NmeaConnection` | Recommended/default route after explicit Output setup and Start | Stop blocks on the publisher byte barrier until the old write returns; it does not open a second socket |

## Configuration ownership

- `OutputSettingsRepository` persists destination/format preferences but keeps the live `publicationEnabled` lease in memory. A fresh/invalid legacy route resolves to `SAME_AS_INPUT_CONNECTION`; a valid saved independent route is preserved. Restart never auto-starts output.
- `NmeaPublisherConfig` is the only live publisher configuration model. Legacy per-phone flags, BACKUP policies, proprietary status and auto-start are removed at its boundary.
- `SettingsRepository.nmeaSharingEnabled/nmeaSharingPort` are migration-only. `YokuliRuntimeCoordinator` migrates a legacy port to a stopped TCP-server destination and immediately clears the legacy enabled flag.
- `YokuliRuntimeCoordinator` is the only production runtime owner. `MainViewModel` saves intent and sends `REFRESH_PHONE_SENSOR_OUTPUT`; it does not call `configure()` or `stop()` for normal Start/Stop.
- `testOutput` and `testKnownGoodHdg` are explicit diagnostic sessions and refuse to run while normal publication is active.

## Start and Stop entry points

1. Settings → NMEA Output → Start.
2. `MainViewModel.startNmeaOutput()` checks calibration and endpoint policy, saves the in-memory publication lease, and asks the foreground coordinator to refresh.
3. The coordinator serially evaluates readiness and calls `AnchorWatchNmeaPublisher.configure()`.
4. Configure starts a new monotonic `publicationGeneration`, new `sessionId`, clears old queue/leases, configures exactly one destination and acquires runtime resources.

Stop follows the reverse path. The generation is invalidated, pending batches are discarded, independently-owned Dedicated/UDP sockets are closed to interrupt IO, the lifecycle write lock waits for old writers, Live TX is cleared, and only then status becomes `OFF`. For same-input TCP, Stop deliberately does not close the socket and may remain `STOPPING` until the in-flight write returns naturally.

The generation check is not treated as the hard-stop proof. Every call to `NmeaDeviceOutputConnection.write()` holds `NmeaOutputStopBarrier` for the complete network operation; Stop obtains the exclusive side before returning. `PhoneNmeaOutputStopBarrierTest` blocks a real loopback OutputStream, starts Stop, and proves Stop cannot return until the old write has joined and its local socket byte count is frozen. TCP-server Stop closes all listener/client sockets and joins every client writer without converting a timeout into a false OFF acknowledgement.

NMEA dependency shutdown is also owned inside `YokuliRuntimeCoordinator`; it saves the stopped lease and invokes the same publisher hard-stop path before releasing NMEA Input.

## Sentence generation paths

### `LOCAL_SENSOR_INJECTION`

`VesselDataHub` → `AnchorWatchNmeaFeedEncoder` → `LatestPerStreamQueue` → `NmeaDeviceOutputConnection` → selected destination.

- Position: 1 Hz from one atomic Android GNSS fix; RMC/VTG SOG and COG can never come from Boat input.
- Heading: 1 Hz from the calibrated Phone vessel-heading source, with fixed outward `IIHDT` / optional `IIHDG` identity.
- Motion/attitude: 1 Hz from the mounted Phone IMU.
- Pressure: 1 Hz from the Phone barometer when enabled and complete.
- True wind: 1 Hz only when the result carries explicit Anchor Watch `APP_DERIVED` identity and derivation provenance. A direct Boat MWD/MWV/VWT observation is never published.
- Depth and speed through water are not publisher streams. Boat DBT/DPT/VHW is input-only on every transport.

Changing between same-socket, dedicated TCP, TCP server or UDP changes only the byte destination. It never changes source eligibility and cannot turn the App into a selected Boat-data fan-out.

### `DIAGNOSTIC_TEST`

Explicit stopped-only endpoint test → known complete diagnostic sentence(s) → chosen destination → automatic hard stop.

### Disabled paths

- `LEGACY_SHARING`: no runtime consumer.
- `RAW_REPEATER`: no runtime consumer.
- Raw Boat forwarding: absent.

## Source freshness and publication leases

`VesselSourceCandidate` exposes separate numeric measurement and physical-source heartbeat times. The Phone/App publication boundary applies metric-specific rules:

- Position: numeric freshness only.
- Phone Heading/Pressure/Motion and explicit App-derived Wind: a complete value may remain `HELD` while the exact owning source heartbeat is fresh.
- `INVALID` / `DISABLED`: immediate rejection, regardless of heartbeat.

`PhoneAppNmeaMetricLeaseBank` is the internal lease primitive and separately owns the last complete published value, numeric time, heartbeat time, expiry and stable source key. Only Phone/App-owned observations can reach it from the live encoder. A READY local-source switch replaces the lease in one tick; no blank transition sentence is generated.

## Stream scheduling and queueing

`AnchorWatchNmeaHeartbeat` runs every normal product stream at 1 Hz. The writer uses one bounded latest-value slot per stream, then coalesces everything due on that tick into one contiguous socket payload and one flush. A blocked gateway therefore keeps only the newest value of each family and never replays stale batches after reconnect. A write that takes at least 500 ms gets a full one-second recovery period after completion, so missed periods collapse in place instead of becoming a catch-up burst. Every batch carries its publication generation, source stable key and—when reusing input TCP—the exact transport generation. The shared socket rejects old-generation batches after reconnect.

The scheduler advances at the normal attempt cadence, not on immediate retry. Successful socket time is recorded separately. This is deliberate for a fragile gateway: a failed write waits for the next 1 Hz fresh batch, never a tight failure loop.

The live transport watchdog labels an in-flight write `CONGESTED` at 500 ms. At three seconds it marks same-input TX `STALLED` and suppresses later output admission until an explicit Stop/Start. It never closes or reconnects RX, never increments the RX generation, and never creates an independent Boat client. The TX UI exposes current backpressure and last/maximum write duration.

## Packet-path diagnostics

`NmeaPacketPathDiagnostic` records:

- publisher session and generation;
- Phone/App publisher, legacy, diagnostic or raw path;
- generated/write-start/queued/written/source-changed stage;
- stream, transport, destination and sentence type;
- generated/write times and source stable key.
- normalized generated sentence, byte length, input transport generation, outcome and failure reason.

Before a frame reaches any destination, `NmeaGeneratedSentenceValidator` requires one printable-ASCII sentence, a valid checksum, CRLF termination, no embedded newline and at most 82 bytes.

## Reconciliation with `CODEX_PHONE_NMEA_COMPLETE_PROBLEM_AND_SOLUTION.md`

The supplied analysis inspected an older `main` architecture. It was used as an audit checklist, not applied as a patch:

- Already resolved in the current publisher: independent stream clocks, one production runtime owner, Phone/App-owned value encoding, no selected-Boat re-encoder or raw repeater, publication generation, hard Stop and real GNSS-based declination validity.
- Adopted from the audit: input transport generation on queued writes, structured same-socket write failures, per-packet dropped diagnostics, occurrence-bounded echo quarantine with a pre-socket in-flight barrier, time-driven field expiry, PHONE_BARO XDR semantics, strict output validation and randomized TCP framing coverage.
- Current compatibility contract: every normal stream is 1 Hz. Unchanged valid Heading is still resent as one complete sentence every second; change-only output is forbidden.
- Intentionally retained: retry on the next normal stream period. Immediate failure retry would recreate a connection/write storm; attempted and written clocks/counters remain separate.
- Not enabled as a default: combined attitude+pressure XDR packing. Separate complete standard XDR frames remain the product output. Converter-specific combined packing needs one controlled Pi/MFD A/B capture and must also remain within the 82-byte sentence limit.

Data → NMEA Output displays the live session/generation, per-stream source epoch/rates/ages/suppression, and recent packet-path events. Stop clears Live TX while preserving separately labelled last-session history.

## Legacy inventory

The following remain only for migration/backward-compatible storage and are not read by the live publisher:

- `phonePositionEnabled`
- `phoneHeadingEnabled`
- `phoneMotionEnabled`
- `phonePressureEnabled`
- `positionPolicy`, `headingPolicy`, `motionPolicy`, `pressurePolicy`, `derivedWindPolicy`
- `purpose`
- `proprietaryStatusEnabled`
- `autoStartOutput`
- `nmeaSharingEnabled`, `nmeaSharingPort`

## Verification truth

Targeted JVM Source/Lease/Publisher/Socket tests passed during implementation. The final close-out reran 89 focused JVM tests plus `lintDebug` and `assembleDebug`. Aggregate full unit, aggregate connected-device tests, the forced real-time 10-minute Fake TCP Heading soak and the following 60-second stopped window also pass from the immediately preceding audit stage; exact evidence is recorded in `NMEA_PUBLISHER_P0_REPORT.md`. Physical fragile-gateway verification remains a separate controlled QA action and must never be inferred from loopback tests.
