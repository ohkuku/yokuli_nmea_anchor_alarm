# Codex Finding Ledger

## Baseline lock

- Base commit: `66112c0efe9d64e5a31541844ad6d40eccb47c6e`
- Working branch: `codex/p0-user-story-reset`
- Intended integration target: `codex/develop`
- Room schema: `20` (`exportSchema = true`)
- Backup format: `5`
- Initial working tree: 32 modified files and 5 untracked files carried forward from the preceding stabilization pass. They were preserved on the dedicated branch; no reset or overwrite was performed.
- Baseline unit tests: **NOT RUN — user explicitly requested no local/device/emulator test execution for this pass.**
- Baseline lint: **NOT RUN — same instruction.**
- Baseline assemble: **NOT RUN — same instruction.**
- Evidence method for this pass: source trace, deterministic regression tests written but not executed, static diff inspection, and a fillable manual QA plan.

## Finding P0-001 — Anchor primary action can appear to do nothing

- Severity: **P0 / safety-critical operability**
- User story: Anchor → Set anchor → Preflight → Setup → Start must either create the session or show the exact blocker in the same foreground UI.
- Evidence: `WatchBottomSheet.kt` silently disables Set Anchor for `!settingsReady` or an active Trip. `AnchorSetupSheet.kt` sends a Service intent and infers failure only from a five-second Room timeout. `AnchorWatchRuntime.arm()` reports its real rejection only through a system notification, which the setup sheet does not observe.
- Reproduction steps: open Anchor with an active Trip or submit setup while the selected position becomes stale; tap the primary action; observe either a disabled control or a generic delayed error with no runtime reason.
- Root cause: UI and runtime command-result ownership are disconnected; the UI guesses completion from Room state and primary blockers are represented as disabled controls.
- Failing test: `P0OperabilityComposeTest.setAnchorPrimaryActionShowsAVisibleBlockerInsteadOfDoingNothing`; runtime start coverage remains in `AnchorSafetyFlowTest.backdownStartsWithTemporaryBoundaryAndProvisionalCentre`.
- Fix commit: **`ce30e53`**
- Verification result: **Source trace and `git diff --check` passed. `P0OperabilityComposeTest.setAnchorPrimaryActionShowsAVisibleBlockerInsteadOfDoingNothing` was added but NOT RUN by user instruction.**
- Real hardware verified: **No — UNVERIFIED_HARDWARE.**
- Status: **FIXED IN CODE — AWAITING AUTOMATED + HARDWARE QA**

## Finding P0-002 — Root pagers steal map, instrument and form gestures

- Severity: **P0 / core interaction broken**
- User story: maps own pan/zoom/marker gestures; Sail instrument pages own their swipe; sliders/forms own horizontal gestures; root sections change only through tabs.
- Evidence: `WorkspaceRoots.kt` leaves Anchor and Sail root `HorizontalPager.userScrollEnabled` at the default `true`; `DataScreen.kt` does the same for Data. The previous workaround sets `BottomSheetScaffold.sheetSwipeEnabled=false`, which also removes the expected upward detail gesture.
- Reproduction steps: horizontally drag Anchor map, swipe an inner Sail instrument page, edit/drag Data controls, and drag the Watch sheet handle.
- Root cause: parent navigation owns the same gesture axes as interactive children; the sheet workaround disabled the desired child interaction instead of removing the parent owner.
- Failing test: `P0OperabilityComposeTest.rootWorkspaceSwipeDoesNotChangeSection`; the full map/inner-pager/sheet matrix is intentionally listed for hardware QA.
- Fix commit: **`ce30e53`**
- Verification result: **Source trace confirms all Anchor/Sail/Data root pagers are click-only and the Watch sheet owns its vertical drag. `P0OperabilityComposeTest.rootWorkspaceSwipeDoesNotChangeSection` was added but NOT RUN.**
- Real hardware verified: **No — required manual Google Maps gesture matrix is listed in `docs/MANUAL_QA_CHECKLIST.md`.**
- Status: **FIXED IN CODE — AWAITING GESTURE QA**

## Finding P0-003 — Paused NMEA Anchor recovery has no single-tap progress/result contract

- Severity: **P0 / safety recovery**
- User story: a paused NMEA-backed session keeps centre/range/track, reconnects or switches source, and resumes once with visible progress and outcome.
- Evidence: `WatchBottomSheet.kt` leaves Resume continuously tappable. `AnchorWatchRuntime.resume()` can wait up to ten seconds for connection and fix, but exposes progress/failure only by notification. Repeated taps enqueue repeated runtime commands.
- Reproduction steps: pause an NMEA session, stop/interrupt the server, restore it, then tap Resume; there is no in-page pending state and users naturally tap again.
- Root cause: no idempotent foreground command-state contract between ViewModel/UI and serialized runtime command actor.
- Failing test: `AnchorSafetyFlowTest.activeWatchCanBePausedDisconnectedAndResumedAsTheSameSession`; delayed recovery is covered by `FakeNmeaInputServer` plus QA-P0-009.
- Fix commit: **`ce30e53`**
- Verification result: **Runtime/UI source trace confirms one pending Resume command, 15–30 second bounded source wait, exact foreground failure, and preserved paused session. Fake endpoint/manual cases were written but NOT RUN.**
- Real hardware verified: **No — fragile gateway release/reconnect is UNVERIFIED_HARDWARE.**
- Status: **FIXED IN CODE — AWAITING FRAGILE-GATEWAY QA**

## Finding P0-004 — Normal NMEA Input Connect still behaves like a traffic test

- Severity: **P0 / connectivity**
- User story: Save & Connect performs local validation, saves RX profile, opens one formal socket, and immediately presents Connecting / Connected no data / Receiving.
- Evidence: although the disposable preflight socket was removed, `MainViewModel.saveAndConnect()` still waits 10–30 seconds for a valid sentence/fix and labels the operation `TESTING`. Input fields persist an empty port as `0`; fresh installs still default RX to `192.168.1.100:10110`.
- Reproduction steps: connect to a single-client quiet server; the formal socket opens but the UI remains in an endpoint-test flow. Clear the port field and observe model mutation to `0`.
- Root cause: transport connection and traffic validation remain coupled in the ViewModel; form draft and persisted model are the same object; dangerous legacy defaults are also used for fresh DataStore.
- Failing test: `P0NmeaEndpointStoryTest.formalInputOwnsOneSocketWhileQuietThenReceivesLater`; `AnchorSafetyFlowTest.nmeaInputAndOutputKeepReceiveAndSendPortsOnSeparateTopLevelPages`.
- Fix commit: **`ce30e53`**
- Verification result: **`P0NmeaEndpointStoryTest.formalInputOwnsOneSocketWhileQuietThenReceivesLater` and the draft-form regression were written. NOT RUN.**
- Real hardware verified: **No — RX/TX gateway port direction remains UNVERIFIED_HARDWARE.**
- Status: **FIXED IN CODE — AWAITING AUTOMATED + GATEWAY QA**

## Finding P0-005 — Phone vessel sensor and production-share sequence is unclear

- Severity: **P0 / unsafe publication UX**
- User story: explain purpose → secure mount → calibrate attitude → align true bow heading → verify readiness → configure/test endpoint → explicitly start one complete feed.
- Evidence: readiness, mount state, heading alignment, source routing and output transport are distributed across Settings and Data. Runtime start has a partial gate, but the UI does not present one linear completion checklist or a single next action.
- Reproduction steps: fresh install → Settings → Phone vessel sensors and Data → Output; attempt to determine which action comes first and what “ready” means.
- Root cause: engineering capabilities are exposed as peer controls instead of a staged vessel-frame calibration story; secondary Output status lacks a clear owner link.
- Failing test: `NmeaDeviceOutputPolicyTest.firstUseCannotEnableStreamsBeforeTransportChoice`; `tcpServerIsOneCanonicalOutputTransportAndDoesNotReuseInput`; `P0NmeaEndpointStoryTest.dedicatedOutputReceiverIsIndependentFromFormalInput`.
- Fix commit: **`ce30e53`**
- Verification result: **Source trace confirms numbered calibration, runtime calibration gate, canonical heartbeat output, TCP client/server/UDP transport ownership, independent endpoint test and raw TX. Policy/fake-receiver tests were written but NOT RUN.**
- Real hardware verified: **No — sensor mounting/alignment requires physical QA.**
- Status: **FIXED IN CODE — AWAITING SENSOR + RECEIVER QA**

## Finding P0-006 — Saved Anchorage navigation starts silently and page ownership is unclear

- Severity: **P0 / navigation operability**
- User story: select a saved Place/Spot → tap Approach → automatically return to Anchor Current, collapse details, and show unmistakable active guidance with target and cancel action.
- Evidence: `MainViewModel.startAnchorageApproach()` sets `page=0` but has no Anchor-root selected-tab authority; `AnchorRootPage` keeps its own pager state. Therefore an Approach started from History/Anchorages can leave the user on the previous root tab while guidance is active on Current. Place preview also only approaches a single spot and exposes generic “Main spot” naming.
- Reproduction steps: Anchor → Anchorages/History → saved place → Approach. Observe runtime approach state without deterministic navigation to Current.
- Root cause: root-tab state is local Compose state, not an app navigation destination; approach action changes only the top-level page.
- Failing test: `AnchorageApproachStoryTest.onlySavedAnchoragesCreateNearbyApproachAndArrivalGeometry`; `multipleNearbyClustersRequireAnExplicitTargetAndNeverAutoSwitch`; foreground navigation remains in the manual QA matrix.
- Fix commit: **`ce30e53`**
- Verification result: **Navigation state now owns Anchor/Current selection, closes the selected card and surfaces missing-target failures. Existing and updated Compose/story tests were NOT RUN.**
- Real hardware verified: **No — live compass/GNSS guidance remains manual QA.**
- Status: **FIXED IN CODE — AWAITING LIVE APPROACH QA**

## Finding P1-007 — Anchorage Map/List and region browsing are visually and semantically fragile

- Severity: **P1 / major UX and data discoverability**
- User story: Map/List use the same repository-backed Place set; compact controls do not overlap; region selector contains meaningful assigned regions and an explicit Unassigned bucket.
- Evidence: the segmented control renders icon and text inside equal-width buttons without compact layout handling. Region dialog filters to `place.primaryRegionId`, so legacy/imported/failed-classification Places make the selector show only All regions even though Regions exist. Viewport fallback also makes Map/List content depend on camera state in a surprising way.
- Reproduction steps: use a narrow device/font scale; save/import a Place without `primaryRegionId`; open Browse region.
- Root cause: UI filters region metadata through only one optional FK and has no unassigned classification; compact layout lacks width policy.
- Failing test: `AnchorageRegionFilterPolicyTest.allRegionsKeepsClassifiedAndUnassignedPlaces`; `unassignedBucketDoesNotHideLegacyOrImportedPlaces`; narrow-width rendering remains manual QA.
- Fix commit: **`ce30e53`, `320e9d3`**
- Verification result: **Compact Map/List controls and an explicit Unassigned bucket are implemented. `AnchorageRegionFilterPolicyTest` was added but NOT RUN.**
- Real hardware verified: **No.**
- Status: **FIXED IN CODE — AWAITING NARROW-SCREEN QA**

## Finding P1-008 — Trip position-source expectation is ambiguous

- Severity: **P1 / recording correctness and trust**
- User story: before Start Trip, show the exact canonical position source/strategy that will be recorded; if Phone/System is selected, the active sample and stored session must reflect it or show a blocker.
- Evidence: Trip persists `VesselDataSettings.positionPreference`, while Anchor Settings persists `AppSettings.gpsDataSource`; both are user-facing “GPS source” concepts. Trip Start only shows the currently arbitrated observation and has no explicit Manage link, so choosing System GPS in Anchor positioning does not necessarily change Trip’s Vessel Hub preference.
- Reproduction steps: select System GPS under Anchor positioning, leave Data → Vessel position on Boat/Auto, start Trip, inspect source in Trip samples.
- Root cause: two correctly different domains use insufficiently differentiated labels; Trip Start does not expose its authoritative setting or require an eligible selected observation.
- Failing test: `P0OperabilityComposeTest.tripPhoneGpsChoiceRequiresAndUsesAnEligiblePhoneCandidate`; `tripPhoneGpsChoiceIsBlockedWhenAndroidGnssHasNoEligibleFix`.
- Fix commit: **`ce30e53`**
- Verification result: **Trip Start now makes an explicit per-session Auto/Boat NMEA/Phone GPS choice, blocks until that source is fresh, persists it and clears the override at end/empty restore. Compose tests were added but NOT RUN.**
- Real hardware verified: **No.**
- Status: **FIXED IN CODE — AWAITING TRIP QA**

## Finding P1-009 — Trip history map detail is hidden and incomplete for no-map/no-fix cases

- Severity: **P1 / report usability**
- User story: opening a completed Trip immediately shows route map/replay affordance, source timeline and a clear empty-state reason when coordinates are missing.
- Evidence: a static route map exists only inside the secondary Report dialog, is disabled for interaction, and returns no UI at all when maps are not configured or route points are empty. The main expanded history card does not preview the route.
- Reproduction steps: open completed Trip details; note that map visualization requires another Report action and silently disappears for an empty route.
- Root cause: visualization is nested under analytics rather than owned by the history detail; empty states are represented by returning from the composable.
- Failing test: `P0OperabilityComposeTest.completedTripWithoutCoordinatesShowsAnExplicitRouteReason`.
- Fix commit: **`ce30e53`, `320e9d3`**
- Verification result: **Expanded Trip history owns a route preview and explicit loading/no-coordinate/no-map states. `completedTripWithoutCoordinatesShowsAnExplicitRouteReason` was added but NOT RUN.**
- Real hardware verified: **No.**
- Status: **FIXED IN CODE — AWAITING ROUTE/MAPS QA**

## Finding P1-010 — Source contains incomplete prior-pass edits

- Severity: **P1 / build integrity**
- User story: stabilization branch must remain buildable and reviewable.
- Evidence: the carried working tree contains partially applied cross-cutting edits and documentation that claim verification from an earlier pass. This ledger treats those claims as historical only; this pass has not rerun them per user instruction.
- Reproduction steps: inspect working tree and compare source/diff for duplicated or contradictory mutation routes.
- Root cause: preceding work was left uncommitted on `codex/develop` before this P0 task began.
- Failing test: compile/lint gate to be run later; `git diff --check` is the only permitted local static gate in this pass.
- Fix commit: **`ce30e53`, `320e9d3`**
- Verification result: **Full working diff was reviewed, secret-pattern scan and `git diff --check` passed. Unit/lint/assemble/connected gates remain deliberately NOT RUN.**
- Real hardware verified: **No.**
- Status: **REVIEWED AND COMMITTED — BUILD INTEGRITY UNVERIFIED UNTIL GATE RUN**
