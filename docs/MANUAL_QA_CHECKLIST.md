# Anchor by Yokuli — P0 Manual QA Checklist

> Build/commit: ____________________  Date: __________  Tester: __________
>
> Phone / Android: ____________________  Boat gateway / firmware: ____________________
>
> Network topology (Wi‑Fi/AP, RX port, TX port, single-client?): __________________________________

This pass deliberately did **not** run an emulator, device, unit, lint or Gradle build gate. Complete the safety-critical cases below on the intended hardware before treating the build as verified. For every failure, keep the session open when safe, export a Support Bundle, take a screenshot/screen recording, and enter the actual time.

Result values: `PASS / FAIL / BLOCKED / NOT RUN`.

## A. Cold start and Anchor primary action

### QA-P0-001 — First Set anchor tap is never a no-op

1. Force-stop the App; enable precise location and notifications.
2. Open Anchor → Current and wait for a live System GPS fix.
3. Tap **Set anchor** exactly once.
4. Verify Watch Preflight opens. Continue, complete required fields, then tap **Start anchor watch** exactly once.
5. Verify the button shows progress and one active session appears. If it cannot start, verify the same foreground screen gives the exact reason and retains all form values.

- Expected: one tap → one visible transition/result; no notification-only failure; no duplicate session.
- Result: __________
- Actual / timestamp: ________________________________________________________________
- Screenshot / bundle: _______________________________________________________________
- Notes: _____________________________________________________________________________

### QA-P0-002 — Start while GNSS is cold

1. Force-stop, disable then re-enable Location, and immediately open Set anchor.
2. Submit a valid setup before the first GNSS fix arrives.
3. Wait up to 15 seconds without tapping again.

- Expected: progress remains single-flight; the first fresh precise GNSS fix starts the session, or a visible 15-second failure retains the setup.
- Result / feedback: _________________________________________________________________

### QA-P0-003 — Visible primary blockers

Repeat Set anchor while (a) settings are still loading and (b) a Trip Watch is active.

- Expected: the primary button remains tappable and explains the required action; it never looks broken.
- Result / feedback: _________________________________________________________________

## B. Anchor map and sheet gesture ownership

### QA-P0-004 — Map pan/zoom/marker gestures

1. On Anchor → Current, drag horizontally/vertically, pinch zoom, rotate, tap saved markers and drag a measurement pin.
2. Repeat with map lock on and off.

- Expected: the root stays on **Current**; no drag changes to History/Anchorages. Locked mode accepts gestures and returns to the boat while preserving the adjusted scale; unlocked mode stays where browsed.
- Result / feedback: _________________________________________________________________

### QA-P0-005 — Watch details sheet

Drag the Watch sheet handle upward and downward; scroll all details while expanded.

- Expected: sheet expands/collapses; map gestures outside the sheet still work; controls remain reachable.
- Result / feedback: _________________________________________________________________

### QA-P0-006 — Other root workspaces

Swipe horizontally over Sail instruments and Data controls.

- Expected: inner Sail instrument pages may swipe; Anchor/Sail/Data root sections change only by tapping their tabs.
- Result / feedback: _________________________________________________________________

## C. NMEA input and paused Anchor recovery

Record real gateway directions before testing:

- Boat/server → App RX host/port: ____________________
- App → boat/server TX host/port: ____________________
- Same socket explicitly supported? `YES / NO / UNKNOWN`

### QA-P0-007 — Formal NMEA input on a fragile/single-client gateway

1. Ensure no old App/client owns the RX socket.
2. Data → Input: enter host and RX port; tap **Save & connect input** once.
3. Observe Connecting → Connected/no data or Receiving. Do not use Output yet.
4. Check gateway client count if available.

- Expected: one formal RX client only; no disposable test connection; quiet valid socket remains connected; invalid host/port shows inline validation and opens no socket.
- Result / accepted client count: _____________________________________________________

### QA-P0-008 — Delayed traffic and automatic NMEA default

1. Connect while the server is quiet, then begin valid RMC/GGA traffic.
2. Do not touch the GPS source selector during the wait.

- Expected: connection remains the same generation; after the first usable position, idle Anchor default becomes NMEA. Explicitly tapping System during the wait cancels automatic promotion.
- Result / feedback: _________________________________________________________________

### QA-P0-009 — Pause, disconnect, recover, resume once

1. Start a NMEA Anchor session and note session ID/centre/radius/track count.
2. Pause; disconnect NMEA; restart or reconnect the fragile gateway.
3. Tap Reconnect once, wait for a fresh valid position, then tap Resume exactly once.

- Expected: Resume shows progress and cannot be tapped repeatedly; up to 15–30 seconds is allowed according to profile; same session ID, centre, radius and earlier track remain; one success/failure result appears in foreground.
- Result / before-after values: _______________________________________________________

### QA-P0-010 — NMEA loss during active watch

Interrupt RX while a NMEA Anchor session is active.

- Expected: Anchor session stays open; immediate high-priority NMEA-loss notification plus in-App recovery card; GPS data-loss alarm follows its timeout; user may Pause → reconnect/configure/switch to Phone GPS → Resume without re-anchoring.
- Result / alarm timing: ______________________________________________________________

### QA-P0-011 — Explicit disconnect really stops input

With no feature owning NMEA, tap Stop input and watch Raw & health for at least 30 seconds.

- Expected: RX closes and counters/raw input stop. If a feature owns NMEA, a decision dialog identifies it and Disconnect does not pretend to succeed.
- Result / feedback: _________________________________________________________________

## D. Environmental alerts

### QA-P0-012 — No new alert without its exact live instrument

During an active non-Demo Anchor session, disconnect NMEA and open Condition alerts.

- Expected: disabled Depth/Wind/Wind-shift guards cannot be newly enabled; an already-enabled guard may be switched off; stale/held data is labelled and cannot authorize a new guard; Runtime rechecks the same rule.
- Result / feedback: _________________________________________________________________

### QA-P0-013 — Resume with configured guard data missing

Pause a session with an enabled guard, remove the instrument, then resume core GPS monitoring.

- Expected: UI explains missing instruments; core Anchor may resume, configured guard enters its defined audible data-loss state after grace period, and user can explicitly disable it.
- Result / feedback: _________________________________________________________________

## E. Phone vessel sensor → NMEA output story

### QA-P0-014 — Ordered calibration

On a real mounted phone, open Settings → Phone vessel sensors and perform only in order:

1. Choose bow edge, secure the phone, set vessel zero.
2. Confirm **Fixed to the vessel**.
3. Enter/confirm measured true-heading alignment.
4. Verify the final readiness card changes to ready.

- Expected: every step explains purpose and prerequisite; moving/picking up the phone suspends vessel-frame eligibility; uncalibrated/unaligned phone never becomes a boat heading source.
- Result / sensor model / feedback: __________________________________________________

### QA-P0-015 — Endpoint test vs production Start

1. Before calibration, configure a separate TX endpoint and run endpoint test.
2. Verify endpoint test may send its diagnostic, but **Start sharing vessel data** remains blocked.
3. Complete QA-P0-014; start sharing once.

- Expected: production start requires zero + vessel mounted + heading alignment; one complete canonical feed is sent; no automatic output start after App restart.
- Result / feedback: _________________________________________________________________

### QA-P0-016 — Independent RX/TX and heartbeat

Run this twice: first use **TCP client** with different RX and TX ports; then stop it, choose **TCP server**, connect one chartplotter/tablet client to the displayed phone address, and observe both for 2 minutes including unchanged heading/depth.

- Expected: RX stays connected; TCP client connects only to the selected TX endpoint; TCP server is the same canonical Output product rather than a second Sharing switch; unchanged usable fields remain present at heartbeat cadence; null/change-only input does not erase held values; stopping output closes only its client/listener and does not stop RX; Raw output shows generated and transport-written lines.
- Result / receiver evidence: ________________________________________________________

## F. Saved Anchorage library and approach

### QA-P0-017 — Single-Spot Place

Open a saved Place containing one Spot from both Map and List.

- Expected: one complete dialog opens directly on one actionable Spot card; no preview/details duplicate; generic stored “Main spot” is displayed as **Primary anchoring spot**; Google Maps and QR work.
- Result / feedback: _________________________________________________________________

### QA-P0-018 — Multi-Spot Place

Open a Place with at least two Spots.

- Expected: one dialog shows a list of distinct Spot cards; each Approach action uses that exact coordinate; no implicit first-Spot selection.
- Result / feedback: _________________________________________________________________

### QA-P0-019 — Approach is visible and reachable

Start Approach from Anchorages and separately from History.

- Expected: details close; App returns to Anchor → Current; Watch sheet collapses enough not to cover guidance; target/distance/bearing/heading-mode/cancel are unmistakably visible. A missing/deleted target gives foreground error, never silence.
- Result / feedback: _________________________________________________________________

### QA-P0-020 — Map/List controls and region browsing

Test smallest supported screen width and 1.3× font scale. Open region selector with classified and unclassified saved Places.

- Expected: Map/List labels never overlap; all database regions are listed; **Unassigned places** remains browsable; Map and List use the same saved Place repository.
- Result / feedback: _________________________________________________________________

## G. Trip Watch source and history

### QA-P0-021 — Phone GPS trip

1. Sail → Start Trip; select **Phone GPS**.
2. Turn NMEA off; wait for Android GNSS readiness; start.
3. Record movement and end.

- Expected: Start is blocked until fresh Android GNSS; session stores `PHONE`; samples show phone position; Anchor GPS and Data → Vessel global default are unchanged after ending.
- Result / sample source: _____________________________________________________________

### QA-P0-022 — Boat NMEA and Auto trips

Repeat using Boat NMEA, then Auto; interrupt and restore boat traffic during Auto.

- Expected: Boat choice requires eligible Boat position; Auto shows the currently selected source and uses deterministic Hub arbitration; recorded source changes/gaps are visible in report timeline.
- Result / feedback: _________________________________________________________________

### QA-P0-023 — Trip route history

Open a completed Trip in History.

- Expected: expanded card immediately shows route preview; Report and Replay add detail. A trip with no usable coordinates says so explicitly. A build without Maps reports that the route exists and keeps exports available.
- Result / feedback: _________________________________________________________________

## H. Restart, background and destructive regression

### QA-P0-024 — Process death/reboot ownership

Repeat process kill during (a) active Anchor, (b) paused Anchor, (c) active Trip, (d) NMEA output.

- Expected: Anchor/Trip restore their durable session safely; paused remains paused; output never auto-starts; no duplicate RX/TX clients; visible health identifies recovery state.
- Result / feedback: _________________________________________________________________

### QA-P0-025 — Alarm lifecycle regression

Trigger test alarm and real Demo radius alarm; verify Acknowledge/snooze, adjust range, Pause and Lift.

- Expected: test controls remain visible above the UI; stop always silences; Pause/Lift/range-safe state cancels inappropriate ringing; snooze re-reminds rather than permanently suppressing a continuing danger.
- Result / feedback: _________________________________________________________________

## Final hardware gate

- All P0 cases passed: `YES / NO`
- Open failure IDs: _________________________________________________________________
- Support bundles attached: _________________________________________________________
- Safe to merge/release: `YES / NO`
- QA sign-off: ____________________  Date/time: ____________________
