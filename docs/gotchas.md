# Gotchas & Past Bugs

1. **GattCallback parameter naming**: The `onServicesDiscovered` lambda was named the same as the override method, causing infinite recursion (StackOverflowError). Renamed to `onServicesDiscoveredCallback`.

2. **Notification channel caching**: Android permanently caches channel settings. `deleteNotificationChannel()` + recreate with same ID does NOT reset vibration/importance. Must use new channel IDs (versioned).

3. **The iPhone's Bluetooth name may not contain "iPhone"**: users rename their phones. The address of the last iPhone that completed an ANCS session is saved to prefs and matched first; the name check ("iPhone") is only a fallback.

4. **Pixel Watch has no USB data connection**: adb works only over Wi-Fi (Developer options → Wireless debugging → `adb pair`, then `adb connect`). The pairing code expires when you leave the pairing screen.

5. **Wear OS full-screen intent**: `setFullScreenIntent()` alone doesn't reliably launch activities on Wear OS. Also call `startActivity()` directly from the service — which on Android 14+ needs "Display over other apps" (see #21).

6. **macOS `find` commands**: Avoid `find ~` on the home directory — triggers macOS privacy permission dialogs for Music, Desktop, Documents, etc.

7. **`setNotificationSilent()` kills heads-up overlays**: On Wear OS, calling `setNotificationSilent()` on a notification sets `sound=null` which prevents heads-up/overlay display even with IMPORTANCE_HIGH channels. Never use it — let channel settings handle sound and vibration (quiet delivery uses the IMPORTANCE_LOW quiet channel; per-app haptics use dedicated channels with vibration patterns; only incoming calls drive the Vibrator directly, via `Ringer`).

8. **`USE_FULL_SCREEN_INTENT` permission**: On Android 14+ (API 34), this is a special app-op that must be explicitly granted: `adb shell appops set <pkg> USE_FULL_SCREEN_INTENT allow`. Without it, full-screen intents are silently rejected.

9. **Telecom self-managed ConnectionService**: Self-managed calls do NOT use the system's InCallService UI. The app must handle its own call UI. Only `CallProvider` connections (like the native dialer) get the system call screen. Our self-managed connection still gets priority over the charging screen though.

10. ~~**GATT writes trigger system haptics**~~ **Disproven (Pixel Watch 3, Android 17)**: 13 back-to-back Control Point writes on reconnect produced zero vibrations (`dumpsys vibrator_manager`). The original reconnect buzzing came from *posting* backlog notifications. Backlog is now fetched (to re-link notifications after a reconnect, send queued clears, and recover missed ones) and filtered by date before posting; anything shown from it goes to the quiet channel.

11. **Notification channel sound for overlays**: Channels must have a sound set (even the default `notification_sound`) for heads-up overlays to trigger. Channels with `setSound(null, null)` won't show overlays regardless of importance level.

12. **Double ANCS subscription on rediscovery**: When `discoverServices()` is called again (e.g., after bonding completes), the `onServicesDiscovered` callback must check if ANCS is already subscribed to avoid re-subscribing and resetting the session.

13. **Android auto-grouping silences notifications**: When multiple notifications are posted from the same app, Android auto-groups them and applies `GROUP_ALERT_SUMMARY`, adding the `SILENT` flag to all child notifications. This kills overlays, vibration, and sound. Fix: never leave notifications ungrouped. With "Stack by app" each app gets group `app_{bundleId}` plus our own summary (quiet channel), children use `GROUP_ALERT_CHILDREN`; with it off, each notification gets a unique group key (`ancs_{uid}`).

14. **Samsung One UI Watch overlay needs MessagingStyle**: Samsung's heads-up overlay only shows message body for `MessagingStyle` notifications. `BigTextStyle` shows content in the notification list but NOT in the overlay popup. Always use `MessagingStyle` for message-type notifications.

15. **Multi-watch ANCS prioritization**: When multiple watches are bonded to the same iPhone, ANCS uses last-in-first-get — the watch that subscribed to Notification Source most recently receives events. Only one watch active at a time.

16. **ANCS doesn't send REMOVE when call is answered**: When an incoming call is answered on iPhone, ANCS sends a MODIFIED event (EventID=1), not REMOVED. REMOVED only comes when the call fully ends. The call overlay must handle MODIFIED to transition to "Active on iPhone" state, otherwise it stays showing "Incoming Call" for the entire call duration.

17. **iOS 27 interleaves extra tuples in Data Source responses**: after each action label iOS appends attribute `0xFF` (length 0) and a repeated AppIdentifier. Parsing by request position stops early and loses the negative label. `DataSourceAssembler` matches attributes by ID; `Ios27ResponseTest` holds a real captured packet.

18. **ANCS category 12 = active call (undocumented, iOS 27)**: sent after a call is answered, negative action "End Call". Shown as a silent ongoing notification with End Call.

19. **Bluetooth adapter off gives no GATT callback**: turning the watch's Bluetooth off (settings, airplane mode) never delivers `onConnectionStateChange`. Without listening to `BluetoothAdapter.ACTION_STATE_CHANGED` the app stays "Connected" to a dead link and never reconnects.

20. **Implicit broadcast to a receiver with no intent-filter is never delivered**: notification action PendingIntents must target the component explicitly (`Intent(context, Receiver::class.java)`), not action + package. This silently broke Answer/Decline.

21. **Background activity starts are blocked on Android 14+**: the incoming-call screen and Now Playing auto-open need "Display over other apps": `adb shell appops set com.wearos.ancsbridge SYSTEM_ALERT_WINDOW allow`. Full-screen intents are the fallback.

22. **Coroutine delays pause while the watch CPU sleeps**: time-sensitive timers (left-behind alert) hold a short partial wakelock.

23. **iPhone Clock alarms don't reach the watch**: in testing, ringing iPhone alarms never showed up over ANCS — they are full-screen system alerts, not Notification Center items. Wrist alarms were dropped; use the watch's own Clock app.

24. **ANCS UIDs restart at 0 after a reconnect**: never derive watch notification IDs from UIDs. Watch notifications get their own IDs; after a reconnect they are re-linked to the new UIDs by content (app + title + message + date) as iOS re-announces them.

25. **Testing over adb while the watch is locked**: input events go to the keyguard overlay, not the app. Use `DebugInjectReceiver` to drive the pipeline and `dumpsys notification` / `dumpsys vibrator_manager` to verify.

26. **Bond broadcasts are system-wide**: `ACTION_BOND_STATE_CHANGED` fires for every device. Without filtering by address, a failed pairing with headphones or another phone dropped the iPhone link and turned auto-reconnect off.

27. **Late callbacks from a replaced `BluetoothGatt`**: after `close()` + a new `connectGatt()`, the old object can still deliver a disconnect. Ignore callbacks whose `gatt` isn't the current one, and reset per-connection state when replacing a link (otherwise `ancsSubscribed` stays true and the new link never subscribes).

28. **GATT completions must match the in-flight operation**: a callback arriving after its operation timed out would otherwise "complete" the next one. `GattOperationQueue.onOperationComplete(uuid)` ignores mismatches.
