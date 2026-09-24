# Future Work

## Planned / Possible (watch-only)

### Calls on the wrist (Hands-Free Profile)
- The Pixel Watch 3 Bluetooth stack has `HEADSET_CLIENT` (HFP hands-free), `AVRCP_CONTROLLER` and `HID_DEVICE` enabled, and the iPhone's `hfp_client` policy is ALLOWED, but no Classic Bluetooth link is up
- If the iPhone connects HFP to the watch (Classic pairing from iPhone Settings → Bluetooth), calls could use the watch speaker/mic and Wear OS's native phone UI, including dialing out
- Unverified: Wear OS may reserve HFP for its companion phone. The app can't start HFP itself (`BluetoothHeadsetClient` is a system API)

### Native media controls — done another way
- Wear OS's own media controls now show and drive the iPhone's player through a media session backed by Apple Media Service (see protocol.md). AVRCP would only add audio routing, which needs the Classic link above

### Toolchain migration (AGP 9.1, compileSdk 37)
- The newest AndroidX releases (core 1.19, Compose 1.12 / BOM 2026.08+, Wear Compose 1.7) refuse to build on AGP 8.13. Taking them means moving to AGP 9.1 (its built-in Kotlin support changes the plugin setup) and installing SDK 37. Until then the libraries stay on the newest versions AGP 8.13 accepts (gotcha 41)

### iPhone remote (HID) — declined
- `BluetoothHidDevice` would let the watch act as a Bluetooth keyboard: volume-up triggers the iPhone Camera shutter, media keys work, and the consumer-page Power/Menu usage is how BLE remotes invoke Siri
- Owner does not want it; left here only so the option is not researched twice. Unverified whether Wear OS enables the HID Device profile at all

### Heart-rate broadcast — dropped
- A Heart Rate Service (0x180D) server on the watch would let iPhone workout apps use it as a chest strap. The sensor is reachable (`BODY_SENSORS` splits into `health.READ_HEART_RATE` below target SDK 36) and it was built once, then deleted: it only helps someone who trains with Strava, Zwift or Peloton, and the Fitbit iOS app already shows the watch's own health data from the cloud

### "Ping my iPhone"
- No proper API without an iPhone app; possible hack: start iPhone playback at high volume via AMS

### iCloud Calendar on the wrist
- iCloud speaks CalDAV at `caldav.icloud.com` and accepts app-specific passwords from third-party clients, so the watch could fetch the calendar itself over Wi-Fi → next-event complication and agenda tile
- Costs: Wi-Fi is the watch's biggest battery drain, the password has to live on the watch, and Reminders reportedly do not come through CalDAV the way calendars do

### Multi-device pairing
- Switch between iPhones or watches; persist per-device preferences
- Only one watch receives ANCS events at a time (the last one to subscribe)

## Watch this: the EU may open the door
- Under the Digital Markets Act, iOS 26.3 gave third-party smartwatches official notification forwarding, AirPods-style proximity pairing and automatic weather sync
- EU only, so it does not reach this watch, and Apple has not said it will spread. If it ever does, that path beats ANCS

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
