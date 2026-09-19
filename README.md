<p align="center">
  <img src="master-docs/Orbit/orbit-icon.svg" width="128" alt="Orbit icon" />
</p>

# Orbit

Use a Wear OS watch with an iPhone. Orbit mirrors iPhone notifications and calls to the watch, controls iPhone music, and shows iPhone battery and link status — over Bluetooth Low Energy, using services built into iOS (ANCS, Apple Media Service, Battery, Current Time). Fully local, no cloud relay, and **no iPhone app required**.

> **Built on [WearBridge](https://github.com/k97/WearBridge) by Karthik Rajendran** (MIT). WearBridge provided the foundation: the ANCS client (Notification Source parsing, Control Point requests, Data Source reassembly), BLE connection, bonding and reconnection, the Telecom-based incoming-call screen with Answer / Decline, and the notification channels and 75-app icon map. Orbit extends it with pairing without an iPhone app, Apple Media Service, Battery and Current Time services, two-way dismiss, real App Store icons, per-app settings, a tile and complications, continuous call ringing, and many reliability fixes. The original copyright notice is kept in [LICENSE](LICENSE).

```
iPhone (iOS)                             Wear OS Watch
┌──────────────────────────┐             ┌──────────────────────────────┐
│  ANCS  (notifications)   │             │  Orbit foreground service    │
│  AMS   (now playing)     │◄─BLE bond──►│  GATT client + op queue      │
│  Battery / Current Time  │             │  Notifications, calls, media │
│  (all built into iOS)    │             │  Tile + complications        │
└──────────────────────────┘             └──────────────────────────────┘
```

## Features

### Pairing & connection
- **Pairing without an iPhone app** — the watch advertises itself; pair from iPhone **Settings → Bluetooth**
- **Auto-reconnect** — reconnects on its own after range loss, watch Bluetooth off/on, or a reboot
- **Left-behind alert** — one buzz when the iPhone has been disconnected for 15 s; clears itself on reconnect

### Notifications
- **Real-time mirroring** — messages, email, calls, calendar and every other app, delivered in well under a second
- **Real app icons** — each app's official App Store icon, fetched once and cached; iOS-style icons for Apple's built-in apps
- **Two-way dismiss** — swipe away on the watch clears it on the iPhone, and clearing on the iPhone removes it from the watch; keeps working across reconnects, and clears made while disconnected sync once the iPhone is back
- **Actions** — the iPhone's own notification buttons (e.g. "Clear", "Dial" on a missed call) work from the watch
- **Stacks by app** — each app's notifications are grouped into one stack; dismissing the stack clears them all on the iPhone
- **Quiet delivery** — notifications the iPhone delivers quietly (Focus, Deliver Quietly) show without buzzing
- **Missed while away** — notifications that arrived while the watch was disconnected appear quietly after reconnect
- **Updates without re-buzzing** — edited notifications refresh in place

### Per-app settings (like the Apple Watch app)
- **Alert / Quiet / Off** per iPhone app
- **Haptic style** per app — Default, Tap, Double or Long
- Feature toggles for stacks, Now Playing auto-open, the left-behind alert and missed notifications

### Calls
- **Full-screen call screen** with the caller's name, even over the lock screen
- **Keeps vibrating** until you answer, decline or silence it (side button), like an Apple Watch
- **Answer / Decline act on the iPhone**; the watch shows "Active on iPhone" when you pick up there
- **End Call** from the watch while a call is in progress
- **WhatsApp and FaceTime calls** detected too

### Media (Apple Media Service)
- **Now Playing** — title, artist, progress, play/pause, previous/next and volume for whatever plays on the iPhone (Spotify, Apple Music, YouTube, podcasts…)
- **Digital Crown = iPhone volume**
- **Auto-opens** when playback starts on the iPhone
- **Ongoing activity** — a music icon on the watch face while something is playing, with controls in the notification

### iPhone status
- **iPhone battery** — on the home screen, in the ongoing notification, a complication and the tile
- **Clock check** — compares the watch clock with the iPhone's and flags drift or a time-zone mismatch

### Watch surfaces
- **"iPhone" tile** — connection, battery, current track and a Play/Pause button
- **Complications** — "iPhone Battery" and "iPhone Now Playing" for any watch face

### Built for the watch
- Optimized release build (about 0.8 s cold start), crown scrolling, Wear OS themed-icon support
- Fully local — the only network use is the one-time App Store icon lookup

## Upcoming (possible) features

- **Calls on the wrist** — the watch as the iPhone's Bluetooth hands-free device: talk through the watch speaker and mic, Wear OS's native phone UI, dialing from the watch (to be tested — Wear OS may reserve this for its companion phone)
- **Native Wear OS media controls** over Classic Bluetooth (AVRCP), which would come with the above
- **iPhone remote** — the watch as a Bluetooth HID device: iPhone Camera shutter, media keys, slide clicker, possibly a Siri key
- **Heart-rate broadcast** — the watch as a standard heart-rate sensor for iPhone workout apps (Strava, Zwift, Peloton)
- **"Ping my iPhone"** — make the iPhone play a sound to find it
- **iCloud Calendar on the wrist** — next-event complication and agenda tile
- **Multi-device pairing** — switch between iPhones or watches

Not possible without Apple's cooperation: typed iMessage/SMS replies, Apple Pay, unlocking the iPhone or Mac, Siri, Activity rings, Walkie-Talkie, iPhone Clock alarms, and setting the watch clock from the iPhone.

## Tested Hardware

| Device | Details |
|--------|---------|
| iPhone 17 Pro | iOS 27 |
| Google Pixel Watch 3 (41 mm) | Wear OS, Android 17 |

## How It Works

ANCS and the other services are system-level BLE services built into iOS. Any bonded Bluetooth accessory can use them without an app on the iPhone.

1. **Pairing** — the watch advertises with an ANCS solicitation UUID; the iPhone lists it in Settings → Bluetooth and connects when you tap it
2. **Session** — the watch bonds, subscribes to ANCS (Data Source, then Notification Source), then starts Battery, Current Time and Apple Media Service
3. **Notifications** — 8-byte events arrive in real time; the watch fetches title, message, app ID and action labels via the Control Point, reassembles the fragmented response, and posts an Android notification
4. **Actions** — Answer / Decline / End Call / Clear are sent back as ANCS actions; swiping a notification away sends "Clear"
5. **Reconnects** — iOS renumbers notifications after a reconnect, so the watch re-links what it shows by content

Protocol details: [`docs/protocol.md`](docs/protocol.md).

## Project Structure

```
├── wearos-app/          # Wear OS app (Kotlin, Jetpack Compose for Wear OS)
│   └── app/src/main/java/com/wearos/ancsbridge/
│       ├── ble/         # Connection manager, GATT callbacks + op queue, pairing advertiser,
│       │                #   ANCS constants, Battery / Current Time / AMS (PhoneServices)
│       ├── ancs/        # Foreground service, ANCS parsing, calls (Telecom + Ringer),
│       │                #   app icons, notification actions, adb test injector
│       ├── media/       # Now Playing ongoing activity + auto-open
│       ├── surfaces/    # Complications and tile
│       ├── settings/    # Per-app alert modes / haptics, feature toggles
│       ├── model/       # Data classes, live iPhone status
│       ├── ui/          # Home, pairing, Now Playing, settings, call screen
│       └── viewmodel/   # ViewModel
│
└── master-docs/         # Research notes, ANCS spec references, app icon (Orbit/orbit-icon.svg)
```

## Build

Gradle needs a JetBrains JDK 21 — Android Studio's bundled JBR works:

```bash
cd wearos-app
# macOS
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew testDebugUnitTest assembleRelease
# Windows (Git Bash)
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew.bat testDebugUnitTest assembleRelease \
  -Porg.gradle.java.installations.paths="C:/Program Files/Android/Android Studio/jbr"
```

The release build is optimized with R8 and signed with the debug key for sideloading. Use it rather than the debug build, which is much slower on a watch. The package ID is still `com.wearos.ancsbridge` (kept from WearBridge so existing installs upgrade in place).

Install on the watch via ADB (Pixel Watches have no USB data, so use Wi-Fi: Developer options → Wireless debugging):

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
# Needed for the incoming-call screen and Now Playing auto-open:
adb shell appops set com.wearos.ancsbridge SYSTEM_ALERT_WINDOW allow
adb shell appops set com.wearos.ancsbridge USE_FULL_SCREEN_INTENT allow
```

## Setup

1. Install the APK on your Wear OS watch and grant the Bluetooth + notification permissions
2. In Orbit, tap **Pair New Device** → **Start Pairing** (the watch is discoverable for 3 minutes)
3. On the iPhone, open **Settings → Bluetooth** and tap the watch's name (shown on the watch)
4. Tap **Pair**, then **Allow** when iOS asks to share notifications
5. Notifications start mirroring automatically

If the watch doesn't appear in Settings → Bluetooth, connect to it once from any BLE scanner app (e.g. nRF Connect, LightBlue) — the watch then requests pairing itself.

Optional: add the **iPhone** tile, and the **iPhone Battery** / **iPhone Now Playing** complications from the watch face editor. Per-app and feature settings are under **Settings** in Orbit.

## App Icons

Any App Store app shows its real, full-color icon: the watch looks up the bundle ID with Apple's public App Store lookup API the first time the app notifies, then caches the icon. Apple's built-in apps (Messages, Phone, Mail, …) get an iOS-style generated icon. The small notification-header icon is always Orbit's (Android shows the posting app's own icon there).

## Limitations

- No typed replies to iMessage/SMS (ANCS only exposes the iPhone's action buttons)
- iPhone Clock alarms and timers don't reach the watch
- Call audio stays on the iPhone
- The watch clock can't be set from the iPhone (Orbit shows a clock check instead)

## Contributing

Contributions are welcome! Feel free to open issues or submit pull requests.

## License

This project is licensed under the MIT License — see the [LICENSE](LICENSE) file for details.
