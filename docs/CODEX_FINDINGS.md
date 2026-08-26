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
- Failing test: the first permitted `./gradlew --no-daemon assembleDebug` run failed at `WatchBottomSheet.kt:103`; lint and test gates remain deferred.
- Fix commit: **`ce30e53`, `320e9d3`**
- Verification result: **Full working diff was reviewed, secret-pattern scan and `git diff --check` passed. The first Debug assemble exposed an invalid `@Composable` translation call inside `LaunchedEffect`; translation is now resolved before entering the effect, and the repeated `assembleDebug` passed. Unit/lint/connected gates remain deliberately NOT RUN.**
- Real hardware verified: **No.**
- Status: **DEBUG BUILD VERIFIED — LINT/TEST/RELEASE/HARDWARE GATES STILL OPEN**

## Finding P0-011 — P0 branch initially failed Kotlin compilation

- Severity: **P0 / release-blocking build integrity**
- User story: a developer or CI runner must be able to assemble the Debug APK before any manual P0 verification begins.
- Evidence: the first `./gradlew --no-daemon assembleDebug` run failed with “`@Composable invocations can only happen from the context of a @Composable function`” at `WatchBottomSheet.kt:103`.
- Reproduction steps: check out `ea6413c` and run `./gradlew --no-daemon assembleDebug`.
- Root cause: the bilingual `tr(...)` Compose helper was invoked after a coroutine delay inside a `LaunchedEffect` suspend lambda rather than during composition.
- Failing test: `./gradlew --no-daemon assembleDebug`.
- Fix commit: **`a545e4a`**
- Verification result: **The translated message is now resolved during composition and captured by the effect. A redundant always-true post-delay condition reported by the compiler was also removed without changing the single-flight timeout behavior. The final clean `assembleDebug` completed successfully in 28s.**
- Real hardware verified: **No — not required for compiler verification.**
- Status: **FIXED AND DEBUG ASSEMBLE PASSED**

## Finding P0-012 — Saved Anchorage Approach is clipped inside Current

- Severity: **P0 / navigation operability**
- User story: selecting Approach from a saved anchorage must open a dedicated full-screen navigation destination; it must not inherit Current-tab chrome or the Anchor Watch bottom sheet.
- Evidence: `AnchorageApproachOverlay` was rendered inside `AnchorWatchPage` → `BottomSheetScaffold` while `AnchorRootPage` still rendered its tab row and `AnchorApp` still rendered the bottom navigation bar. `fillMaxSize()` therefore meant only the reduced Current content slot.
- Reproduction steps: open a saved Spot, select Approach, accept the disclaimer, then compare the visible navigation data area with the full App window; observe Anchor tabs, bottom navigation and the Current sheet still owning space.
- Root cause: Approach was implemented as a child overlay of the map rather than an App-level navigation destination.
- Failing test: `P0OperabilityComposeTest.anchorageApproachReplacesCurrentWorkspaceInsteadOfBecomingItsChild`.
- Fix commit: **`d1e703f`**
- Verification result: **Approach now replaces the workspace, suppresses bottom/root navigation chrome, owns the system-back action, retains the detail and Set Anchor Watch hand-off, and scrolls on constrained screens. `assembleDebug` passed in 1m12s; Compose test was written but NOT RUN.**
- Real hardware verified: **No — verify small-screen/font-scale layout and phone back gesture in QA-P0-019.**
- Status: **FIXED AND DEBUG ASSEMBLE PASSED — AWAITING MANUAL QA**

## Finding P0-013 — Failed NMEA RX attempts can hide the error and stress a fragile gateway

- Severity: **P0 / connectivity and external-device protection**
- User story: one Connect action owns one formal RX transport; a quiet connection stays open; a refusal remains visible; retries are slow, bounded and observable; repeated taps never create a connection storm.
- Evidence: the connection coroutine previously ended by publishing `DISCONNECTED`, overwriting the actionable transport error. Retry delay and attempt count were not exposed to the UI, and the old manual reconnect debounce was shorter than a fragile server's recovery time.
- Reproduction steps: enter a closed or refusing TCP endpoint, tap Connect once, then inspect the status/error and server connection attempts; repeat with auto reconnect enabled and with several rapid Reconnect taps.
- Root cause: transport ownership, presentation state and retry policy were coupled in one loop without a latched failure state, monotonic transport-generation diagnostics or a bounded retry circuit.
- Failing test: `NmeaConnectionManagerTest.refusedConnectionKeepsOneVisibleErrorAndDoesNotOpenAnotherSocket`; `automaticOpenFailuresBackOffAndStopAfterTheBoundedCircuitLimit`; `explicitReconnectReplacesSameProfileOnceAndRapidTapsAreDebounced`; `P0NmeaEndpointStoryTest.formalInputOwnsOneSocketWhileQuietThenReceivesLater`.
- Fix commit: **`f22c8fe`**
- Verification result: **Passed the expanded `NmeaConnectionManagerTest` (14/14) inside the complete 571-test Debug JVM suite. Circuit-open diagnostics and terminal `ERROR` now publish atomically, so UI/tests cannot observe `circuitOpen=true` with `RECONNECTING`. Final `assembleDebug` passed. No real endpoint was contacted.**
- Real hardware verified: **No — fragile NMEA gateway remains UNVERIFIED_HARDWARE.**
- Status: **FIXED — TARGETED JVM + DEBUG BUILD PASSED; AWAITING GATEWAY QA**

## Finding P0-014 — Formal phone NMEA output can bypass calibration or duplicate the RX endpoint

- Severity: **P0 / unsafe publication and gateway protection**
- User story: formal App-to-boat sharing starts only after calibration version, explicit fixed-mount confirmation and heading alignment all match; independent TX must never create a second client on the configured RX host+port, whether RX is open, closed, or has never been opened.
- Evidence: UI readiness treated an old calibration/alignment as sufficient after recalibration, while runtime collectors did not immediately stop output when readiness became invalid. Independent TCP output validated only generic host/port ranges and could target the same endpoint as RX.
- Reproduction steps: configure independent TX with the exact RX host+port before connecting RX and try endpoint test/start; repeat while RX is open. Separately recalibrate vessel zero after enabling formal output and attempt to restart without reconfirming mount/alignment.
- Root cause: calibration facts had timestamps but no version binding, and endpoint collision was a warning rather than a mandatory policy enforced at UI, ViewModel, runtime, connection and writer boundaries.
- Failing test: `NmeaDeviceOutputPolicyTest.duplicateIndependentTxIsBlockedByConfigurationEvenBeforeRxEverOpened`; `protectedWriterNeverCreatesDuplicateSocketWhenRxIsAlreadyOpen`; `protectedWriterNeverCreatesDuplicateSocketBeforeRxHasEverOpened`; `NmeaStreamReadinessPolicyTest.formalOutputRequiresVersionMatchedZeroMountAndHeadingAlignment`; `suspectMountImmediatelyBlocksFormalOutput`.
- Fix commit: **`f22c8fe`**
- Verification result: **Passed `NmeaDeviceOutputPolicyTest` (18/18) and `NmeaStreamReadinessPolicyTest` (6/6), including the open-RX and never-opened-RX zero-new-socket cases. Final `assembleDebug` passed in 30s. Tests used loopback sockets only.**
- Real hardware verified: **No — vessel mounting and the actual TX/RX gateway ports remain UNVERIFIED_HARDWARE.**
- Status: **FIXED — TARGETED JVM + DEBUG BUILD PASSED; AWAITING GATEWAY/SENSOR QA**

## Finding P0-015 — Two publishers, BACKUP suppression and non-atomic Stop make live NMEA disappear or linger

- Severity: **P0 / live navigation data and transport safety**
- User story: one explicit Start publishes one steady Anchor Watch phone/App feed through the chosen transport; unchanged heading remains at 5 Hz; Boat input never suppresses or leaks into it; one Stop returns only after the old session can emit no more bytes.
- Evidence: production had both `PhonePositionNmeaOutputRuntime` and `NmeaSharingRuntime`. The former could suppress phone values through `PublicationOwnershipGate/BACKUP`; the latter subscribed to `validRawSentences` and forwarded Boat instruments. Dedicated TCP registered its Socket only after blocking `connect()`, and queued writes had no publication generation. TCP server enqueue was also counted as sent without a client flush.
- Reproduction steps: start normal phone output, feed a Boat HDT/VHW candidate, hold phone heading constant, then Stop during a blocked dedicated connect or while heading batches are queued. Separately start the old Sharing server and observe raw Boat sentences using a different feed.
- Root cause: output source policy, feed generation, transport ownership and lifecycle were split across two engines; Stop did not own a monotonic session lease or the in-flight connect candidate.
- Failing test: `AnchorWatchNmeaPublisherTest.constantHeadingHasFiveHertzHeartbeatForTenMinutesWithoutBlankSentence`; `boatCandidatesNeverSuppressOrRewriteTheSelectedAnchorWatchHeading`; `stopInvalidatesEveryOldPublicationGeneration`; `NmeaDeviceOutputPolicyTest.stopClosesAnInFlightConnectCandidateInsteadOfWaitingForTimeout`; `PhoneNmeaOutputStopBarrierTest.stopCannotReturnBeforeAnInFlightSocketWriteHasJoined`; `NmeaSharingServerTest.listeningWithoutAClientNeverClaimsASentenceWasSent`.
- Fix commit: **`f22c8fe` plus the uncommitted `codex/nmea-publisher-hard-stop` follow-up**
- Verification result: **One `AnchorWatchNmeaPublisher` now generates Phone GNSS, calibrated phone vessel heading/motion, phone pressure and App-derived wind. Authoritative/default same-input Socket and explicitly selected Advanced TCP client/server or UDP consume that same bounded latest-value feed. Legacy Sharing migrates stopped; raw Boat forwarding and the second runtime were deleted. Stop invalidates generation, closes connect candidates/transports, waits for the byte barrier, clears Live TX and retains a separately labelled last session. The dedicated in-flight barrier regression uses a real loopback Socket and proves STOP cannot return before the blocked writer joins or allow local socket byte count to rise after return. TCP-server writer jobs are now joined without a timeout escape. The targeted barrier/output/server policy group passed 35/35; the prior final Debug unit/lint/assemble gates also passed. No real NMEA endpoint was contacted.**
- Real hardware verified: **No — do not connect repeatedly to the user's fragile gateway. Run the manual receiver matrix once with packet capture.**
- Status: **FIXED — UNIT/LINT/DEBUG BUILD PASSED; AWAITING ONE CONTROLLED HARDWARE QA RUN**

## Finding P0-016 — Previous publisher fix still expired unchanged Heading by numeric time

- Severity: **P0 / downstream NO HEADING**
- User story: a physical instrument may send one complete Heading followed by same-sentence blank fields while unchanged; the App must keep publishing the last complete Heading while that exact source heartbeat remains fresh.
- Evidence: `VesselSourceCandidate` stored a heartbeat, but `VesselSourceArbitrator.select()` evaluated only `now - receivedElapsedRealtime`; `AnchorWatchNmeaFeedEncoder` separately imposed a five-second Heading age filter.
- Reproduction steps: publish one numeric IIHDT, then feed IIHDT blank heartbeats for more than five seconds while polling the canonical encoder at 5 Hz.
- Root cause: numeric measurement age and source liveness were conflated in arbitration, while publication had no independent per-metric last-complete-value lease.
- Failing test: `VesselSourceArbitratorTest.numericHeading_thenBlankHeartbeats_remainsHeldAndSelected`; `AnchorWatchNmeaPublisherTest.numericHeading_thenBlankHeartbeats_remainsHeldAndPublished`.
- Fix commit: **UNCOMMITTED WORKTREE on `codex/nmea-publisher-hard-stop`**
- Verification result: **The tests failed before implementation and pass after `MetricSourceEligibility` + `CanonicalNmeaMetricLeaseBank`. The final unit evidence is aggregate 539 passed, 0 failed/errors and one wall-clock soak skipped by default. The wall-clock soak was then forced uncached against the final source and passed exactly 3,000 complete HDT deliveries at 5 Hz with every receiver gap asserted ≤400 ms. Lint and Debug assemble also passed.**
- Real hardware verified: **No.**
- Status: **FIXED — ALL LOCAL SOFTWARE GATES PASSED; CONTROLLED HARDWARE QA REQUIRED**

## Finding P0-017 — TCP-server client writers could outlive Stop presentation

- Severity: **P0 / ghost bytes after Stop**
- User story: once Stop returns and Output shows OFF, no old TCP-server client writer may flush another byte or resurrect RUNNING state.
- Evidence: the destination closed/cancelled clients but did not join their writer jobs; a writer `finally` block could publish a stale RUNNING status after Stop reset state.
- Reproduction steps: attach a TCP client, publish a sentence, close/Stop while the client writer is active, then inspect server state and receiver bytes.
- Root cause: transport closure and coroutine termination were separate asynchronous operations.
- Failing test: `NmeaSharingServerTest.broadcastsCrlfSentenceToTcpClientAndStopsCleanly`; stopped zero-byte soak.
- Fix commit: **UNCOMMITTED WORKTREE on `codex/nmea-publisher-hard-stop`**
- Verification result: **`NmeaSharingServer.stop()` now closes listener/clients, joins old jobs with a bounded timeout, and reasserts STOPPED after join. Targeted socket tests pass. The forced wall-clock Fake TCP soak closed the only writer after 3,000 lines and then completed a real 60-second window with no new connection or line. Packet-path diagnostics now distinguish queued server delivery from actual client flush.**
- Real hardware verified: **No.**
- Status: **FIXED — ALL LOCAL SOFTWARE GATES PASSED; CONTROLLED HARDWARE QA REQUIRED**

## Finding P0-018 — Depth, STW and selected Boat ROT were omitted from the independent canonical scheduler

> **SUPERSEDED 2026-08-26:** this earlier requirement was rejected after product review. NMEA Output is a Phone/App sensor node, not a Boat-data repeater. DBT/DPT/VHW and Boat ROT are now deliberately absent from every output transport; see Finding P0-023. The historical evidence below is retained only to explain the removed behavior.

- Severity: **P0 / canonical feed completeness**
- User story: every already-supported selected typed vessel metric listed by the P0 contract must keep its own cadence and lease; a Boat ROT must not disappear merely because phone attitude is absent.
- Evidence: the initial replacement scheduler contained only Position, Heading, Motion, Pressure and Derived Wind. The sentence completeness filter recognized DBT/VHW, but no stream ever generated either sentence, and Motion read only phone attitude yaw rather than `rateOfTurnDegreesPerMinute`.
- Reproduction steps: construct a `VesselDataSnapshot` containing selected Depth, STW and Boat ROT with no phone attitude, then poll every scheduler stream; observe no DBT, VHW or ROT before the fix.
- Root cause: the first publisher consolidation focused on the reported Heading/Stop failures and copied the previous phone-output families instead of reconciling every stream in the attached P0 schedule.
- Failing test: `AnchorWatchNmeaPublisherTest.depthAndStwHaveIndependentCadenceAndHoldOnlyCompleteSameSourceValues`; `selectedBoatRotPublishesWithoutAConnectedPhoneAttitudeSensor`.
- Fix commit: **UNCOMMITTED WORKTREE on `codex/nmea-publisher-hard-stop`**
- Verification result: **Both tests failed before implementation and now pass. DBT is 1 Hz with a 60-second metric lease, VHW/STW is 2 Hz with a 10-second lease, and Boat ROT uses the 2 Hz Motion stream with a three-second lease. Out-of-range or incomplete primary fields suppress the whole sentence. Final aggregate unit, lint, assemble, targeted Output Compose and real-time Heading/Stop soak gates pass.**
- Real hardware verified: **No.**
- Status: **SUPERSEDED — BEHAVIOR REMOVED FROM PRODUCT**

## Finding P2-019 — Pressure history test could observe the first asynchronous upsert before replacement

- Severity: **P2 / CI determinism**
- User story: a passing product behavior must not fail the build gate depending on coroutine scheduling.
- Evidence: the test waited only until DAO row count became one. The first same-minute write already satisfied that condition, so the assertion could run before the second upsert replaced its pressure value.
- Reproduction steps: run the complete unit suite under load; `repeatedMeasurementsInOneMinuteRemainOneDatabaseRow` can read 1012.0 instead of the eventual 1012.4.
- Root cause: the assertion synchronized on cardinality rather than the observable state it intended to verify.
- Failing test: `PressureHistoryRepositoryTest.repeatedMeasurementsInOneMinuteRemainOneDatabaseRow`.
- Fix commit: **UNCOMMITTED WORKTREE on `codex/nmea-publisher-hard-stop`**
- Verification result: **The test now awaits both one row and the final replacement value, then passed when only the failed case was rerun. Product repository behavior was not changed.**
- Real hardware verified: **Not applicable.**
- Status: **FIXED — TARGETED RETRY PASSED**

## Finding P0-020 — Same-socket queued NMEA could cross an RX reconnect boundary

- Severity: **P0 / incorrect live vessel output**
- User story: a canonical batch generated for TCP transport N must be written only to N; reconnect N+1 starts with newly sampled data and never receives a queued pre-reconnect batch.
- Evidence: publication batches carried only publisher generation. `NmeaConnectionManager.write()` looked up whichever Socket was current at write time and returned only Boolean.
- Reproduction steps: queue a batch, replace the formal input transport, then let the writer drain; before this fix the batch could resolve the replacement socket.
- Root cause: publisher lifecycle generation and physical transport generation were separate concepts, but only the former was bound to a batch.
- Failing test: `NmeaConnectionManagerTest.queuedBatchFromOldTransportGenerationIsNeverWrittenAfterReconnect`.
- Fix commit: **UNCOMMITTED WORKTREE on `codex/nmea-publisher-hard-stop`**
- Verification result: **Generation-aware loopback test passed. Encoder also rejects a selected Boat value from an older input generation. Packet diagnostics contain expected/actual transport generation and a dropped reason. Final Debug unit suite passed 549 total with 0 failures/errors and one opt-in soak skipped; lintDebug and assembleDebug passed.**
- Real hardware verified: **No — UNVERIFIED_HARDWARE.**
- Status: **FIXED IN CODE — TARGETED JVM PASSED; CONTROLLED GATEWAY QA REQUIRED**

## Finding P0-021 — Phone GPS Arm could wait before actually acquiring GNSS

- Severity: **P0 / anchor safety cannot start**
- User story: choosing Phone GPS and pressing Start must first acquire System GNSS, then create the session, or show the exact foreground failure once.
- Evidence: `awaitUsableStartFix()` called `enableSystemGps()`, which changed the foreground-service type but did not set `SystemLocationRepository.backgroundEnabled`. Formal `ANCHOR_WATCH` resources were acquired only after a session was inserted. The setup UI also recognized failures by the English substring `not started`, and its 15-second timeout raced the Runtime's own 15-second timeout.
- Reproduction steps: open setup when Phone GPS is not already owned by saved App settings, select Phone GPS, then Start; no repository owner starts LocationManager while Runtime waits. In Chinese, the localized failure does not match the English substring and the progress UI persists until its fallback timeout.
- Root cause: circular resource acquisition plus language-dependent command-result inference.
- Failing test: `RuntimeTripOwnerTest.anchorStartupGpsLeaseHandsOffWithoutAZeroOwnerGap`; `AnchorSetupSubmissionPolicyTest.localizedArmFailureEndsSpinnerWithoutMatchingEnglishTitle`; device story `AnchorSafetyFlowTest.freshSystemGpsArmCreatesAnActiveSessionWithoutNmea`.
- Fix commit: **UNCOMMITTED WORKTREE on `codex/nmea-publisher-hard-stop`**
- Verification result: **`ANCHOR_STARTUP` now owns GNSS/wake resources before the bounded wait and hands off to `ANCHOR_WATCH`; the setup sheet temporarily previews GNSS and consumes typed `ARM_WATCH` failure context. Targeted JVM tests, the 549-test Debug unit gate, lintDebug and assembleDebug passed. The full Service → startup lease → emulator GNSS → Room ACTIVE-session regression has been added and `assembleDebugAndroidTest` passes, but that device story was deliberately not executed in this pass.**
- Real hardware verified: **No — start once outdoors with a fresh GNSS fix.**
- Status: **FIXED IN CODE — TARGETED JVM PASSED; PHONE HARDWARE QA REQUIRED**

## Finding P1-022 — Residual field/echo/framing gaps reduced output trust

> **UPDATED 2026-08-26:** occurrence-consuming echo matching remains the
> confirmed policy because it cannot indefinitely hide a genuinely independent
> instrument with the same value. A short pre-socket barrier now closes only the
> first-return race. The observed KC-2W/Raymarine dropout was not an App echo
> loop; the vessel test confirmed a 4800-baud bottleneck resolved at 38400.

- Severity: **P1 / interoperability and diagnostics**
- User story: App output frames are valid and observable; echoes cannot become Boat evidence; held values disappear after source silence; App pressure is typed consistently.
- Evidence: exact echo storage kept one timestamp per sentence, semantic matches were not occurrence-consuming, retained generic fields expired only when a later sentence arrived, XDR bar pressure remained RAW, and no final centralized 82-byte framing validator existed.
- Reproduction steps: send the same App frame twice and receive three echoes; stop all input traffic after an XDR field; decode `IIXDR,P,1.01320,B,PHONE_BARO`; inject an oversized generated frame.
- Root cause: value-oriented caches were reused where occurrence, time-driven expiry and a final transport boundary were required.
- Failing test: `NmeaDeviceOutputPolicyTest.exactEchoQuarantineConsumesOneOccurrencePerOutboundFrame`; `semanticEchoAlsoConsumesOneOutboundOccurrence`; `NmeaFieldDecoderTest.retainedFieldsExpireWithoutWaitingForAnotherSentence`; `NmeaOutputMuxTest.generatedSentenceBoundaryRejectsBadFramingUnicodeChecksumAndOversize`; `NmeaParserTest.streamFramingSurvivesRandomTcpFragmentation`.
- Fix commit: **UNCOMMITTED WORKTREE on `codex/nmea-publisher-hard-stop`**
- Verification result: **All listed targeted tests pass, including 300 randomized fragmentation seeds. Final Debug unit suite passed 549 total with 0 failures/errors and one opt-in soak skipped; lintDebug and assembleDebug passed.**
- Real hardware verified: **No — Pi/converter/MFD are NOT YET PROVEN.**
- Status: **FIXED IN CODE — LOCAL TARGETS PASS; EXTERNAL CHAIN QA REQUIRED**

## Finding P0-023 — Output transport choice could re-publish Raymarine/KC-2W Boat data

- Severity: **P0 / bidirectional gateway feedback and disappearing instruments**
- User story: every Phone Vessel Output route publishes only Phone sensors and explicit Anchor Watch calculations. Selecting a separate TCP/UDP/server destination must never re-publish values the App received from Raymarine/KC-2W or another Boat instrument.
- Evidence: `AnchorWatchNmeaFeedEncoder` consumed `VesselDataHub` selected Position, SOG, COG, Heading, ROT, Pressure, Wind, Depth and STW for every transport. `VesselDataHub` AUTO normally prioritizes `BOAT_NMEA`; only Position had a partial exclusion while phone position publication was active, and RMC still resolved SOG/COG independently. A bidirectional KC-2W could therefore return Raymarine/N2K candidates that the App encoded back onto the same Socket.
- Reproduction steps: create a Phone GNSS fix and phone Heading candidate; add higher-priority Raymarine-like Boat SOG/COG/HDT/MWV/DBT candidates from the current input generation; encode each stream for `SAME_AS_INPUT_CONNECTION`. Before the fix, Boat Heading/Depth/STW and Boat-derived wind were emitted and Phone RMC could contain Boat SOG/COG.
- Root cause: transport and source semantics were coupled. Same-socket had a provenance firewall, but choosing an independent transport silently switched the encoder to selected VesselDataHub fan-out and made Boat data eligible again.
- Failing test: `AnchorWatchNmeaPublisherTest.boatHeadingIsBlockedOnEveryTransportAndPhoneCandidateWins`; `independentPositionFeedUsesOnlyTheAtomicPhoneFix`; `mockOrNetworkPositionCanNeverEnterPhoneOutput`; `boatDepthAndStwAreNotPublisherStreamsOnAnyTransport`; `transportChoiceNeverTurnsBoatInputIntoOutput`; `selectedBoatRotIsNeverRepublishedWithoutPhoneAttitude`; `appDerivedWindIsPublishableButDirectBoatWindIsNot`; `tcpServerAndTcpClient_useSameFeedScheduler`; `PositionSourceConflictPolicyTest.phoneOwnedOutputAndNmeaInputMayCoexistWithoutChangingEitherSource`.
- Fix commit: **UNCOMMITTED WORKTREE on `codex/nmea-publisher-hard-stop`**
- Verification result: **Superseding 2026-08-26 change: all transports now use the same Phone/App-owned encoder. One accepted Android `NavigationFix` atomically owns Position/SOG/COG/time; calibrated Phone candidates own Heading/IMU/Barometer; only values with explicit Anchor Watch `APP_DERIVED` identity and provenance may add computed true wind. Depth/STW and every direct Boat observation are absent from the scheduler on same-socket, dedicated TCP, TCP server and UDP. Tests were rewritten for this stricter boundary but deliberately NOT RUN. Earlier pass counts above do not verify this superseding change.**
- Additional isolation: **Starting Phone output no longer changes `VesselDataHub` source arbitration or blocks an Anchor/Trip from selecting NMEA position. TX reads a separately accepted, real non-mock Android GNSS fix; Android mock/proxy locations are rejected before the publisher boundary. Thus NMEA may remain the App's input while Phone GNSS is the independently owned output source.**
- Real hardware verified: **No — Raymarine MDS, KC-2W firmware conversion and N2K source-address behavior remain EXTERNAL-UNVERIFIED.**
- Status: **FIXED IN CODE — TESTS NOT RUN; ONE CONTROLLED KC-2W/IS42/RAYMARINE QA RUN REQUIRED**

## Finding P0-024 — MFD-related sensor disturbance could flap the complete Phone NMEA session

- Severity: **P0 / downstream NO HEADING and disappearing phone fields**
- User story: after a calibrated Phone→Boat session starts, turning on a Raymarine MFD must not make every phone-sourced KC-2W field disappear. A genuinely untrusted handset mount must stop unsafe Heading/Motion immediately while independent Phone GNSS/pressure and the existing TCP session remain alive.
- Evidence: during controlled hardware QA, Raymarine initially received stable Heading, later reported `NO HEADING`, and KC-2W DataList phone values began appearing and disappearing. Code inspection found that any `MOUNT_SUSPECT` transition called the publisher's whole-session stop path and the coordinator persisted `publicationEnabled=false`. Mount suspicion could also be triggered by a rotation-vector jump alone, although Android's fused vector can jump when nearby powered marine electronics disturb its magnetic component.
- Reproduction steps: start calibrated same-input Phone output; while it is publishing, inject a large rotation-vector discontinuity with negligible gyro movement or transition the runtime mount to `MOUNT_SUSPECT`; inspect the master output session and the Position/Pressure/Heading stream states.
- Root cause: the formal Start readiness gate was incorrectly reused as a continuous whole-session kill switch, and the mount detector treated an uncorroborated fused-vector discontinuity as physical handset movement.
- Failing test: `NmeaStreamReadinessPolicyTest.magneticFusionJumpWithoutPhysicalGyroEvidenceDoesNotInvalidateTheMount`; `activeOutputMountWarningSuppressesOnlyUnsafeVesselFrameStreamsOnEveryTransport`; `runtimeMountWarningCannotTurnOffAnAlreadyStartedPublicationSession`.
- Fix commit: **UNCOMMITTED WORKTREE on `codex/nmea-publisher-hard-stop`**
- Verification result: **Code changed but tests deliberately NOT RUN in this pass. Formal sharing still cannot begin without version-matched zero/mount/alignment. Once running, a mount warning keeps the publication generation and selected transport alive, immediately suppresses Phone Heading/Motion on every route, and leaves Phone Position/Pressure active. Rotation-vector jumps now require corroborating gyro motion unless angular velocity is independently extreme. The NMEA Output diagnostics must be captured during the next single hardware run to distinguish any remaining socket backpressure from sensor gating.**
- Real hardware verified: **Partially reproduced before the fix; post-fix behavior NOT YET VERIFIED.**
- Status: **FIXED IN CODE — TESTS NOT RUN; ONE CONTROLLED KC-2W/RAYMARINE QA RUN REQUIRED**

## Finding P0-025 — Per-stream fast writes can overload the KC-2W path after Raymarine joins

- Severity: **P0 / downstream fields expire and return one by one**
- User story: when Raymarine joins the N2K network, Phone data forwarded through the KC-2W path must remain a quiet, complete 1 Hz heartbeat. Unchanged Heading must never disappear, and one congested interval must not create a backlog or a burst of independent flushes.
- Evidence: hardware QA showed all Phone fields initially appearing in KC-2W DataList, then disappearing together, returning individually and producing errors after Raymarine powered on. The App scheduled Heading at 5 Hz, Motion/Wind/STW at 2 Hz, and Position/Pressure/Depth at 1 Hz. Each stream occupied a separate queue item and the single writer performed a separate socket write/flush, so one blocked writer allowed later stream values to accumulate independently and emerge one by one.
- Reproduction steps: start same-input output with every local family ready; hold the writer while multiple scheduler periods elapse; release it and inspect per-stream queue replacement, wire-write count and receiver order.
- Root cause: product cadence and physical wire cadence were separate. Fast per-stream heartbeats multiplied socket operations on a fragile bidirectional converter, while the latest-per-stream queue made recovery visible as a staggered sequence rather than one fresh snapshot.
- Failing test: `AnchorWatchNmeaPublisherTest.everyStreamUsesOneHertzAndConstantHeadingNeverBecomesBlank`; `slowGatewayGetsNoCatchUpBurstAfterAWriteReturns`; `NmeaConnectionManagerTest.oneHertzSchedulerTickIsOneContiguousCrlfWirePayload`; `writeBackpressureHasAWarningWindowBeforeTheHardStallBoundary`; `sharedWriteStallAbortTargetsOnlyTheExpectedTransportGenerationAndRunsOnce`; updated opt-in `NmeaPublisherRealtimeSoakTest.fakeTcpReceiver_tenMinuteHeadingSoak_thenSixtySecondStoppedZeroBytes`.
- Fix commit: **UNCOMMITTED WORKTREE on `codex/nmea-publisher-hard-stop`**
- Verification result: **All normal stream periods are now 1,000 ms. The publisher prepares every due stream, retains only its newest blocked value, and wakes the writer once after the complete tick is ready. The writer drains those values into one contiguous payload and invokes one Socket write/flush; stale publication/input generations are dropped and never replayed. A slow write cannot trigger a catch-up burst: after crossing 500 ms it receives a full one-second recovery window and the pending slots continue replacing in place. At 500 ms the live UI reports congestion; at three seconds the exact same-input transport generation is aborted once and only the existing bounded RX reconnect policy may recover it. Unchanged complete Heading remains a mandatory 1 Hz heartbeat. Tests and manuals were updated but deliberately NOT RUN.**
- Real hardware verified: **No — post-fix KC-2W/Raymarine run pending.**
- Status: **FIXED IN CODE — KOTLIN COMPILE PASSED; TESTS NOT RUN; CONTROLLED HARDWARE QA PENDING**

## Finding P0-026 — Boat-network injection and phone-hosted NMEA server shared one lifecycle

- Severity: **P0 / wrong product boundary and cross-feature socket ownership**
- User story: writing missing Phone/App data into the Boat network and hosting a TCP service for another device are different products. Either must be configurable, started, stopped and diagnosed without mutating the other.
- Evidence: `NmeaOutputTransportMode.TCP_SERVER` lived inside `NmeaDeviceOutputSettings`; `AnchorWatchNmeaPublisher`, `NmeaDeviceOutputConnection`, calibration gating, one `publicationEnabled` lease and one Output UI owned same-socket Boat TX, dedicated Boat TX, UDP and the phone listener. Stopping the common connection called `tcpServer.stop()`.
- Reproduction steps: configure TCP Server in Data → Output; Start it, then change/stop the common output product or exercise a Boat-output lifecycle refresh. Observe that the phone listener is treated as a Boat destination and shares its Stop/generation/status.
- Root cause: a common Phone/App sentence feed was mistaken for common destination ownership. Data-plane reuse leaked into control-plane settings and lifecycle.
- Failing test: updated `NmeaDeviceOutputPolicyTest.tcpServerIsNotABoatNetworkOutputTransport`; `AnchorSafetyFlowTest.legacySharingRequestMigratesToIndependentStoppedPhoneServiceWithoutOpeningASocket`; new direction-selection UI story and product-boundary unit coverage.
- Fix commit: **`53bf3c2` — `feat(nmea): separate boat injection from phone server`**
- Verification result: **Code written; tests deliberately NOT RUN. Boat supplement no longer injects/owns `NmeaSharingServer`. Phone NMEA service has a separate DataStore-backed configuration, process-local lease, runtime, encoder state, resource owner, diagnostics and UI destination. Both may run together. Legacy TCP-server routes migrate stopped and are rejected below UI.**
- Real hardware verified: **No.**
- Status: **FIXED IN CODE — KOTLIN COMPILE PASSED; TESTS NOT RUN; MANUAL TWO-DEVICE QA REQUIRED**

## Finding P0-027 — Rapid phone NMEA server Start requests could stop a fresh listener

- Severity: **P0 / Start requires repeated taps; apparent 1 ms session**
- User story: one Start tap must create a stable listener. Repeated taps or duplicate UI/runtime refreshes must be harmless, and zero clients must not self-stop the service.
- Evidence: `NmeaSharingServer.start()` checked the active job under `synchronized`, released it, called `stop()`, then reacquired it to launch. Two concurrent Start calls could both pass the first check; the second then stopped the first call's newly created listener.
- Reproduction steps: issue many concurrent `start(samePort)` calls while the server is stopped, then connect a client. Before serialization the lifecycle could visibly enter STARTING/STOPPED and require another tap.
- Root cause: check → stop/join → launch was not one serialized lifecycle transaction; configuration persistence and the live lease were also exposed as one operation in the common Output repository.
- Failing test: `NmeaSharingServerTest.rapidDuplicateStartIsIdempotentAndCannotCreateAOneMillisecondSession`.
- Fix commit: **`53bf3c2` — `feat(nmea): separate boat injection from phone server`**
- Verification result: **Code written; test deliberately NOT RUN. A fair lifecycle lock now serializes complete Start/Stop transactions. Same-port Start is idempotent inside that lock. Local service configuration saving cannot mutate its live lease.**
- Real hardware verified: **No.**
- Status: **FIXED IN CODE — KOTLIN COMPILE PASSED; TESTS NOT RUN; MANUAL START/SOAK QA REQUIRED**

## Finding P1-011 — Saved anchorage exposed two nearby surfaces and an internal GIS schema

- Severity: **P1 / saved-anchorage operability**
- User story: one saved anchorage should open one complete, full-screen card with clear Approach, Maps, Share, Edit and Delete actions; Watch should show exactly one nearby prompt.
- Evidence: `WatchScreen` rendered `GisNearbyAnchorageCard` above the map while `WatchBottomSheet` independently rendered `NearbyAnchorageCard`. The library called itself a `Place → Spot → Visit` guide, exposed raw planning/verification enums, and opened a five-tab `AlertDialog` whose default tab was Spots.
- Reproduction steps: approach a saved anchorage with Watch inactive and inspect both the map and setup sheet; then select a saved marker and try to find its notes, edit/delete action and navigation without understanding Place/Spot/Visit.
- Root cause: the canonical GIS persistence model leaked into presentation, while an experimental GIS nearby projection was added alongside the established Watch-sheet projection instead of replacing it.
- Failing test: updated `NearbyAnchorageCardTest`; new protected-delete regression `AnchorageGisRepositoryTest.savedAnchorageUsedByActiveWatchCannotBeDeletedBelowTheUi`; full-screen detail test tags are available for the pending Compose story suite.
- Fix commit: **`c45111f`, `ce54be1`**
- Verification result: **The duplicate GIS nearby composable and its independent ViewModel were removed. The Watch sheet is the single nearby owner. Saved anchorages now use a sailor-facing map/list/filter surface and one full-screen detail with primary position actions, optional expandable evidence, confirmed deletion and repository-level active-watch protection. The self-audit follow-up added an explicit editor to every saved anchoring position instead of silently editing only the first. Final `:app:compileDebugKotlin` passed in 34s; tests were written/updated but deliberately NOT RUN.**
- Real hardware verified: **No — verify nearby transitions, small-screen detail scrolling, Google Maps hand-off and destructive confirmation in manual QA.**
- Status: **FIXED IN CODE — KOTLIN COMPILE PASSED; MANUAL UX QA REQUIRED**

## Finding P1-012 — Anchorage QR was useful only to an existing App user

- Severity: **P1 / offline hand-off and product clarity**
- User story: a friend should be able to scan a shared anchorage card with any phone to see its location, while a Boat Watch user can separately import the richer local record.
- Evidence: the V2 share image contained one large proprietary `anchorwatch://` QR and the instruction “Scan with Anchor Watch”. Generic camera apps could not open the location, despite the image already printing coordinates. The legacy/nearby-card V1 generator was a second independent single-QR path.
- Reproduction steps: share a V2 anchorage image to a phone without Anchor Watch and scan it with the system camera.
- Root cause: navigation and structured import were treated as one QR destination even though they have different compatibility and privacy contracts.
- Failing test: `AnchorageShareContentTest.shareCardKeepsPublicNavigationAndPrivateAppImportAsTwoExplicitDestinations`; existing payload codec and bitmap decoder suites cover the individual encodings.
- Fix commit: **`c45111f`, `94fe279`**
- Verification result: **Both canonical V2 library shares and legacy/nearby-card V1 shares now render a branded two-QR card: a public Google Maps HTTPS location and a separately labelled versioned Boat Watch import payload. The card includes saved parameters, approach note, safety disclaimer, logo and `Developed aboard SV Yokuli`; visits/photos/device data remain excluded. Tests were written but deliberately NOT RUN.**
- Real hardware verified: **No — scan both regions from a shared/compressed image on iOS and Android during QA.**
- Status: **FIXED IN CODE — MANUAL CROSS-DEVICE QR QA REQUIRED**

## Finding P1-013 — The public name described one feature after the App became a boat companion

- Severity: **P1 / product identity, release metadata and upgrade safety**
- User story: the installed App, About page, documentation, share/export images and downloadable builds should be recognisable as Boat Watch, while the Anchor Watch feature and every installed-data identifier remain compatible.
- Evidence: launcher labels in all six locales, `ProductBrand`, onboarding/About/feedback, README/store copy, branded GPX/snapshot/diagnostic output, GitHub workflow titles and artifacts still said Anchor Watch. At the same time the App already contained Trip Watch, NMEA products, sonar, maps and saved anchorages. The existing identity document also declared the old value canonical.
- Reproduction steps: install the current development APK in each locale, open onboarding/About/Feedback, export a trip/backup/share card and inspect Actions artifact labels and the store-copy document.
- Root cause: the App expanded feature by feature without a distinct public-product identity contract; visible branding and compatibility identifiers were treated as the same kind of string.
- Failing test: updated `ProductBrandTest.productIdentityAndConfiguredChannelsAreExact`, `FeedbackEmailComposerTest`, and the About/Feedback Compose stories; `.github/scripts/product_policy_guard.sh` now requires Boat Watch in both resources and `ProductBrand` and rejects the old launcher label.
- Fix commit: **`94fe279`**
- Verification result: **Public identity is Boat Watch across every locale, About/onboarding/feedback, README/store copy, QR and trip exports, release scripts/workflows and new artifact labels. `docs/PRODUCT_IDENTITY.md` now states that Anchor Watch is the anchor-alarm feature. Package/application ID, Room/backup identity, `anchorwatch://` V1/V2 imports, provider/channel/DataStore identifiers and release signing remain unchanged for upgrades. Product policy guard passed; final `:app:compileDebugKotlin` passed in 31s before the 34s anchorage follow-up. Tests were updated but deliberately NOT RUN.**
- Real hardware verified: **No — verify launcher name after in-place upgrade, old backup restore, old V1/V2 QR import and Android mock-location picker identity.**
- Status: **FIXED IN CODE — COMPILE/POLICY CHECK PASSED; UPGRADE AND STORE QA REQUIRED**

## Finding P0-028 — KC-2W 4800-baud bottleneck masqueraded as App feedback

- Severity: **P0 / downstream NO HEADING and hardware interoperability**
- User story: a KC-2W installation carrying the complete Phone/App feed and N2K→0183 converted traffic must have enough 0183 bandwidth; otherwise the App must not be blamed for gaps created after its socket write.
- Evidence: vessel QA showed KC-2W Phone fields stable until Raymarine powered on, then all fields disappeared/reappeared and Raymarine reported `NO HEADING`. With no App code change, switching the KC-2W NMEA 0183 rate from 4800 to 38400 made the data stable. At 8-N-1, those settings provide roughly 480 versus 3840 payload bytes/second before protocol overhead.
- Reproduction steps: configure KC-2W 0183 at 4800, publish the complete 1 Hz Phone/App batch, then power Raymarine/N2K conversion on; observe DataList/Heading gaps. Repeat at 38400; the reported vessel test remains stable.
- Root cause: confirmed KC-2W 0183 serial-bandwidth bottleneck. Raymarine joining increased the aggregate conversion traffic crossing the low-rate leg. The earlier hypothesis that an App echo was causing the supplement to yield is rejected for this incident.
- Failing test: **Manual hardware case `QA-NMEA-KC2W-BAUD`; not representable by a JVM socket test because the bottleneck is inside the converter's serial/N2K bridge.** Defensive regressions `firstFullDuplexEchoIsBlockedBeforeSocketWriteReturns` and `failedSocketAttemptImmediatelyReleasesItsEchoBarrier` cover only a separate first-return race.
- Fix commit: **UNCOMMITTED WORKTREE on `codex/develop`**
- Verification result: **REAL HARDWARE CONFIRMED: KC-2W at 38400 resolved the reported disappearing DataList and `NO HEADING`. Documentation now makes 38400 a precondition for the complete feed. The speculative 30-second unlimited semantic filter was removed; the existing 5-second occurrence-bounded policy remains, with only a short pre-socket barrier added. Barrier tests were written but deliberately NOT RUN.**
- Real hardware verified: **Yes — user verified 4800 failed and 38400 worked with the KC-2W/Raymarine installation.**
- Status: **ROOT CAUSE CONFIRMED AND RESOLVED IN HARDWARE — DOCUMENTED; DEFENSIVE APP BARRIER COMPILED, TESTS NOT RUN**

## Finding P0-029 — NMEA input test and live connection ownership were inconsistent

- Severity: **P0 / formal RX unavailable**
- User story: Connect itself is the only input test and becomes the live connection. A successful connection is retained; a transport error closes it. No disposable test Socket and no replacement Socket may overlap the previous attempt's close path.
- Evidence: old `main` opened `endpointPreflight.check()` and then a second live connection. That was removed, but the later `ERROR` exception allowed Connect to cancel/replace a still-active terminal coroutine before its transport-finally completed.
- Reproduction steps: connect to a refusing endpoint and press Connect as soon as ERROR appears, or inspect the old preflight flow. The unsafe variants either consumed two server sessions or allowed the next generation to begin before the prior coroutine completed closing.
- Root cause: “endpoint test” was modelled as a separate transport, then terminal presentation state was incorrectly treated as proof that transport ownership had already ended.
- Failing test: `NmeaConnectionManagerTest.explicitConnectAfterTerminalErrorWaitsForTheOldAttemptToCloseBeforeOpeningReplacement`, plus the existing one-connection preflight story.
- Fix commit: **UNCOMMITTED WORKTREE on `codex/develop`**
- Verification result: **Save & Connect performs syntax validation and opens exactly one long-lived input transport. TCP-open/no-data remains on that connection, network errors close it, and Connect never replaces any active coroutine, including the short terminal-error close path. Default peer-close auto-retry was raised from 2 seconds to 15 seconds. Main and unit-test Kotlin compilation passed; tests were updated but not run.**
- Real hardware verified: **No — use one controlled attempt against the fragile endpoint.**
- Status: **FIXED IN CODE — KOTLIN COMPILE PASSED; TEST NOT RUN; FRAGILE-GATEWAY QA PENDING**

## Finding P1-030 — Share Start and stopped route presentation contradicted saved configuration

- Severity: **P1 / repeated actions and misleading destination**
- User story: one Start tap must immediately become pending/running, and a saved dedicated TX route must still be labelled dedicated while stopped; Phone NMEA service and Phone/App boat output require separate Stop controls plus an explicit emergency Stop both.
- Evidence: runtime readiness collectors could lag the coordinator's valid readiness by one emission, and ViewModel presentation waited for asynchronous DataStore/runtime collection before disabling Start. When stopped, output status reset to default same-input mode, so UI said same socket despite a saved dedicated port.
- Reproduction steps: finish calibration, tap Start once, or save Dedicated TCP then stop and reopen the status card. Before the fix a second tap could be required and the stopped route read as same-input.
- Root cause: command-time readiness was not passed atomically to the runtime, optimistic UI lease state was absent, and inactive runtime defaults were shown instead of persisted configuration.
- Failing test: existing readiness/start idempotency coverage plus updated product-boundary UI tests; no new device integration test was requested for this pass.
- Fix commit: **UNCOMMITTED WORKTREE on `codex/develop`**
- Verification result: **Coordinator passes its atomic readiness snapshot, Start/Stop updates presentation immediately, stopped UI resolves the saved route, and emergency Stop both products has a single runtime command. Tests were updated but NOT RUN.**
- Real hardware verified: **No.**
- Status: **FIXED IN CODE — KOTLIN COMPILE PASSED; UI QA PENDING**

## Finding P1-031 — A global mount guard conflated heading with sailing attitude

- Severity: **P1 / sensor truth and Trip Report integrity**
- User story: the phone-to-bow heading offset is a durable user alignment; GNSS and pressure are mount-independent; heel/pitch/motion belong only to user-confirmed Trip attitude segments. Picking up the phone must not stop unrelated sensors or a NMEA transport, and a vessel already heeled at confirmation must not be recorded as level.
- Evidence: `PhoneVesselOutputReadinessPolicy` required a version-matched neutral quaternion, mount confirmation and heading alignment as one gate. `VesselDataHub` removed Phone vessel heading whenever mount state was not `VESSEL_MOUNTED`. `PhoneVesselAttitudeRepository` subtracted `neutralQuaternion`, so confirming at 20° heel returned approximately 0°. Settings owned a permanent vessel-zero/mount workflow even though attitude is used for sailing reports.
- Reproduction steps: save a heading offset; recalibrate the attitude mount or transition to handheld and observe heading/output eligibility disappear. Separately, confirm the old neutral quaternion while a test fixture is rolled 20°, then inspect reported heel. Start a Trip, pick up the phone without ending the Trip, and inspect whether invalid attitude samples can enter report statistics.
- Root cause: device direction, vessel heading alignment and vessel-frame attitude were modeled as one calibration lifecycle. The implementation attempted to infer a permanent phone-to-vessel transform from sensors that only observe the phone relative to Earth.
- Failing test: updated `NmeaStreamReadinessPolicyTest.formalOutputRequiresDurableHeadingAlignmentButNotTripAttitudeMount`; `invalidAttitudeSegmentSuppressesOnlyMotionOnEveryTransport`; `AnchorHeadingEvidenceRouterTest.phoneHeadingNeedsAlignmentButNotAnAttitudeMount`; new `PhoneSensorCoordinateFrameTest.confirmingWhileVesselIsHeeledDoesNotZeroTheCurrentHeel`; `headingAlignmentSurvivesAttitudeSegmentReconfirmationAndInvalidation`; report-only short-artifact regressions; updated Trip Start Compose story.
- Fix commit: **Current `codex/develop` delivery commit**
- Verification result: **Heading alignment now survives attitude segment/version changes and is applied to anchor evidence. GNSS/pressure/heading no longer depend on the attitude flag. Trip Start and the running Trip UI own optional attitude placement, manual Pause-before-pick-up and reconfirmation. Current orientation is mapped to vessel axes without zeroing current heel. Trip pause/process restore require attitude reconfirmation. There is deliberately no automatic pick-up detector. Trip Report rejects null/stale/paused samples plus isolated short spikes whose neighbours agree, while retaining sustained heel and GPS-evidenced turns; it displays the recorded route and synchronized speed-versus-heel statistics. The complete Debug JVM suite passed 571 tests with 0 failures and 1 existing conditional skip; Debug APK assembly passed.**
- Real hardware verified: **No — use a phone on a controllable tilted surface and then a sailing hardware run.**
- Status: **FIXED IN CODE — 571 DEBUG UNIT TESTS + DEBUG APK PASSED; MANUAL TRIP QA PENDING**

## Finding P1-032 — Hidden BACKUP arbitration stopped valid Phone/App NMEA fields

- Severity: **P1 / incorrect publication contract**
- User story: when Phone/App boat output is started, every currently valid value owned or explicitly derived by the phone must publish continuously. Raymarine, Simrad, KC-2W or another receiver—not an invisible App policy—selects its preferred source. Direct Boat input must still never be echoed.
- Evidence: `AnchorWatchNmeaPublisher.publishDue()` evaluated `BoatNetworkSupplementPolicy` before encoding every family and returned without publishing when the Vessel Data Hub contained a healthy Boat candidate. Persisted and canonical stream policies were also restored as `PublicationPolicy.BACKUP`, while the UI gave the user no source-priority control.
- Reproduction steps: start Phone/App output with valid aligned phone Heading, then introduce a fresh direct Boat Heading. The old runtime changed the Phone stream to standby after a hold and stopped putting it on the wire even though the user had explicitly started sharing.
- Root cause: a previous “fill only missing instruments” product assumption was implemented as publisher-side source arbitration. That assumption conflicts with NMEA receivers that already own source selection and with the requested complete Phone/App feed.
- Failing test: `NmeaDeviceOutputPolicyTest.legacySameSocketBackupPoliciesMigrateToAlwaysLocalInjection`, `livePublisherConfigCannotReadLegacyPerStreamOrBackupFlags`, plus encoder provenance tests proving Boat observations never enter the local feed.
- Fix commit: **Current `codex/develop` delivery commit**
- Verification result: **The runtime supplement gate and policy class are removed. Persisted legacy policies normalize to ALWAYS for every enabled locally-owned family. Runtime suppression is limited to unavailable/stale local evidence and manually paused Trip Motion. UI and product documentation now state that receivers choose source priority. The complete Debug JVM suite passed 571 tests with 0 failures and 1 existing conditional skip; Debug APK assembly passed.**
- Real hardware verified: **No — verify on the vessel with KC-2W at the already-confirmed 38400 baud setting.**
- Status: **FIXED IN CODE — 571 DEBUG UNIT TESTS + DEBUG APK PASSED; HARDWARE QA PENDING**

## Finding P1-033 — Output Socket ownership was exposed as a user configuration choice

- Severity: **P1 / confusing NMEA setup and duplicate-connection risk**
- User story: the user enters the boat gateway RX and TX addresses. If they are identical, Boat Watch must internally reuse the existing full-duplex TCP connection; if they differ, it creates one independent write-only TX connection. The user must not choose or understand a “same Socket” implementation mode.
- Evidence: the output page presented “use connected NMEA TCP Socket” and “Dedicated TCP client” as separate choices. Saving an independent TCP route equal to RX was rejected as an error even though endpoint equality is enough for the App to select safe reuse automatically.
- Reproduction steps: configure RX and TX with the same TCP host/port, then open Phone/App boat output. Before the fix the user had to choose a Socket ownership mode, and the wrong choice produced a duplicate-endpoint blocker instead of deterministic reuse.
- Root cause: internal transport ownership was persisted and exposed as product configuration rather than derived from the two endpoints.
- Failing test: `NmeaDeviceOutputPolicyTest.matchingTcpEndpointIsAutomaticallyNormalisedToTheExistingInputConnection` and `matchingEndpointChoosesReuseByConfigurationEvenBeforeRxEverOpened`; the raw dedicated writer guard remains as defence in depth.
- Fix commit: **UNCOMMITTED WORKTREE on `codex/develop`**
- Verification result: **`NmeaOutputEndpointPolicy.automatic()` now canonicalises matching TCP endpoints to the existing input connection before readiness, runtime ownership, tests or writes. Different TCP endpoints remain an independent writer. UI exposes only TCP destination host/port plus optional UDP protocol choices; same-Socket wording and blockers are removed. `:app:compileDebugKotlin` passed in 30 seconds and `:app:compileDebugUnitTestKotlin` passed in 26 seconds. Tests were not run, and no real endpoint was contacted.**
- Real hardware verified: **No — verify one accepted gateway client for equal RX/TX and exactly one additional client for a documented different TX port.**
- Status: **FIXED IN CODE — KOTLIN COMPILE PASSED; TEST AND HARDWARE QA PENDING**

## Finding P1-034 — NMEA input hid Connect below diagnostics and repeated the same failure

- Severity: **P1 / routine connection setup was difficult to operate**
- User story: opening Data → Input → Connection should present protocol, server, receive port and Connect together in the first workflow card. Status and the actionable error belong beside that action; counters and Socket generations are optional diagnostics.
- Evidence: the page rendered a full live-diagnostics card and safety/output notices before the endpoint form. Profile name, timeout, checksum and reconnect switches then pushed Connect to the bottom. `connectionAttempt` and `lastDisconnectReason` each rendered their own error surface, so one failed attempt appeared in multiple places.
- Reproduction steps: use a small phone display, open a stopped or failed NMEA input and count the scroll distance to Connect; then attempt an unavailable endpoint and compare the form error with the transport error card.
- Root cause: connection task flow, advanced configuration and technical troubleshooting were flattened into one long `LazyColumn` instead of being presented by user priority.
- Failing test: existing Compose connection stories retain `nmea_connect_input`; the new compact card exposes `nmea_input_connection_card`, `nmea_input_advanced_toggle` and `nmea_input_diagnostics_toggle` for the pending small-screen reachability regression.
- Fix commit: **UNCOMMITTED WORKTREE on `codex/develop`**
- Verification result: **Protocol/address/port/Connect or Reconnect/Stop now share the first card. Profile/timeout/checksum/retry are collapsed under Advanced settings. Technical health is collapsed separately. Validation, attempt and terminal transport failure select one feedback surface; the technical table may retain the last failure as a non-actionable historical row. The 572-test Debug suite initially reported two coroutine timeouts; both failures passed targeted reruns, with the NMEA recovery test corrected to inject a short deterministic retry policy instead of assuming the production 15-second fragile-gateway delay. `lintDebug` and `assembleDebug` passed. No real endpoint was contacted.**
- Real hardware verified: **No — verify on the smallest supported phone and the fragile boat gateway.**
- Status: **FIXED IN CODE — UNIT FAILURES PASSED TARGETED RERUNS; LINT/APK PASSED; SMALL-SCREEN AND GATEWAY QA PENDING**

## Finding P1-035 — Phone heading alignment looked permanent and exposed an internal offset

- Severity: **P1 / heading source setup and recovery were unclear**
- User story: a sailor must be able to realign the phone whenever its orientation relative to the vessel changes. The normal action is “point the phone top toward the bow and align now”; a numeric implementation offset is not a user task.
- Evidence: Settings described alignment as one-time, displayed an editable degree offset and a Confirm button, and did not acquire a Phone-heading display lease while the sensor page was open. This made realignment hard to find and could leave the live verification blank.
- Reproduction steps: complete heading setup once, return after rotating the phone and search for a realign action; open the page with no Watch/Approach runtime owning the compass and inspect whether live device direction updates.
- Root cause: the persisted angular transform was exposed directly as product UI, while sensor runtime demand was owned by map/approach screens rather than the settings surface that required a live sample.
- Failing test: `NmeaStreamReadinessPolicyTest.liveHeadingAlignmentUsesOnlyMatchingNorthReferences`; UI tags `phone_sensor_confirm_heading` and `phone_sensor_align_nmea` remain available for the pending device Compose story.
- Fix commit: **UNCOMMITTED WORKTREE on `codex/develop`**
- Verification result: **The settings row explicitly advertises realignment. Opening the sensor page starts a display-only compass lease. The main action may be repeated at any time and records a zero correction only after a fresh compass sample while the phone points toward the bow. When fresh Phone and NMEA headings share a true or magnetic reference, an optional live-match action computes the shortest signed correction. The offset is no longer editable or shown as required input. The new same-reference/wraparound policy test passed within the Debug suite; `lintDebug` and `assembleDebug` passed.**
- Real hardware verified: **No — verify compass freshness, bow-pointing realignment and optional NMEA match on both phones.**
- Status: **FIXED IN CODE — UNIT/LINT/APK GATES PASSED; TWO-PHONE SENSOR QA PENDING**

## Finding P0-036 — Instrument-only NMEA incorrectly blocked Phone-GPS anchoring

- Severity: **P0 / core Anchor Watch arming blocked**
- User story: a fishfinder may provide valid depth, wind or other instrument data without providing a GPS position. Boat Watch must keep that NMEA input connected for instruments while using Phone GNSS to start a new Anchor Watch session.
- Evidence: with the saved GPS preference set to NMEA, `MainViewModel` disabled `SystemLocationRepository` even when the current NMEA connection had no usable position. The setup UI tried to fall back only after Phone GNSS was already ready, creating a circular wait; Watch health continued evaluating the saved NMEA source and reported “NMEA GPS not fresh”.
- Reproduction steps: save NMEA as the preferred GPS source; connect a fishfinder stream that carries depth but no current-position sentence; open Watch and start anchor setup. The old flow retained NMEA as the effective position source, did not continuously acquire Phone GNSS and blocked arming as stale NMEA GPS.
- Root cause: the persisted preferred position source was incorrectly treated as the effective source for every new session, and NMEA transport/instrument availability was conflated with NMEA position availability.
- Failing test: `NmeaSourceSelectionPolicyTest.instrumentOnlyNmeaFallsBackToPhoneGpsForANewAnchor` plus companion preference/demo cases.
- Fix commit: **Current `codex/develop` delivery commit**
- Verification result: **New-session source routing now resolves an unusable NMEA position preference to Phone GPS without changing the saved preference or closing the NMEA instrument connection. Phone GNSS is acquired before and during setup; Watch status and preflight evaluate the same effective source. A running session remains locked to its explicitly armed source, so an active NMEA watch is never silently switched. Targeted `NmeaSourceSelectionPolicyTest` and `compileDebugKotlin` passed together in 56 seconds.**
- Real hardware verified: **No — verify with the fishfinder stream connected and no NMEA GPS sentences, then confirm Phone GPS can arm while live depth remains available.**
- Status: **SUPERSEDED FOR NEW-SESSION ARMING BY P0-041 — NMEA IS NO LONGER SILENTLY REPLACED BY PHONE GPS DURING A TRANSIENT POSITION FAULT**

## Finding P0-037 — Late NMEA readiness was missed while arming

- Severity: **P0 / valid NMEA position could not start Anchor Watch**
- User story: when the selected boat NMEA server supplies a current valid position, one Start tap must arm from that NMEA position even if the fix and transport-ready state arrive in adjacent asynchronous updates.
- Evidence: the setup form captured its temporary Phone-GPS fallback before the preferred NMEA fix arrived and never promoted itself back to NMEA. Independently, `AnchorWatchRuntime.awaitUsableStartFix()` observed only `navigation.fix`; `NavigationRepository` intentionally publishes the parsed fix before calling `reportValidFix()`, so the waiter could reject the fix while state was `CONNECTED_NO_FIX`, miss the subsequent `CONNECTED` transition, and wait 15 seconds for a duplicate sentence. The generic Chinese translation then hid whether NMEA or Phone GNSS had timed out.
- Reproduction steps: configure NMEA as the preferred position source; open anchor setup while the current connection is transitioning from data-only to its first valid RMC/GGA/GLL position; press Start after the position appears. The old implementation could retain Phone GPS or reject the first NMEA fix and eventually show only “需要实时且新鲜的 GPS 定位”.
- Root cause: the setup source was a one-shot remembered value, and Runtime treated position readiness as a single-flow event instead of the tuple `(connection state, connection generation start, fix, quality)`.
- Failing test: `NmeaPositionAwaiterTest.freshFixWakesWaiterWithoutWaitingForAConnectionLabelPromotion` and the centralized-source Compose regression in `AnchorSetupValidationTest`.
- Fix commit: **UNCOMMITTED WORKTREE on `codex/develop`**
- Verification result: **Setup no longer owns an independent source picker: it derives and reports the centralized Data → Sources choice. The shared NMEA awaiter listens to connection state, fix and connection-generation start together and remains useful for explicit paused-source handover and Resume. P0-041 subsequently removed it from initial arming because GPS health must not be an arming-permission wait. Production, JVM-test and Android-test Kotlin sources compiled in the original pass; tests were not executed in the final UI-centralization pass.**
- Real hardware verified: **No — retest one Start tap against the vessel NMEA server and record whether the source-specific failure appears if it still rejects the stream.**
- Status: **SUPERSEDED FOR INITIAL ARMING BY P0-041 — ASYNC READINESS REMAINS RELEVANT TO EXPLICIT SOURCE HANDOVER/RESUME**

## Finding P0-038 — Global semantic echo filtering deleted real boat GPS

- Severity: **P0 / raw GPS visible but no parsed position**
- User story: a real GPS position received from the configured Boat NMEA input must remain eligible even while Phone/App output or the independent Phone NMEA service is running. Sensors mounted on the same vessel agreeing on position or heading is normal and is not proof of an echo.
- Evidence: direct comparison with `main` (`2627c6e`) showed that its `NavigationRepository` parsed accepted NMEA sentences directly. `develop` later injected the singleton `NmeaOutboundLoopGuard` into both Boat output and the unrelated phone-hosted NMEA server, then called the guard before parsing Boat input. The guard's semantic fingerprint deliberately ignored talker/checksum and matched positions within roughly three metres, so a real fishfinder/boat GPS near the phone could be labelled `[Echoed App TX]` and returned before `parser.parseEnvelope()`. Raw UI still showed the sentence, exactly matching the vessel report.
- Reproduction steps: run Phone/App publication or serve Phone NMEA clients, then receive an independent Boat RMC/GGA at the same vessel position; inspect Raw data and try to arm from NMEA. The regression branch may show the GPS sentence while `nmeaFix` remains empty/stale; old `main` accepts it.
- Root cause: two output products shared one global echo-provenance cache, and approximate semantic equality was treated as transport provenance. This violated the product split and cannot distinguish independent co-located sensors from converted echoes.
- Failing test: `NmeaDeviceOutputPolicyTest.boatInputUsesExactEchoEvidenceAndNeverRejectsANearbyIndependentGps`; companion parser/quality regressions cover GNS and stale GGA quality contamination.
- Fix commit: **UNCOMMITTED WORKTREE on `codex/develop`**
- Verification result: **The independent Phone NMEA service no longer writes to the Boat-input echo guard. `NavigationRepository` now rejects only byte-identical recent App frames; approximate semantic matching remains available for diagnostics but cannot delete Boat GPS evidence. Modern GNS is parsed, an invalid GGA/GNS cannot write quality 0 back into the shared cache, stale bad HDOP no longer poisons a newer RMC/GLL fix, and Raw/health shows the exact latest position rejection reason. Six targeted suites plus `compileDebugKotlin` passed in 2m26s.**
- Real hardware verified: **No — install the new APK, keep the same output/server configuration, and verify that Boat NMEA Position becomes populated before one-tap arming.**
- Status: **FIXED IN CODE — MAIN REGRESSION IDENTIFIED; TARGETED PARSER/QUALITY/ECHO TESTS PASSED; VESSEL QA PENDING**

## Finding P0-039 — One invalid NMEA position source erased another valid GPS

- Severity: **P0 / map shows coordinates but Anchor Watch cannot arm**
- User story: a multiplexed boat network may carry several RMC/GGA/GNS/GLL producers. One source reporting an explicit invalid status must not erase a fresh valid position from another GPS.
- Evidence: `main` kept the last valid aggregate position until freshness expiry. `develop` added exact-source invalidation in `VesselSourceRegistry`, but also called `clearLegacyMeasurements()` on the shared `NavigationRepository._fix`. That aggregate has no physical-source identity, so any invalid position sentence cleared every source. The next valid sentence restored the map, producing the reported visible-coordinate/15-second-arm-timeout contradiction.
- Reproduction steps: feed a valid RMC repeatedly together with an invalid GGA/GLL from another talker or converted device; observe `_fix` alternate between valid and null while Raw data continues to show coordinates, then attempt to arm NMEA Anchor Watch.
- Root cause: a source-scoped negative event was incorrectly applied to a source-agnostic compatibility aggregate. This regression is absent from `main`.
- Failing test: expanded `NmeaSourceSelectionPolicyTest.freshCurrentGenerationFixWinsOverTransientTransportPresentationLabels`; existing `NmeaSourceInvalidationTest.invalidStatusMarksOnlyMatchingTransportGenerationAndMetricsInvalid` proves the authoritative registry keeps invalidation source-scoped. A real multiplexed-stream device case remains in manual QA.
- Fix commit: **Current `codex/develop` delivery commit**
- Verification result: **Exact invalidation remains in `VesselSourceRegistry`; the source-agnostic map/Anchor aggregate now preserves its last valid observation and expires by receive time. Fresh current-generation fixes are accepted during transient CONNECTED_NO_FIX/CONNECTED_NO_DATA/STALE presentation labels, but DISCONNECTED/CONNECTING/RECONNECTING/ERROR, old-generation fixes, expired fixes and current bad HDOP/fix quality remain blocked. Production, JVM-test and Android-test Kotlin sources compile; tests were not executed in this pass.**
- Real hardware verified: **No — connect the existing vessel stream, confirm a continuously visible NMEA position, and start Anchor Watch once.**
- Status: **FIXED IN CODE — PRODUCTION AND TEST SOURCES COMPILE; VESSEL QA PENDING**

## Finding P1-040 — GPS controls were split across Anchor, Data and Settings

- Severity: **P1 / safety-source UX ambiguity**
- User story: the sailor should have one discoverable place to select Phone/NMEA/Demo GPS and manage the Android GPS proxy, while Anchor setup only confirms which source will be used.
- Evidence: Settings exposed Positioning and Developer Demo pages, Data exposed a separate instrument-position routing strategy, and Anchor setup exposed another Phone/NMEA radio group. Recovery actions also jumped to Settings or changed the source directly from Watch.
- Reproduction steps: try to answer “which GPS will the next anchor session use?” by visiting Data, Settings and Set anchor; several controls use similar labels but different scopes.
- Root cause: feature-specific controls accumulated without a single navigation owner or clear separation between safety GPS selection and App-instrument routing.
- Failing test: updated `AnchorSafetyFlowTest` GPS-source/proxy stories; new `AnchorSetupValidationTest.anchorSetupReportsTheCentralGpsChoiceButDoesNotOfferAnotherPicker`.
- Fix commit: **Current `codex/develop` delivery commit**
- Verification result: **Data → Sources is now the only UI owner for App GPS selection, Demo GPS and the Android GPS proxy. Settings removes the duplicate Positioning and Developer entries. Anchor setup shows a read-only source/readiness summary and a single link to Data → Sources. Watch/Alarm recovery also opens the central source page. Instrument routing remains below it with explicit copy that it controls App instruments/calculations rather than Anchor GPS or NMEA publication. Production, JVM-test and Android-test Kotlin sources compile; tests were not executed in this pass.**
- Real hardware verified: **No — verify navigation and small-screen scrolling on the phone.**
- Status: **FIXED IN CODE — PRODUCTION AND TEST SOURCES COMPILE; MANUAL UX QA PENDING**

## Finding P0-041 — Runtime GPS health was incorrectly used as permission to create an anchor session

- Severity: **P0 / every Set anchor attempt could be rejected despite a visible boat position**
- User story: pressing Start after confirming an anchor coordinate must create and activate the anchor session immediately. A disconnected/reconnecting NMEA transport, an old connection generation, a stale fix, missing GPS or current poor HDOP/accuracy must be reported during the active watch, not prevent the session from existing. Unsafe fixes must still never enter distance, track or centre-estimation calculations.
- Evidence: `AnchorSetupSheet.valid` required strict source readiness; `WatchPreflightEvaluator` independently classified missing/stale/poor NMEA GPS and network as blockers; `AnchorWatchRuntime.arm()` then called `awaitUsableStartFix()` and returned without inserting the Room session if its full connection-generation/freshness/quality tuple did not become acceptable within 15 seconds. A parsed fix can be visible on the map while any of those independent presentation gates is false, making the failure deterministic rather than an occasional GPS dropout.
- Reproduction steps: select NMEA in Data → Sources, retain a valid displayed boat coordinate, then let the input be reconnecting, older than 15 seconds or report an unacceptable current HDOP. Open Set anchor and Start. The previous branch either disabled the form/preflight or spun for 15 seconds and reported that no fresh boat position existed; no anchor session was created.
- Root cause: the product had one strict policy suitable for trusted runtime movement calculations and reused it in three layers as an arming-authorisation policy. The later wait fixed an asynchronous first-fix race but made the architectural mistake more visible. Silent NMEA→Phone fallback further contradicted the centralized explicit source choice.
- Failing test: updated `WatchPreflightEvaluatorTest` proves stale/disconnected/poor NMEA is a warning while notification permission remains a blocker; updated `NmeaSourceSelectionPolicyTest.selectedNmeaRemainsAuthoritativeDuringATransientOutage` proves the selected source does not silently change. Runtime/UI device stories remain written for the next device gate and are not executed in this pass.
- Fix commit: **Current `codex/develop` delivery commit**
- Verification result: **Initial arming no longer waits for or requires a live fix. It validates the confirmed anchor geometry, inserts and activates the session, starts the selected source resources and submits an initial fix only when that fix passes the existing current-generation/freshness/quality policy. Otherwise it records `SESSION_STARTED_GPS_DEGRADED`, posts an immediate high-priority warning and lets the existing GPS-loss/quality alarm plus automatic recovery continue. Active NMEA/System submissions now apply that strict health gate before distance, track or estimator input. Setup and Watch show degraded health without disabling Set anchor; Preflight presents GPS/NMEA/network faults as proceedable warnings. Data → Sources remains authoritative and no transient NMEA fault silently switches the session to Phone GPS.**
- Real hardware verified: **No — verify one Start tap with the current vessel feed, then interrupt/recover NMEA and confirm the same session remains active while unsafe fixes never move the plotted boat/track.**
- Status: **FIXED IN CODE — PRODUCTION/JVM-TEST/ANDROID-TEST SOURCES COMPILE; TESTS NOT RUN; REAL-VESSEL QA PENDING**
