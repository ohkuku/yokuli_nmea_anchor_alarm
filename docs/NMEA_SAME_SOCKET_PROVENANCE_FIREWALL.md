# NMEA output destination semantics and provenance firewall

## Product invariant

Every NMEA Output destination is **Phone/App Sensor Injection**. Transport
choice changes only where bytes go. It never changes which sources are
eligible and never turns Anchor Watch into a Boat-data repeater.

`SAME_AS_INPUT_CONNECTION` reuses the one already-open full-duplex Boat TCP
Socket and never creates a second Boat connection. Explicit advanced
destinations (`DEDICATED_TCP`, `TCP_SERVER`, UDP unicast and UDP broadcast)
receive exactly the same Phone/App-owned feed.

## All-transport source decision

A directly measured value is accepted only when its source proves that it is
owned by the Phone:

- real, non-mock Android GNSS;
- calibrated Phone vessel/device heading;
- Phone IMU;
- Phone barometer;
- an explicit `APP_DERIVED` true-wind result computed by Anchor Watch.

It is rejected when it is:

- `BOAT_NMEA`, including an AUTO-selected Boat candidate;
- `PHONE_TX_ECHO`;
- Demo, mock, network/coarse, unknown or another non-local source.

Direct Boat data is never eligible. A true-wind result is eligible only when
the output observation itself has `APP_DERIVED` identity and explicit
`VesselProvenance.Derived`; this permits a value Anchor Watch actually
calculated, while a Boat MWD/MWV/VWT value remains input-only. Any returned TX
echo is quarantined and direct Boat input can never become the next output.

## Transport generation

Boat identities carry input profile and connection generation. Same-input
output batches also carry the input generation that existed when they were
created. The writer rejects a batch if reconnect N+1 has replaced generation
N. The provenance policy rejects Boat input regardless of destination and
distinguishes a current-transport Boat source in diagnostics.

## Stream rules

### Position

RMC/GGA/VTG/ZDA are generated directly from one accepted real Android GNSS
`NavigationFix`. Latitude, longitude, SOG, COG, UTC, altitude and accuracy are
never assembled from independently selected vessel observations. Boat SOG/COG
therefore cannot leak into a Phone RMC or VTG. Selecting NMEA as the App or
Anchor position source does not change the independent Phone output source.

### Heading

Publication searches explicitly for a fresh calibrated Phone vessel/device
heading candidate on every transport. A Raymarine/KC-2W Heading may create a
visible source conflict, but does not suppress or replace the Phone HDT/HDG
heartbeat.

### Motion and pressure

ROT/heel/pitch use Phone IMU only. Pressure uses Phone barometer only. External
values remain available to `VesselDataHub` but never enter any output feed.

### Depth, STW and wind

Depth and STW are Boat metrics and are absent from the publisher on every
transport. Direct Boat wind is also absent. Anchor Watch may publish only a
true-wind result carrying explicit App-calculation identity and provenance.

## Echo handling

The provenance firewall and outbound echo quarantine are separate layers.
`NmeaOutboundLoopGuard` consumes bounded occurrences of recently transmitted
exact/semantic frames before they reach the source registry. A short barrier is
installed before socket IO and confirmed only after a successful write. An echo identity,
if one reaches the provenance boundary, is denied explicitly.

## Publication ownership

Normal Phone/App streams use `ALWAYS`, not `BACKUP`. A stream publishes while
its eligible local/App sample is fresh and valid, required calibration is
valid, and the transport is writable. External-source presence is diagnostic
only. Stale or invalid eligible evidence suppresses the individual stream with
an explicit reason.

Starting output does not change `VesselDataHub` arbitration and does not block
an Anchor or Trip from using NMEA position. `SystemLocationRepository` rejects
Android mock locations, while the output boundary also requires non-mock GNSS,
so the global NMEA GPS proxy cannot loop back as Phone GNSS output.

## External uncertainty

The software boundary prevents an avoidable App feedback loop. It does not yet
prove how KC-2W firmware maps NMEA 0183 talkers to N2K source addresses, or how
Raymarine MDS and Simrad IS42 select competing physical N2K sources. Those
behaviours require one controlled hardware test with App generated/written
counters, KC-2W Data List and IS42 N2K diagnostics recorded before and after
Raymarine power-on.
