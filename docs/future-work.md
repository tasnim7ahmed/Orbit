# Future Work

## Planned / Possible (watch-only)

### Calls on the wrist (Hands-Free Profile)
- The Pixel Watch 3 Bluetooth stack has `HEADSET_CLIENT` (HFP hands-free), `AVRCP_CONTROLLER` and `HID_DEVICE` enabled, and the iPhone's `hfp_client` policy is ALLOWED, but no Classic Bluetooth link is up
- If the iPhone connects HFP to the watch (Classic pairing from iPhone Settings → Bluetooth), calls could use the watch speaker/mic and Wear OS's native phone UI, including dialing out
- Unverified: Wear OS may reserve HFP for its companion phone. The app can't start HFP itself (`BluetoothHeadsetClient` is a system API)

### Native media controls (AVRCP)
- Comes with the Classic link above: Wear OS's own media controls for iPhone playback

### iPhone remote (HID)
- `BluetoothHidDevice` (public API): watch as a Bluetooth keyboard/consumer-control device
- Volume-up = iPhone Camera shutter, media keys, slide clicker; possibly a Siri key (experimental)

### Heart-rate broadcast
- Expose the standard Heart Rate Service (0x180D) from the watch so iPhone workout apps (Strava, Zwift, Peloton) can use it like a chest strap

### "Ping my iPhone"
- No proper API without an iPhone app; possible hack: start iPhone playback at high volume via AMS

### iCloud Calendar on the wrist
- Sync iCloud Calendar over Wi-Fi/LTE via CalDAV (app-specific password) → next-event complication and agenda tile

### Multi-device pairing
- Switch between iPhones or watches; persist per-device preferences
- Only one watch receives ANCS events at a time (the last one to subscribe)

## Would need an iPhone app (not planned)
- Typed replies (via Shortcuts — unreliable)
- Ringing the iPhone even on silent
- Syncing watch health data into Apple Health (HealthKit)
- Reading the iPhone's Focus state directly

## Not Possible (Apple-locked)
- iMessage/SMS replies from the watch (no MAP client on the watch; ANCS has no reply action)
- Apple Pay, unlocking iPhone/Mac, Siri, Activity rings, Walkie-Talkie, Handoff
- iPhone Clock alarms (not delivered over ANCS)
- Setting the watch clock from the iPhone (`SET_TIME` is system-only; the app shows a clock check instead)
