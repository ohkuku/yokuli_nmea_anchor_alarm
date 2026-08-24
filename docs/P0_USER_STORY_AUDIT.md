# P0 User-Story Reset

## Product ownership rules

| Concern | Authoritative owner | Runtime rule |
|---|---|---|
| New Anchor GPS default | Anchor positioning | A live session stores and locks its own source |
| Current Anchor GPS recovery | Paused Anchor session | System/NMEA may switch only while paused; Demo remains locked |
| Vessel instrument default | Data → Vessel | Feeds live Sail instruments and ordinary canonical selection |
| Current Trip position | Sail → Start Trip preflight | Per-session override; cleared at Trip end; does not mutate Anchor/default |
| NMEA input | Data → Input | One formal RX transport; traffic health is asynchronous |
| NMEA output | Data → Output | Independent TX route/lease; never auto-starts |
| Phone vessel frame | Settings → Phone vessel sensors | Zero → mounted → heading alignment before production sharing |
| Anchorage approach | Anchor → Current overlay | Library/History actions always navigate here |

## Anchor start transaction

`Set anchor → visible Preflight → retained Setup form → one Service command → wait for same-source fresh fix → Room session OR exact foreground failure`

The runtime no longer reads System GPS before enabling it. A cold GNSS start gets one bounded 15-second wait. Repeated taps are prevented by the submitting state, and runtime feedback is mirrored into the foreground UI.

## NMEA recovery transaction

`Active NMEA loss → visible warning/alarm → Pause (preserve session) → reconnect/configure/switch source → one Resume tap → 15–30 s bounded wait → same session resumes OR remains paused with reason`

Opening the formal RX connection is not a traffic test. A quiet TCP socket is a valid `CONNECTED_NO_DATA` state. The first later usable position may select NMEA as the idle Anchor default unless the user explicitly chose another source meanwhile.

## Gesture ownership

Root sections are click-only. Anchor map, Watch sheet, Sail inner instruments and Data controls own their gestures. This removes the parent horizontal pager that previously cancelled maps, marker drags, sliders and the upward Watch-details interaction.

## Saved Anchorage navigation

A Place selection opens one full details surface directly on its Spot cards. One Spot means one card; multiple Spots require an explicit card. Approach closes details, selects Anchor → Current, collapses the Watch sheet, and renders target guidance. Missing targets report an error instead of returning silently.

## Trip recording

Trip position is selected in Start Trip as `Auto`, `Boat NMEA`, or `Phone GPS`. It is a per-session choice enforced temporarily by `VesselDataHub` and persisted on `TripSessionEntity`; ending the Trip restores the global Data → Vessel default. Start requires the chosen source to become fresh. Completed history exposes a route preview or an explicit empty/unavailable state.

## Verification status

Tests and fake endpoint scenarios were added, but no Gradle, emulator, simulator or real-device execution occurred in this pass by explicit instruction. See `docs/MANUAL_QA_CHECKLIST.md` for the hardware gate.
