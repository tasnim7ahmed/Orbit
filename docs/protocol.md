# BLE & ANCS Protocol Details

## iPhone Services Used

| Service | UUID | Used for |
|---|---|---|
| ANCS | `7905F431-B5CE-4E99-A40F-4B1E122D00D0` | Notifications, calls, actions |
| Apple Media Service (AMS) | `89D3502B-0F36-433A-8EF4-C502AD55F8DC` | Now Playing + remote control |
| Battery | `0x180F` / Battery Level `0x2A19` | iPhone battery % (read + notify) |
| Current Time | `0x1805` / Current Time `0x2A2B`, Local Time Info `0x2A0F` | Clock check (drift + time zone) |
| GAP | Device Name `0x2A00` | Real iPhone name |

All require the bond. Battery/CTS/AMS are started by `PhoneServices` once the ANCS session is active.

## ANCS Characteristics

| Characteristic | UUID | Role |
|---|---|---|
| Notification Source | `9FBF120D-6301-42D9-8C58-25E699A21DBD` | Subscribe — 8-byte event notifications |
| Control Point | `69D1D8F3-45E1-49A8-9821-9BBDFDAAD9D9` | Write — request attributes / perform actions |
| Data Source | `22EAC6E9-24D6-4BB5-BE44-B36ACE7C7BFB` | Subscribe — fragmented attribute responses |

**Subscribe to Data Source BEFORE Notification Source** (Apple requirement).

Data Source responses are matched **by attribute ID, not position**: iOS 27 interleaves extra tuples (`0xFF` len 0, repeated AppIdentifier) after action labels. Category **12** (undocumented) = call in progress, negative action "End Call".

## Pairing (no iPhone app)

1. **Pair New Device → Start Pairing**: `PairingAdvertiser` advertises connectable with the ANCS **service solicitation** UUID (name in scan response) for 3 minutes, and opens a GATT server to receive the inbound link
2. iPhone **Settings → Bluetooth** lists the watch → user taps it → iPhone connects as central
3. `BluetoothGattServerCallback.onConnectionStateChange` → `connectGatt()` on the existing link
4. Outside the pairing window, inbound links are accepted only from the remembered iPhone

## Connection Flow

1. `connectGatt()` (callbacks on the main looper) → `onConnectionStateChange(CONNECTED)`
2. `requestMtu(512)` → `onMtuChanged` → `discoverServices()` (3 s fallback — the MTU may already be negotiated when the iPhone opened the link)
3. ANCS not visible (not bonded) → `createBond()` (skipped if bonding is already in progress) → iOS pairing dialog. If ANCS is visible pre-bond, the CCCD write fails with an auth error → bond → re-subscribe after `BOND_BONDED`
4. Subscribe DS CCCD → NS CCCD → session active; `sessionStartedAt` set, iPhone address saved to prefs (`wearbridge` / `iphone_address`)
5. Read GAP name, start Battery / CTS / AMS
6. Service discovery failure or missing ANCS characteristics → disconnect → normal reconnect
7. Bond broadcasts for other devices (headphones, other phones) are ignored

## Reconnection

- `autoConnect=true` to the bonded iPhone; exponential backoff 3 s → 6 s → 12 s → 30 s cap
- After an unexpected drop the watch also advertises (low power) so the iPhone can reconnect inbound
- **Watch Bluetooth off/on**: Android sends no GATT callback, so `ACTION_STATE_CHANGED` drives it — off → `onAdapterOff()` (Disconnected); on → reconnect + advertise
- `START_STICKY` foreground service + `BootReceiver`
- `getBondedIPhone()`: saved address first, then a bonded device whose name contains "iPhone"
- Replacing a connection resets all per-connection state (`clearCharacteristics()`); late callbacks from a replaced `BluetoothGatt` are ignored

## GATT Operation Queue

`GattOperationQueue` serializes every descriptor write, characteristic write and read (Android allows one in flight). Completions carry the characteristic UUID, so a late callback for an operation that already timed out (5 s) can't complete the next one.

## Notification Pipeline

1. NS event → ADDED/MODIFIED → fetch attributes via CP (one request at a time; the next waits for the DS response or a 3 s timeout, which posts partial content if title/message arrived)
2. **Backlog** = flagged pre-existing, or anything in the first 10 s after subscribing. Fetched only to (a) re-link notifications still on the watch, (b) send queued clears, (c) recover missed ones ("Show missed"); otherwise skipped
3. **Watch notification IDs** come from `allocateNotifId()` (persisted counter, 1000–400000), never from the UID. `uidToNotifId` maps the current session's UIDs; it is cleared at each session start
4. **Re-linking**: iOS restarts UIDs at 0 on reconnect and re-announces everything in Notification Center. Backlog items are matched to posted watch notifications by signature (app + title + message + date) and re-linked to the new UID
5. **Missed while away**: backlog dated after the last time the link was up (`last_link_up_at` heartbeat every 30 s) is shown on the quiet channel; older items are skipped
6. MODIFIED updates replace their watch notification with `setOnlyAlertOnce` (no second buzz)
7. Oldest notifications are evicted above 20 active

### Two-way dismiss

- **iPhone → watch**: REMOVED event → `removeWatchNotification()`
- **Watch → iPhone**: every notification has a `deleteIntent` → `onWatchDismissed()` → ANCS negative action ("Clear"), only for the current session's UID. Stale/disconnected → the signature is queued in `pendingIPhoneClears` (max 100) and cleared after reconnect when iOS re-announces it
- In-app "Clear Notifications" clears on both sides

### Stacks, quiet delivery, per-app settings

- **Stack by app** (default on): group `app_{bundleId}` + our own summary (quiet channel, ID `2_000_000 + hash`), children `GROUP_ALERT_CHILDREN`. Off: unique group per notification (`ancs_{uid}`). Summary is removed with its last child; dismissing it clears the whole stack on the iPhone
- **Quiet**: ANCS Silent flag (iPhone Focus / Deliver Quietly), per-app Quiet, or recovered backlog → `CHANNEL_QUIET`
- **Per-app** (`AppSettings`, SharedPreferences JSON): Alert / Quiet / Off + haptic Default / Tap / Double / Long (Tap/Double/Long map to dedicated haptic channels)

### App icons

`AppIconRepository`: bundle ID → Apple's public App Store lookup API → 144 px rounded, circle-safe icon cached on disk (misses retried after 3 days; lookup US store, then the watch's locale). Shown as `largeIcon` and as the MessagingStyle sender avatar; first notification from a new app posts immediately and swaps the icon in silently. Apple's own apps (not on the App Store) and offline cases get a generated icon: `AppIconMapper` glyph (77 bundle IDs) on the app's brand color.

## Notification Channels

Versioned (`_v7`); bump in `AncsApplication.kt` to change settings.

| Channel | Importance | Purpose |
|---|---|---|
| `service` | LOW | Foreground service (status + iPhone battery) |
| `incoming_call` | HIGH | CallStyle notification with full-screen intent |
| `messages`, `email`, `social`, `other` | HIGH | By ANCS category |
| `schedule` ("Calendar & Reminders") | HIGH | Calendar, Reminders, Clock |
| `quiet` | LOW | Silent-flagged, per-app Quiet, recovered backlog, stack summaries |
| `haptic_tap`, `haptic_double`, `haptic_long` | HIGH | Per-app haptic styles (custom vibration pattern) |
| `connection` | HIGH | Left-behind alert |
| `now_playing` | LOW | Now Playing ongoing notification |

## Incoming Calls

1. NS ADDED with cat=1 → call screen immediately (caller name updated when attributes arrive ~0.2 s later)
2. `Ringer` vibrates `USAGE_RINGTONE`, repeating, until answer / decline / iPhone pickup / hang-up / side button (silence) / link loss / 45 s cap. Not started if the iPhone delivered the call silently
3. `AncsConnectionService` reports a self-managed Telecom call; `IncomingCallActivity` is started directly (needs `SYSTEM_ALERT_WINDOW`), with the CallStyle full-screen intent as fallback
4. Answer / Decline (screen or CallStyle notification) → ANCS positive / negative action
5. VoIP call detection from text only for WhatsApp / FaceTime / Phone; generic phrases ("video call", "ringing") only when they are the whole short message

### Call state after answering

1. **ANCS MODIFIED** for the active call → "Active on iPhone" (auto-dismiss 5 s); a MODIFIED call never re-rings
2. **Category 12** → silent ongoing notification with **End Call** (negative action)
3. **Safety timeout**: 45 s

## Apple Media Service (Now Playing)

- Remote Command `9B3C81D8-57B1-4A8A-B8DF-0E56F7CA51C2` (write command ID; notifies supported commands), Entity Update `2F7CABCE-808D-411F-9A0C-BB92BA96C102` (subscribe Player name/playback/volume and Track artist/album/title/duration)
- `MediaScreen`: controls, progress, crown = iPhone volume (`onRotaryScrollEvent`, 48 px per step)
- `NowPlayingController`: ongoing notification + Wear Ongoing Activity while a track is loaded (hidden after 10 min paused); auto-opens Now Playing when playback starts (not in the first 8 s of a session, 60 s cooldown, toggle in Settings)

## Battery & Clock

- Battery Level read + notify → home screen, service notification, complication, tile
- CTS: Local Time Info read first, then Current Time read + notify → drift vs the watch clock (in sync under 3 s) and time-zone mismatch, shown on the home screen. The app can't set the watch clock (needs `SET_TIME`, system only)

## Watch Surfaces

- **Complications**: "iPhone Battery" (RANGED_VALUE, SHORT_TEXT; "—" when disconnected), "iPhone Now Playing" (SHORT_TEXT, LONG_TEXT). Push-only (`UPDATE_PERIOD_SECONDS=0`)
- **Tile** "iPhone": link status + battery, current track, Play/Pause (LoadAction handled in `onTileRequest`)
- `SurfaceUpdater.requestAll()` on battery / link / media changes (coalesced 0.5 s)

## Left-behind Alert

Unexpected disconnect → after 15 s still down → "iPhone disconnected" on `connection` channel. A partial wakelock covers the 15 s (coroutine delays pause while the CPU sleeps). Cleared on reconnect; user "Disconnect" doesn't trigger it.

## Not Supported

- **iPhone Clock alarms/timers**: didn't arrive over ANCS in testing (they are full-screen system alerts, not Notification Center items)
- **Typed replies**: ANCS only exposes the positive/negative actions
- **Call audio on the watch**: calls are answered on the iPhone
