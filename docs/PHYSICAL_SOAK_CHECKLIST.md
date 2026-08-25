# Boat Watch — Physical-device soak checklist

This checklist is a release-candidate gate, not a substitute for a navigational watch. Keep an independent anchor alarm and a qualified watchkeeper throughout every test.

## Test record

Record these before starting:

- App commit and APK SHA-256:
- Phone model, Android version and vendor build:
- Battery-optimization state and notification/alarm permissions:
- Power source and starting battery percentage:
- Boat Wi-Fi/router and NMEA server make/version:
- NMEA transport, host and port (do not publish these in logs):
- Enabled runtimes: Anchor / Sonar / Sharing / GPS proxy:
- Start/end local time and timezone:
- Observer and independent alarm used:

Run the short matrix on at least one Pixel-class device and one vendor-modified device such as Samsung. Run the 72-hour gate on the intended boat phone, charger, Wi-Fi and NMEA network.

## Pre-flight

- [ ] Install the exact candidate APK and verify its SHA-256.
- [ ] Reboot the phone, open the App, and acknowledge the recovery status.
- [ ] Confirm precise location, notifications, alarm volume, vibration and battery settings.
- [ ] Connect the real NMEA source and verify fresh accepted GPS, DPT/DBT, wind and heading on Raw data.
- [ ] Confirm the real sonar survey refuses to start if either NMEA position or DPT/DBT is stale, and starts only when both come from the same NMEA connection.
- [ ] Play and stop the alarm test. Confirm that Stop leaves no sound or vibration.
- [ ] Confirm Map/Satellite/Nautical, OpenSeaMap seamarks, LINZ Local depth and sonar fail independently without affecting the watch.
- [ ] Export a backup and retain it outside the phone before the long run.

## Eight-hour screen-off run

- [ ] Arm a real anchor session and record its locked GPS source and alarm radius.
- [ ] Start NMEA Sharing with at least one client; start a sonar survey when fresh same-stream GPS and depth are available.
- [ ] Turn the screen off for eight hours. Keep the phone on its intended charger and boat Wi-Fi.
- [ ] At 1 h, 4 h and 8 h record battery percentage, device temperature if available, process survival, accepted fixes, reconnect count, wake/Wi-Fi lock state, client count and sonar sample count from Diagnostics.
- [ ] Verify that no session source changed, no candidate centre was applied automatically, and no raw rejected position appeared on the map, in Sharing or in sonar samples.

## Controlled fault chain

Perform each fault separately and note timestamps and observed recovery:

- [ ] Interrupt NMEA for 2 seconds, restore it, and confirm no source failover.
- [ ] Interrupt NMEA for 30 seconds. Confirm a visible connection warning/GPS-loss behavior and automatic reconnection.
- [ ] Leave the socket connected but stop bytes, then send non-position sentences only. Confirm the two states are distinguishable in Diagnostics.
- [ ] Send one isolated position spike and three inconsistent spikes. Confirm they are quarantined and do not move the active centre.
- [ ] Create a coherent slow movement beyond the radius. Confirm the foreground dialog, notification, looping alarm and vibration all occur.
- [ ] Tap Snooze while still unsafe. Confirm sound/vibration stop immediately and return after the configured interval if danger remains.
- [ ] Adjust the radius so the current position is safe. Confirm active sound/vibration stop immediately without rewriting centre-learning inputs.
- [ ] Pause and Resume. Confirm the same session, track, centre, source and radius remain.
- [ ] Lift anchor. Confirm all alarm output stops and the session becomes historical.
- [ ] Connect five Sharing clients and one deliberately slow client. Confirm the slow client is dropped without delaying other clients or the anchor alarm.
- [ ] Revoke mock-location permission or stale the NMEA input while proxying. Confirm proxy failure never changes the anchor-session source.
- [ ] Make LINZ unavailable and force a sonar-tile error. Confirm the base map and anchor safety runtime continue.

## Process and reboot recovery

- [ ] With an open session, kill only the App process and reopen it. Confirm the persisted session remains, its source is unchanged, and the UI does not claim the unobserved interval was continuously protected.
- [ ] Reboot with an open anchor session, enabled Sharing and an unfinished sonar survey.
- [ ] Confirm boot recovery restores only the allowed runtimes, presents an anchor-attention notification, and does not silently resume or source-switch the anchor alarm.
- [ ] Open the App and manually verify position, depth, NMEA, volume and power before resuming.

## Backup/restore gate

- [ ] Export after the soak and verify the archive is non-empty.
- [ ] End every live runtime, then restore onto a clean installation.
- [ ] Compare settings, anchor sessions, track/event counts, sonar surveys and raw depth-sample counts.
- [ ] Confirm open watches restore paused, unfinished sonar surveys restore safely closed, custom audio requires access, and derived sonar/LINZ caches rebuild instead of being treated as source data.
- [ ] Attempt a deliberately truncated copy and a wrong-checksum copy. Confirm both are rejected without changing local data.

## Resource measurements

After a 10-minute warm-up, capture a baseline and a 60-minute steady-state sample:

```bash
adb shell dumpsys meminfo com.yokuli.anchorwatch
adb shell pidof com.yokuli.anchorwatch
adb shell dumpsys power
adb shell dumpsys wifi
adb shell dumpsys notification --noredact
```

For the process ID returned by `pidof`, record thread and file-descriptor counts with the device shell tools available on that Android build. Do not attach raw NMEA or coordinate-bearing logs to a public issue.

Suggested pass thresholds after warm-up:

- PSS growth below 25 MB over 60 minutes.
- Thread-count increase below 10.
- File-descriptor increase below 20.
- No continuously increasing socket count or tile cache.
- Zero missed safety alarms, crashes, ANRs, wake-lock loss while required, silent source changes or automatically applied candidate centres.

## 72-hour boat release gate

- [ ] Repeat the screen-off and fault checks for 72 hours on the intended vessel setup.
- [ ] Include normal wind shifts, at least one real NMEA/Wi-Fi interruption, a verified audible alarm cycle and a short real sonar survey.
- [ ] Review battery, memory, thread, descriptor, reconnect and database-growth records at the end.
- [ ] Record every anomaly and rerun the affected chain after a fix.
- [ ] Sign off only when all automated gates pass and this physical checklist has no unexplained failure.

The automated suite accelerates logical time and validates state, persistence and fault handling. It cannot prove OEM background-process behavior, speaker audibility, GNSS antenna performance, charger reliability or the real vessel network; only this physical run can close those risks.
