# Vessel Data Flow Audit

## Canonical path

```text
NMEA sentences          Android GNSS / sensors          derived values
      │                           │                           │
      ├──── decode + validity ────┴──── candidate mapping ────┤
      │                                                       │
      └────────────── VesselSourceRegistry ───────────────────┘
                              │
                    VesselSourceArbitrator
                 preference · pin · quality · conflict
                              │
                       VesselDataHub
                   canonical VesselObservation
                              │
          ┌──────────┬────────┼────────┬──────────┐
         Data       Sail     Map      Trip      NMEA TX
                              │
                 AnchorHeadingEvidenceRouter
                    (stricter safety gate)
```

Data, Sail, the boat marker, Trip recording/reporting, true-wind inputs and the canonical NMEA output consume the selected Hub observation. They do not implement local Boat → Phone → COG fallback trees.

Anchor uses the same heading preference and selected observation, then applies stricter evidence rules. COG remains a course/motion observation and never becomes vessel heading.

## Measurement time versus source heartbeat

Every candidate/canonical observation can carry two clocks:

- `receivedElapsedRealtime`: last numeric measurement accepted for that metric.
- `sourceHeartbeatElapsedRealtime`: last coherent sentence/field heartbeat proving the same source is still alive.

An unchanged or blank-allowed DBT/DPT, heading, pressure, wind or speed update may refresh the source heartbeat while retaining the numeric measurement timestamp. The Hub presents this as `HELD`, not as a new measurement. Bounded freshness policy eventually changes it to `STALE`.

Position is deliberately different: blank RMC/GLL/GGA coordinates are never held as a new position. A previous coordinate may remain in history, but no blank sentence refreshes its safety age.

Pressure trend samples are added only for numeric measurements; a blank MDA heartbeat cannot manufacture a flat trend.

## Explicit invalidation

The following invalidate their affected source immediately instead of retaining the last value:

- RMC `V`
- GGA fix quality `0`
- GLL `V`
- MWV `V`
- ROT `V`

Invalidation is published to `VesselSourceRegistry`, legacy live wind/depth holders and downstream selection. It therefore reaches Data, Trip current snapshot, output eligibility and Anchor evidence consistently. A null/blank field without an explicit invalid status is not automatically treated as invalid; retention is metric- and sentence-scoped.

## Provenance contract

A canonical `VesselObservation` exposes:

- value and unit (defined by its metric);
- source identity/class and sentence or phone/derived origin;
- measurement age and source-heartbeat age;
- quality and `FRESH` / `HELD` / `STALE` state;
- arbitrator `selectionReason`;
- source conflict diagnostics.

Data → Sources shows these values independently. A heartbeat cannot make the displayed measurement age look new.

## Heading presentation and Anchor evidence

Presentation can remain responsive while Anchor evidence pauses:

| Selected heading | Presentation | Anchor evidence |
|---|---|---|
| Fresh GOOD physical NMEA true heading | Yes | Yes |
| Explicitly pinned fresh GOOD source with another conflicting candidate | Yes, conflict shown | Yes, selected pin only |
| AUTO with unresolved conflict | Selected value may remain visible with conflict | No (`AUTO_SOURCE_CONFLICT`) |
| Mounted, calibrated, aligned stable Phone true heading | Yes | Yes |
| Phone moving/handheld/mount suspect | Responsive device heading may remain visible | No |
| COG only | Course shown | Never heading evidence |
| No heading | No heading tile/value | Anchor continues GPS/rode/wind/COG geometry |

When accepted evidence changes source, Anchor starts a new heading-evidence epoch, records `ANCHOR_HEADING_EVIDENCE_SOURCE_CHANGED`, and excludes old heading samples from the new epoch. GPS geometry, track, wind and COG history are retained; the adopted centre never moves automatically.

## NMEA input and output lifecycle

- RX is one long-lived `NavigationRepository` socket. Endpoint validation occurs on that socket; there is no disposable test client that can consume a fragile single-client gateway.
- An opened-but-quiet socket is kept alive and continues listening/reconnecting. Repeated Save & Connect is not used as a polling strategy.
- Explicit Stop Input calls `disconnectAll()` only after active safety owners are resolved, closing the socket and clearing stale owner latches.
- Dedicated TX owns a separate write-only socket/port. TX failure never closes or reconnects RX.
- Same-socket TX exists only for gateways that explicitly support bidirectional TCP.
- Output never auto-starts. It requires saved route, completed vessel-frame calibration/mount confirmation and an explicit Start in the current app run.
- The product feed publishes the canonical selected values at a stable heartbeat. `FRESH` and bounded `HELD` measurements are emitted; `STALE`, losing-source and null values are not.
- Recently transmitted sentences echoed by a gateway are quarantined and cannot re-enter the Hub as independent boat evidence.

## Saved anchorage data convergence

The canonical library is Room Place → Spot → Visit plus Region metadata. Watch nearby cards, history projection, map markers and Approach clusters now all derive from active Places and Spots. The legacy `saved_anchorages` table is not a live presentation source.
