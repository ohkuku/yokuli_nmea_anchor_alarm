# Anchorage Library current-state audit

Audited branch: `codex/anchorage-library-final`  
Audited baseline: `f22c8fe`  
Pre-redesign database schema: Room `20`, exported in `app/schemas/com.yokuli.anchorwatch.data.database.AppDatabase/20.json` (the implementation work following this audit advances it to 21)

This audit records the checked-out implementation before the FINAL anchorage-library convergence work. It is evidence, not a restatement of the target document.

## What already exists

- Normalized GIS entities already exist for Region, Place, Spot, Visit, Collection, protection sectors, facilities, personal ratings, photos, summaries, search FTS and region packs.
- `Migration19To20` migrates every legacy `saved_anchorages` row independently to one Place and one Spot. A valid linked anchor session becomes a Visit. The legacy table remains available for compatibility.
- Region candidates, spatial RTree queries, map/list library browsing, search, filters, collections, photos, LINZ Gazetteer candidates, QR v1/v2, backup adapters and immutable Visit snapshots already have production implementations.
- Anchor history launches a session-derived save flow. The draft freezes its selected anchor position and session values before the UI begins.
- The new library has repository classes and a dedicated `AnchorageLibraryViewModel`; it is no longer only a direct DAO projection in `MainViewModel`.

## Conflicting or incomplete implementation

### Two live identity models

- `AnchorageApproachRepository` projects normalized Spots back into `SavedAnchorageEntity`.
- `MainViewModel` then runs `AnchorageClusterer` and stores `selectedApproachClusterId` plus member IDs.
- `GisNearbyAnchorageCard` independently queries stable Place/Spot records while `WatchPanel` also displays the legacy cluster-based nearby card.
- Result: the same physical saved location can appear twice, a selected target can change identity after an edit, and nearby/approaching/anchored states are not mutually exclusive by construction.

### Nearby and approach lifecycle

- Nearby dismissal is memory-only and split across two trackers/ViewModels.
- Selected Place/Spot, approach episode, cooldown and suppressed Place IDs are not restored after process death.
- Starting approach suppresses the visible legacy prompt, but the product state is not represented by one explicit state machine.
- Arrival is only an `ApproachPhase`; it does not replace Nearby as a persisted experience state.

### Save flow

- Session draft creation and transactional Place/Spot/Visit save already exist.
- The current UI is one long `AlertDialog`, not a stepped full-screen/large-sheet flow.
- The compatibility `AnchorageApproachRepository.save` still throws a duplicate exception for a fixed 75 m threshold. This conflicts with the uncertainty-aware Spot matcher and prevents legitimate additional Spots.
- Save success does not identify the exact Place/Spot, offer View, or provide transaction-scoped Undo.

### Library and detail UI

- The library defaults to map and supports map/list, search, region and basic filters.
- Marker tap immediately opens the full detail `AlertDialog`; it does not open a compact Place sheet first.
- The full detail surface is still an `AlertDialog` and protection editing is an abbreviated tap-to-cycle row.
- Low/medium zoom aggregates Places, but Region-level aggregation and a selected-Place Spot layer need clearer separation.

### Schema and migration risks

- The target name `anchorage_personal_assessments` is currently implemented as `anchorage_personal_ratings`.
- Migration 19→20 copies legacy `visitCount` into both `visitCountCached` and `legacyVisitCount`; presentation adds both and can double-count historical visits.
- Visit observations are stored separately from protection and do not currently overwrite protection sectors, which is correct.
- Eight wind and eight swell sectors round-trip through the normalized table; missing sectors represent `UNKNOWN`.

### Backup and QR

- QR v1 is adapted to an unverified imported Place/Spot draft. QR v2 carries a Place summary and one Spot without Visit history.
- Existing backup code includes the normalized anchorage tables and retains a legacy adapter. Migration/restore fixtures still need a single release gate proving old NDJSON and QR v1 counts.

## Legacy UI and tags to remove from live flows

- `saved:*` and `spot:*` runtime cluster IDs.
- `AnchorageClusterIdentityResolver` as live target ownership.
- `nearbyAnchoragePrompt` and the legacy `NearbyAnchorageCard` inside the Anchor Watch bottom sheet.
- `AnchorageDetailsPolicy` routing based on cluster member IDs.
- Fixed-distance duplicate rejection in `AnchorageApproachRepository.save`.

Legacy entities/codecs may remain read-only for migration, old backup restore and QR compatibility. They must not remain the source of live map, Nearby, Approach or save identity.

## Construction order from this audit

1. Add and persist one mutually exclusive Place/Spot experience state machine.
2. Make Nearby and Approach consume stable Place/Spot IDs and remove the duplicate legacy prompt.
3. Correct schema naming/count migration and add migration fixtures.
4. Replace fixed-distance duplicate rejection with the uncertainty-aware Spot decision.
5. Convert save/detail into non-AlertDialog surfaces and complete result/undo behavior.
6. Finish compact map selection, explicit protection editing, backup/QR regression and scale/accessibility gates.
