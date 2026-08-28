# Sail / Trip marine map and square-screen implementation

Starting branch: `codex/develop`
Starting SHA: `68a137b3690a9a1f7ce7daec9889f8f69ffa715e`

## Data contract

- `TripRuntime` remains the only producer of canonical `TripSampleEntity` rows.
- Schema 22 adds a per-trip monotonic `recordingSequence` and a unique `(tripId, recordingSequence)` index.
- Each canonical sample goes to the bounded writer queue and `TripTrackRepository.liveTail`.
- Successful Room batches move the identical sample into bounded persisted display geometry and remove its live-tail identity.
- Process restoration pages Room data through `samplesPage`; map UI never loads an unbounded raw trip query.

## Rendering budgets and gaps

| Surface | Maximum display points |
| --- | ---: |
| Trip history card thumbnail | 256 |
| Sail Overview preview | 512 |
| Live Trip detail | 2,500 |
| Trip History detail | 5,000 |

The most recent 384 points remain high resolution. Older display geometry is sampled without modifying Room. A null position, a discontinuity over 15 seconds, or an implausible spatial jump closes the current polyline segment.

## Cockpit

Overview is the default first page. Sail, Nav, Motion, Weather and custom dashboards retain their existing order and customization after it. The embedded map is non-interactive and opens a dedicated full-screen destination. The refactored rose is north-up and compares only true HDG/COG/TWD; magnetic-only heading remains numeric, and AWA/TWA remain explicitly relative.

## Maps

- Sail Overview: one non-interactive live GoogleMap, with a clear full-map action.
- Trip history cards: Canvas thumbnails, never one GoogleMap per list row.
- Live/History Trip detail: interactive full-screen map, segmented track, waypoints, follow/recenter, fit, ruler, scale and Normal/Satellite base style.
- Anchorage Place/Spot detail: interactive full-screen map from the current GIS library, all saved spots, current vessel when available, distance/bearing, fit/recenter and Approach.
- Anchor Watch retains its existing safety-owned map state. Only additive map capability/UI-setting primitives were shared.

## Adaptive modes

Post-inset constraints choose `COMPACT_SQUARE`, `COMPACT_PORTRAIT`, `REGULAR_PORTRAIT` or `WIDE`. The square Overview uses a shallow map strip, compact rose, four core metrics and the existing always-reachable Trip controls.

## Verification

- JVM unit tests: 647 total, 0 failed, 0 errors, 1 existing skip (646-test full suite plus the final compaction-gap regression).
- Android instrumentation sources: compiled.
- Android lint: passed (no baseline).
- Debug APK: assembled.
- Emulator/API 36/device integration: not executed in this local run.
- Real square phone and vessel hardware: `UNVERIFIED_HARDWARE`.
