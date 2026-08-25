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

## Anchorage Library FINAL pass lock

- Base commit: `f22c8fe`
- Working branch: `codex/anchorage-library-final`
- Room schema: `20 → 22`
- State-machine commit: `ee00062`
- Library/schema implementation commit: `e3b02a0`
- Categorical assessment commit: `fc6176b`
- Verification: **533/533 Debug JVM tests passed; `lintDebug` passed; `assembleDebug` passed; unit and instrumentation Kotlin sources compiled. Emulator/device tests were not run.**

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
- Verification result: **Passed `NmeaConnectionManagerTest` (9/9) and `P0NmeaEndpointStoryTest` (2/2) using loopback fake endpoints. Final `assembleDebug` passed in 30s. No real endpoint was contacted.**
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
- Failing test: `AnchorWatchNmeaPublisherTest.constantHeadingHasFiveHertzHeartbeatForTenMinutesWithoutBlankSentence`; `boatCandidatesNeverSuppressOrRewriteTheSelectedAnchorWatchHeading`; `stopInvalidatesEveryOldPublicationGeneration`; `NmeaDeviceOutputPolicyTest.stopClosesAnInFlightConnectCandidateInsteadOfWaitingForTimeout`; `NmeaSharingServerTest.listeningWithoutAClientNeverClaimsASentenceWasSent`.
- Fix commit: **`f22c8fe`**
- Verification result: **One `AnchorWatchNmeaPublisher` now generates Phone GNSS, calibrated phone vessel heading/motion, phone pressure and App-derived wind. TCP client/server, UDP and advanced same-socket consume that same bounded latest-value feed. Legacy Sharing migrates stopped; raw Boat forwarding and the second runtime were deleted. Stop invalidates generation, closes connect candidates/transports, waits for the writer lease, clears Live TX and retains a separately labelled last session. `testDebugUnitTest` passed 523/523, `lintDebug` passed, and `assembleDebug` produced the APK. Tests used only deterministic clocks, loopback/fake TCP endpoints and local Android build tools; no real NMEA endpoint was contacted.**
- Real hardware verified: **No — do not connect repeatedly to the user's fragile gateway. Run the manual receiver matrix once with packet capture.**
- Status: **FIXED — UNIT/LINT/DEBUG BUILD PASSED; AWAITING ONE CONTROLLED HARDWARE QA RUN**

## Finding P0-016 — Saved anchorage discovery and approach had two competing identities

- Severity: **P0 / navigation-state integrity**
- User story: a saved Place/Spot may be Nearby, Approaching, Arrived, linked to an active anchor, or in departure cooldown—but never several at once. Editing or map clustering must not change the selected target.
- Evidence: `MainViewModel` persisted `selectedApproachClusterId` while the map rebuilt `saved:*` clusters; `GisNearbyAnchorageCard` separately queried Place/Spot rows and the Watch bottom sheet rendered a second legacy nearby prompt.
- Reproduction steps: save several close Spots, enter the one-nautical-mile trigger, start Approach, edit/delete a nearby record, then background/restore the App. Observe duplicate prompts and target identity derived from a visual cluster.
- Root cause: normalized Place/Spot storage had been projected back through the legacy SavedAnchorage cluster state instead of owning one explicit product state.
- Failing test: `AnchorageExperienceStateTest`; rewritten `AnchorageApproachStoryTest`.
- Fix commit: **`ee00062`**
- Verification result: **A persisted, mutually exclusive Place/Spot state machine now owns Browsing, Nearby, Approaching, Arrived, Anchored and DepartureCooldown. Guidance and map selection use stable Room IDs; the duplicate Watch-sheet prompt and live cluster projection were removed. `AnchorageExperienceStateTest` passed as part of the 533/533 JVM gate; all instrumentation sources compile.**
- Real hardware verified: **No — execute Anchorage QA section on a real moving phone/boat source.**
- Status: **FIXED — JVM/LINT/DEBUG BUILD PASSED; AWAITING MANUAL GPS QA**

## Finding P1-017 — Anchorage save and detail flows hid decisions in long AlertDialogs

- Severity: **P1 / destructive choice clarity and accessibility**
- User story: saving a completed session is a stepped Place → Spot → Visit decision; an 80 m Spot is allowed; save success identifies exactly what was created and can be undone. Details must be a complete scrollable surface.
- Evidence: the old save dialog mixed region, Place, Spot, notes and review in one list; compatibility code rejected a fixed 75 m duplicate; detail used an oversized `AlertDialog` and protection used eight abbreviated chips on one row.
- Reproduction steps: save a session near an existing Spot with large or small coordinate uncertainty; on a narrow/high-font-scale phone attempt to review every field and protection sector.
- Root cause: a legacy card editor remained in the write path after the normalized repository and uncertainty matcher were introduced.
- Failing test: `AnchorageMatchEnginesTest.anEightyMetreSpotIsAllowedWhenUncertaintyDoesNotOverlap`; `AnchorageSaveFlowRepositoryTest.completedSaveCanUndoOnlyItsNewPlaceSpotAndVisit`.
- Fix commit: **`ee00062`, `e3b02a0`, `fc6176b`**
- Verification result: **Save is now a full-screen three-step flow, requires an explicit Spot decision when matches exist, creates immutable Visit snapshots and an optional categorical personal assessment transactionally, reports Place/Spot IDs and supports scoped Undo. Detail is full-screen; map selection is compact; wind/swell protection uses separate 3×3 compass editors. Fixed-distance rejection was removed. JVM/lint/build gates passed and all device tests compile.**
- Real hardware verified: **No — high font scale, TalkBack and rotation remain manual QA items.**
- Status: **FIXED — JVM/LINT/DEBUG BUILD PASSED; AWAITING UI QA**

## Finding P1-018 — Legacy visit counts were presented twice after GIS migration

- Severity: **P1 / data correctness**
- User story: a legacy summary count remains a summary; the one linked migrated Visit must not be added to that same count again.
- Evidence: migration 19→20 copied `saved_anchorages.visitCount` into both `legacyVisitCount` and `visitCountCached`, while UI totals add those columns.
- Reproduction steps: migrate a legacy row with `visitCount=3` and a valid source session; open the Place list and compare the displayed count with the source row.
- Root cause: cached normalized Visits and imported aggregate history were not separated during migration.
- Failing test: `Migration5To6Test.migration19To20KeepsEveryLegacyRowAndCreatesPlaceSpotVisitLinks` schema-21 assertions.
- Fix commit: **`e3b02a0`, `fc6176b`**
- Verification result: **Schema 21 renames the assessment table and fixes duplicated legacy counts; schema 22 replaces legacy integer assessment columns with the FINAL categorical contract. Old star values are normalized conservatively and retained as compatibility metadata. Visit refresh subtracts the imported linked-session baseline. Migration and repository instrumentation sources compile; both exported schemas are committed.**
- Real hardware verified: **Not applicable; Room migration test is the authority. Device migration test is written but not executed in this turn.**
- Status: **FIXED IN CODE — MIGRATION SOURCE COMPILES; AWAITING DEVICE MIGRATION GATE**
