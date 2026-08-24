# P0 User-Story Reset — Final Engineering Report

## Scope and commits

- Baseline commit: `66112c0efe9d64e5a31541844ad6d40eccb47c6e`
- Working branch: `codex/p0-user-story-reset`
- Intended review target: `codex/develop`
- Final reviewed implementation commit: `320e9d3`
- Implementation commits:
  - `ce30e53` — `fix(p0): restore operable safety workflows`
  - `320e9d3` — `test(p0): cover anchorage regions and trip route states`
- The documentation commit containing this report follows those implementation commits.

The implementation commits changed 50 application/test files. They preserve the pre-existing Room schema and do not introduce a destructive data migration.

## Result

The reported workflows have been reset around explicit ownership and visible outcomes:

1. Set Anchor is always an actionable primary control. A blocker is visible in the foreground; starting a selected source is one bounded operation that waits for a usable fix instead of reading GNSS before starting it.
2. Resume is single-flight. It preserves the paused session, centre, radius and track, waits for the selected source, and returns a visible result rather than encouraging repeated taps.
3. Anchor, Sail and Data root tabs are click-only. Maps, inner instrument pagers, controls and the Watch detail sheet own their own gestures.
4. Normal NMEA Input opens one formal RX connection. A quiet server is a valid `CONNECTED · NO DATA` state; traffic is observed asynchronously and no disposable preflight socket steals a fragile gateway client slot.
5. Phone vessel sensors now use the ordered story: choose bow edge/set vessel zero → secure in the calibrated mount → confirm heading alignment → verify readiness.
6. NMEA Output is one canonical product. TCP client, TCP server, same-socket (advanced) and UDP (advanced) are transport choices for the same feed. The old parallel Sharing switch is no longer restored or exposed. Endpoint testing remains possible before calibration; production Start requires the calibrated vessel frame and never auto-starts after reboot/process restart.
7. Saved Anchorage opens one complete Place surface. One Spot yields one card; multiple Spots yield a list. Approach closes the detail surface, selects Anchor → Current, and missing targets produce visible errors.
8. Map/List controls no longer combine overlapping icon/text layouts. The region selector exposes every stored Region plus an explicit Unassigned bucket for legacy/imported Places.
9. Trip Start owns a per-session Auto / Boat NMEA / Phone GPS choice. It requires a fresh eligible source, persists that choice for the Trip, and does not mutate Anchor GPS or the global Vessel default.
10. Completed Trip history shows a route preview or an explicit loading/no-coordinate/no-map reason instead of silently omitting visualization.

## P0 findings and root causes

The complete evidence ledger is [`CODEX_FINDINGS.md`](CODEX_FINDINGS.md). The central root causes were:

- foreground UI inferred Service outcomes indirectly from Room and notifications;
- parent pagers and the Watch sheet competed for child gestures;
- connection establishment was coupled to immediate NMEA traffic validation;
- RX, output transport, legacy Sharing and phone sensor calibration exposed overlapping ownership paths;
- root tab selection lived only inside Compose, so domain navigation could start invisibly on another tab;
- Trip position recording relied on a global Vessel setting that was not explicit at Start;
- optional/empty map states were represented by returning no UI.

## Regression tests added or strengthened

New test support and cases include:

- `P0OperabilityComposeTest`
  - Set Anchor blocker is visible rather than a no-op;
  - root workspace swipe cannot change sections;
  - Phone GPS Trip is enabled only with an eligible phone candidate;
  - a coordinate-free completed Trip shows an explicit route reason.
- `FakeNmeaInputServer`
  - accepts one formal client, remains quiet, begins traffic later and drops live clients on demand.
- `FakeNmeaOutputReceiver`
  - proves a dedicated TX endpoint is independent from the formal RX endpoint.
- `P0NmeaEndpointStoryTest`
  - one formal quiet RX socket remains the same connection when valid NMEA begins;
  - independent TX never steals or restarts RX.
- `AnchorageRegionFilterPolicyTest`
  - All, assigned and Unassigned filters retain the correct Places.
- `NmeaDeviceOutputPolicyTest`
  - TCP server is part of canonical Output and never claims/reuses the input transport.
- Existing Anchor safety, anchorage approach, source arbitration, NMEA hold/heartbeat, map-source and output-loop tests were updated with the repaired behavior.

## Verification and full gate result

After the implementation pass, the user explicitly requested a local compile. `./gradlew --no-daemon assembleDebug` was therefore run on 2026-08-25 and passed after correcting one Compose-context compile error. Unit tests, lint, emulator, Android connected tests, device commands and real gateway tests were still not run.

| Gate | Result |
|---|---|
| Source/diff review | PASS |
| `git diff --check` | PASS |
| Secret-pattern scan of changed App source | PASS |
| JVM unit tests | NOT RUN — user instruction |
| Android lint | NOT RUN — user instruction |
| Debug assemble | PASS — `assembleDebug`, 2026-08-25 |
| Release assemble | NOT RUN |
| Compose/connected tests | NOT RUN — user instruction |
| Emulator/device | NOT RUN — user instruction |
| Physical NMEA gateway | UNVERIFIED_HARDWARE |

This branch is therefore **Debug-buildable and review-ready, but not release-verified**. It must not be described as fully build-green or hardware-verified until the deferred lint/test/release gates and manual checklist pass.

## Manual QA

Use the fillable [`MANUAL_QA_CHECKLIST.md`](MANUAL_QA_CHECKLIST.md). It contains 25 end-to-end cases covering:

- first-tap Set Anchor and cold GNSS;
- map, inner pager and Watch sheet gesture ownership;
- fragile/single-client NMEA connection, delayed traffic, loss, recovery and explicit disconnect;
- environmental-alert eligibility and recovery;
- physical phone mount/calibration, TCP client and TCP server Output, heartbeat/null holds and raw TX;
- single/multiple saved Spots, approach visibility and region browsing;
- Phone/Boat/Auto Trip sources and route history;
- process death/reboot and alarm lifecycle.

For a physical gateway, record the distinct boat→App RX port and App→boat TX port first. Run RX alone, then dedicated TCP client TX, then the App TCP server with an external client. Save gateway client counts, raw RX/TX, timestamps and a Support Bundle for every failure.

## Hardware-unverified items

- exact one-client/release-delay behavior of the user's marine gateway;
- device compass, GNSS, IMU mount stability and heading alignment on each phone;
- Google Maps gesture arbitration and sheet behavior across the supported device/font matrix;
- background Service/alarm behavior under OEM battery restrictions;
- chartplotter interpretation and source persistence for the canonical heartbeat;
- full route rendering with production Maps credentials and real recorded samples.

## Remaining P1/P2 work

No known P0 item in the ledger remains intentionally open in source. Remaining work is verification-led:

- run the deferred unit/lint/assemble/connected gates and repair any compile or assertion failure without weakening tests;
- complete the physical checklist and convert every failure into a new ledger entry;
- continue localization of runtime-originated English diagnostics after behavior is stable;
- consider a dedicated non-blocking TCP-server endpoint-test result that distinguishes “listener bound” from “a client received data”; current UI states that socket write/bind does not prove remote consumption;
- retire legacy Sharing preference/command storage entirely in a later schema/settings cleanup after upgrade migration has had a release cycle.

## Rollback notes

- Revert `320e9d3` first, then `ce30e53` to return to the baseline behavior.
- Do not partially revert only the Output transport enum: its UI, persistence mapping, runtime connection and legacy Sharing migration are one coherent change.
- No Room downgrade is required for these two commits.
- A user upgrading with the legacy Sharing switch enabled will find it stopped by design. Re-enable the equivalent function explicitly through Data → Output → TCP server after phone vessel-sensor readiness is complete.

## Review disposition

The branch is prepared for review against `codex/develop`; it has not been merged. Release approval is blocked on the deferred automated gate and the manual QA sign-off.
