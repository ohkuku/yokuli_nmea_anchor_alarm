# Pre-feature Stabilization Report

## Baseline

- Requested reference in the supplied specification: `683110b24deb969fe5a0958b7de646230aa9bc7d`
- Actual clean commit beneath this working pass: `66112c0efe9d64e5a31541844ad6d40eccb47c6e`
- Branch: `codex/develop`
- Room schema: 20 (`exportSchema = true`)
- Backup format: 5

## Product fixes delivered in this pass

### P0 user flows

- **Set anchor:** setup no longer starts on an unavailable remembered NMEA source. Start keeps the form visible, shows progress, closes only after an active Room session appears, and shows an inline failure after the bounded wait instead of looking like a dead tap.
- **Map gestures:** the Watch bottom sheet no longer intercepts vertical Google Map drags. Its handle explicitly expands/collapses the sheet; map pan and pinch remain usable even while follow-lock is enabled, followed by delayed recentering.
- **NMEA RX:** Save & Connect manages only input. Validation uses the actual long-lived socket, avoiding a second preflight connection that can occupy a fragile marine gateway.
- **NMEA TX:** RX and TX ports are configured independently. Choosing Separate TX only selects a draft; validation occurs after host/port input. Route controls appear before the explicit Start card.
- **Output usability:** one canonical heartbeat feed replaces granular product switches. It holds unchanged valid values, excludes stale/null/losing data, requires vessel-frame calibration and never auto-starts.

### Anchorage library

- Fixed the split in which the GIS save flow wrote `anchorage_places`/`anchorage_spots` while Watch and History still collected `saved_anchorages`.
- User-visible saved anchorages, nearby cards, Watch markers and Approach clusters now project from canonical Places/Spots.
- Region browsing reads real `AnchorageRegionEntity` rows. Save-time candidates come from cached/user regions plus LINZ Gazetteer where configured and applicable.
- LINZ region features provide classification candidates only. They are not silently presented as public recommended anchorages.
- Editing/deleting the History projection writes/deletes the canonical Spot/Place, protecting a Place used by the active Anchor session.

### Automatic Anchor heading evidence

- Removed setup and active-session heading enable switches.
- Removed the public ViewModel method, Service action and Runtime command that could enable/disable evidence.
- Boat true heading, gated mounted Phone true heading, AUTO conflict handling and exact pinned-source behavior are centralized in `AnchorHeadingEvidenceRouter`.
- Old `usePhoneHeading` and `headingEvidenceEnabled` columns remain backup/schema compatible but are upgraded to true and ignored as gates.
- Accepted source changes create an evidence epoch without resetting GPS geometry or moving the adopted centre.

### Map model

- Base styles are Standard, Satellite and Nautical.
- Nautical chooses Default online or an imported MBTiles primary source. Missing/unselected/removed MBTiles always fall back to the online nautical source rather than a blank map.
- LINZ NZ chart enhancement and personal sonar are independent overlays. Only LINZ exposes opacity; sonar uses fixed 75%.
- Current LINZ/personal depth readouts are separate from chart sources.
- Settings owns chart files/storage only. All current display mutations live in Map → Layers.
- Z order keeps safety geometry, anchor and boat markers above charts and sonar.

### Vessel data semantics

- Canonical observations expose selection reason and preserve separate measurement/heartbeat timestamps.
- Held observations remain visibly held and may be heartbeat-published; stale observations cannot be output or used as Anchor heading evidence.
- Map heading uses the Vessel Hub canonical heading rather than a local priority tree.
- Data source details show measurement age, heartbeat age, quality, state, provenance, selection reason and conflicts.

## Deprecated compatibility paths

- Room/backup heading booleans are retained but no longer control runtime behavior.
- Old per-stream NMEA output fields decode existing DataStore/backup records but have no product mutation UI; Start normalizes the feed to canonical.
- The old saved-anchorage table remains for backup/migration compatibility but is not collected by live UI or Approach.
- The persisted offline-map boolean is interpreted as the two-value nautical source preference until a future low-risk DataStore migration.

## Test coverage changed

- Router: physical NMEA only, Phone integrity channel, mounted/handheld, AUTO fallback, conflict suppression, explicit pin, held/degraded rejection and COG exclusion.
- Map: stable persisted base values, mutually exclusive rendering, online fallback, installed-but-unselected MBTiles, selected MBTiles, removal fallback and independent overlay matrix.
- NMEA output: independent TX endpoint, runtime lease never auto-starts, restore clears lease, canonical families, held heartbeat, stale exclusion, echo quarantine and dedicated-TX/RX isolation.
- Anchorage story: session history alone creates no nearby target; canonical Place/Spot saves create clusters and Approach geometry.
- Device regression: legacy disabled heading fields upgrade to automatic evidence without changing historical points.

## Verification status

> Historical note: the results below belong to the stabilization pass completed before the current P0 user-story reset. They were not rerun for `codex/p0-user-story-reset`; the current branch remains `NOT RUN` for Gradle, lint, emulator and device verification as recorded in `CODEX_OVERNIGHT_FINAL_REPORT.md`.

- `:app:compileDebugKotlin`: passed after the product changes.
- `:app:compileDebugUnitTestKotlin`: passed after the product changes.
- Focused unit suite (Heading router, map source/model, NMEA output/retention/decoder and configuration ownership): passed.
- `:app:compileDebugAndroidTestKotlin`: passed, including the canonical Anchorage Approach story and legacy Heading compatibility test.
- `:app:lintDebug`: passed.
- `:app:assembleDebug`: passed; debug APK generated.
- The previous five reported device-test failures were fixed and rerun by failed item before this stabilization pass; a full 72-test emulator rerun is intentionally not repeated here.

## Known limitations / follow-up

- Anchor conservatively keeps Phone heading runtime available during an estimation epoch. A later optimization may stop Phone sensors when preference is explicitly Boat and the selected Boat heading remains healthy; this must not reintroduce a manual Anchor switch.
- DataStore still contains compatibility fields for old output stream controls and the imported-chart preference. Removing them requires a separately reviewed migration, not a UI cleanup patch.
- Official LINZ Gazetteer coverage is New Zealand-only and network-dependent when no cached candidate exists. Saved Place/Spot/Region data remains offline.
- Imported MBTiles tile gaps fall through to default nautical rendering; source attribution and chart suitability remain the user's responsibility.

## Changed areas

Runtime/position acceptance, Vessel Hub/output, map/domain/UI, NMEA input/output UI, anchorage repositories/DAO/UI, focused unit/device tests, and the three stabilization audit documents were changed. No Room table or backup-format migration was required.
