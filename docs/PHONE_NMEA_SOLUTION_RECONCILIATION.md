# Phone NMEA solution reconciliation

Date: 2026-08-25  
Branch: `codex/nmea-publisher-hard-stop`

This report compares `CODEX_PHONE_NMEA_COMPLETE_PROBLEM_AND_SOLUTION.md` with the current implementation. The attachment was written against an older `main`; findings were accepted only where current source evidence still supported them.

## A. Implementation summary

The normal path is now:

`Phone sensors + explicit App calculations → provenance gate → AnchorWatchNmeaFeedEncoder → independent stream scheduler → bounded latest-per-stream queue → one writer → selected destination`

Boat observations still enter `VesselDataHub` for display, anchoring, sailing and reports, but they have no edge into NMEA Output. Output may query the Hub only to find Phone candidates and explicit `APP_DERIVED` results; it never re-encodes the selected Boat value.

For `SAME_AS_INPUT_CONNECTION`, the writer reuses the already-open formal TCP input socket. It never opens a second connection. Each queued batch binds both the publication generation and the input transport generation. Reconnect invalidates the latter; old data is dropped and diagnosed rather than written onto the new socket.

This existing full-duplex socket is the authoritative and fresh-install Phone→Boat route. Dedicated TCP, TCP server and UDP remain available only as explicit Advanced routes. Previously saved advanced choices are preserved, but a missing/corrupt stored mode no longer falls back to `DEDICATED_TCP`.

Implemented residual fixes from the audit:

- structured shared-socket write result with expected/actual transport generation and failure category;
- generated/attempted/written/dropped packet diagnostics with exact sentence, byte length and reason;
- occurrence-bounded echo quarantine plus a short pre-socket in-flight barrier;
- silent generic-field expiry independent of new traffic;
- correct `PHONE_HEEL`, `PHONE_PITCH`, rudder and `PHONE_BARO` XDR semantics;
- strict ASCII/checksum/CRLF/82-byte validation before socket IO;
- randomized TCP fragmentation regression coverage;
- System GPS Arm startup resource lease and locale-independent foreground result handling.

## B. Main files

- `data/nmea/NmeaConnection.kt`: shared transport generation and structured write result.
- `data/NavigationRepository.kt`: generation-aware write facade.
- `data/nmea/output/NmeaDeviceOutputConnection.kt`: one writer boundary, validation, packet outcomes, and pre-socket/confirmed echo replay firewall.
- `data/nmea/output/NmeaGeneratedSentenceValidator.kt`: final sentence contract.
- `runtime/output/PhonePositionNmeaOutputRuntime.kt`: publication + transport generation on queued batches.
- `data/nmea/NmeaFieldRepository.kt`: timer expiry and XDR pressure semantics.
- `runtime/anchor/AnchorWatchRuntime.kt`: bounded System GNSS startup with temporary resource ownership.
- `ui/watch/AnchorSetupSheet.kt`: GNSS preview and typed Arm failure result.

## C. Timing retained

| Stream | Period | Rate |
|---|---:|---:|
| Position | 1000 ms | 1 Hz |
| Heading | 1000 ms | 1 Hz |
| Motion / ROT / attitude | 1000 ms | 1 Hz |
| Pressure | 1000 ms | 1 Hz |
| Anchor Watch `APP_DERIVED` wind | 1000 ms | 1 Hz |

These Phone/App-owned streams share the 1 Hz product cadence. Boat Depth/STW and direct Boat Position/Heading/Motion/Pressure/Wind are deliberately absent from the publisher. Values due on the same tick are encoded as one contiguous payload and use one socket write/flush. A failed write waits for the next fresh period; no immediate retry loop or stale replay is allowed. A write lasting at least 500 ms gets a full one-second recovery window after it returns, preventing queued periods from becoming a catch-up burst. At three seconds a same-input stall suppresses later TX until an explicit Output Stop/Start; it never closes, replaces or reconnects the safety-owned RX transport. Socket-success counters advance only after actual write/flush succeeds.

NMEA Input and Phone Position Output may run together. Selecting NMEA as the App/Anchor position no longer changes Hub arbitration or disables Phone TX. Position output reads only `VesselPositionRepository.acceptedPhoneFix`; both `SystemLocationRepository` and the encoder reject mock feedback, and the encoder additionally rejects network/coarse providers.

## D. Same-socket reuse

The App-to-boat path in same-socket mode calls `NmeaConnectionManager.writeExpected(...)` on the existing TCP socket. The expected transport generation is checked before the socket is leased and again immediately before bytes are written. An **input reconnect** changes the transport generation and drops every queued old-generation batch. An **output Stop** invalidates the publication generation immediately, waits for an in-flight write to return naturally, and leaves the RX socket and generation untouched. No independent TX client is created for this mode.

## E. Automated evidence

- Deterministic and opt-in real-time Heading soaks now specify 1 Hz complete non-blank HDT with a ≤1,200 ms allowed gap. They were updated with the cadence change and remain to be rerun.
- Cadence policy asserts that healthy writes remain fixed-rate while congested writes cannot produce an immediate catch-up attempt. Backpressure thresholds and exact-generation, single-abort behavior have deterministic JVM regressions; they are written and remain to be run.
- Old transport generation is rejected after reconnect.
- TCP input framing reconstructs identical sentence lists across 300 random fragment seeds.
- Confirmed exact/transformed echoes consume written occurrences; the short pre-socket barrier closes the first-return race and failed write attempts are removed immediately. The real KC-2W/Raymarine dropout was separately confirmed as a 4800-baud hardware bottleneck and resolved at 38400.
- Silent retained fields expire; blank same-source heartbeats retain unchanged values only while live.
- `PHONE_BARO` produces hPa pressure; unrelated angular XDR does not become rudder.
- Generated sentence validator rejects bad checksum/framing, Unicode, embedded newline and oversize frames.
- Localized Arm failures stop the setup spinner; startup and formal Anchor owners hand off System GNSS without a resource gap. A device story now asserts fresh System GPS → real ARM command → ACTIVE Room session with NMEA disconnected; it is compiled into the Android test APK and remains to be executed on the emulator/device.
- A real loopback OutputStream barrier regression proves Stop cannot return while an old writer is still releasable; byte count is stable after return. TCP-server client writers are joined after their sockets close rather than abandoned after a timeout.

## F. External uncertainty

Android-side scheduler, framing and socket ownership are locally verified. The following are **NOT YET PROVEN** and cannot be inferred from loopback tests:

- bytes captured on the user's Pi at the actual boat endpoint;
- Pi stream-parser behavior under its own buffering and forwarding configuration;
- NMEA 0183 → NMEA 2000 converter PGN cadence;
- final Raymarine/Navionics display behavior;
- whether that converter benefits from a non-default combined-XDR compatibility profile.

Perform one controlled hardware run with packet capture. Do not repeatedly reconnect the fragile gateway. Compare Android written diagnostics, Pi RX timestamps, converter output and MFD display before changing the standard separate-XDR default.
