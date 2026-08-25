# NMEA output destination semantics and provenance firewall

## Product invariant

`SAME_AS_INPUT_CONNECTION` is **Local Sensor Injection**. It reuses the one
already-open full-duplex Boat TCP Socket and never creates a second Boat
connection. It is not a unified vessel repeater.

Independent destinations (`DEDICATED_TCP`, `TCP_SERVER`, UDP unicast and UDP
broadcast) are **Unified Vessel Stream / fan-out** destinations. They may encode
the source selected by `VesselDataHub`, including Boat input and App-derived
values, because they do not write those values back into their source Socket.

## Same-input decision

A same-input value is accepted only when the complete provenance available to
the App proves that it is local:

- Phone GNSS
- calibrated Phone vessel/device heading
- Phone IMU
- Phone barometer
- an App-derived value whose complete direct input list contains only Phone
  sensor identities

It is rejected when it is:

- `BOAT_NMEA`, including an AUTO-selected Boat candidate;
- `PHONE_TX_ECHO`;
- derived from any `NMEA_INPUT` identity;
- derived from mixed Boat/Phone inputs;
- nested App-derived data whose ancestry is unavailable;
- Demo, unknown or another non-local source.

Unknown ancestry is rejected conservatively. A freshly generated App sentence
does not prove that the value is safe; the inputs that produced the value are
the authority.

## Transport generation

Boat identities carry input profile and connection generation. Output batches
also carry the input generation that existed when they were created. The
writer rejects a batch if reconnect N+1 has replaced generation N. The
same-input provenance policy rejects Boat input regardless, and distinguishes a
current-transport Boat source in diagnostics.

## Stream rules

### Position

RMC/GGA/VTG/ZDA are generated directly from one accepted Android
`NavigationFix`. Latitude, longitude, SOG, COG, UTC, altitude and accuracy are
never assembled from independently selected vessel observations. Boat SOG/COG
therefore cannot leak into a Phone RMC or VTG.

### Heading

Same-input publication searches explicitly for a fresh Phone vessel/device
heading candidate. A Raymarine/KC-2W Heading may create a visible source
conflict, but does not suppress or replace the Phone HDT/HDG heartbeat.

### Motion and pressure

Same-input ROT/heel/pitch use Phone IMU only. Pressure uses Phone barometer
only. External values remain available to VesselDataHub and independent feeds.

### Depth, STW and wind

Depth and STW are Boat metrics and are never injected back into the input
Socket. Derived wind is allowed only if every declared dependency is a local
Phone sensor. Boat apparent wind, heading or speed anywhere in the ancestry
blocks MWD/MWV/VWT on the same Socket.

## Echo handling

The provenance firewall and outbound echo quarantine are separate layers.
`NmeaOutboundLoopGuard` consumes a bounded occurrence for recently transmitted
exact/semantic frames before they reach the source registry. An echo identity,
if one reaches the provenance boundary, is denied explicitly.

## Publication ownership

Normal same-input Phone streams use `ALWAYS`, not `BACKUP`. A stream publishes
while it is enabled, its local sample is fresh/valid, calibration is valid and
the transport is writable. External-source presence is diagnostic only. Stale
or invalid local evidence still suppresses the individual stream with an
explicit reason.

## External uncertainty

The software boundary prevents an avoidable App feedback loop. It does not yet
prove how KC-2W firmware maps NMEA0183 talkers to N2K source addresses, or how
Raymarine MDS and Simrad IS42 select competing physical N2K sources. Those
behaviours require one controlled hardware test with App generated/written
counters, KC-2W Data List and IS42 N2K diagnostics recorded before and after
Raymarine power-on.
