package com.wearos.ancsbridge.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Watch-side notification preferences, like the Apple Watch app's Notifications
 * screen: per-app alert mode and haptic, plus global feature toggles.
 */
object AppSettings {

    private const val PREFS = "wearbridge_settings"
    private const val KEY_APPS = "known_apps"
    private const val SEEN_WRITE_INTERVAL_MS = 10 * 60_000L

    enum class AlertMode(val label: String) {
        ALERT("Alert"),   // heads-up + haptic
        QUIET("Quiet"),   // shows in the list, no buzz
        OFF("Off");       // not shown at all

        fun next() = entries[(ordinal + 1) % entries.size]
    }

    enum class Haptic(val label: String) {
        DEFAULT("Default"),
        TAP("Tap"),
        DOUBLE("Double"),
        LONG("Long");

        fun next() = entries[(ordinal + 1) % entries.size]
    }

    data class AppEntry(
        val bundleId: String,
        val name: String,
        val lastSeen: Long,
        val mode: AlertMode = AlertMode.ALERT,
        val haptic: Haptic = Haptic.DEFAULT,
        /** Delivered quietly until this time (System.currentTimeMillis()); 0 = not muted. */
        val mutedUntil: Long = 0L
    ) {
        fun isMuted(now: Long = System.currentTimeMillis()) = mutedUntil > now
    }

    private lateinit var prefs: SharedPreferences

    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    /** Apps that have sent at least one notification, most recent first. */
    val apps: StateFlow<List<AppEntry>> = _apps.asStateFlow()

    private val _toggles = MutableStateFlow(Toggles())
    val toggles: StateFlow<Toggles> = _toggles.asStateFlow()

    data class Toggles(
        val stackByApp: Boolean = true,
        val autoLaunchNowPlaying: Boolean = true,
        val leftBehindAlert: Boolean = true,
        val showMissedWhileAway: Boolean = true,
        /** No buzzing while the watch is off the wrist, like an Apple Watch. */
        val quietOffWrist: Boolean = true,
        /** Look up album art online. Sends the song's title and artist to Apple's search API. */
        val albumArt: Boolean = true
    )

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _apps.value = loadApps()
        _toggles.value = Toggles(
            stackByApp = prefs.getBoolean("stack_by_app", true),
            autoLaunchNowPlaying = prefs.getBoolean("auto_launch_now_playing", true),
            leftBehindAlert = prefs.getBoolean("left_behind_alert", true),
            showMissedWhileAway = prefs.getBoolean("show_missed", true),
            quietOffWrist = prefs.getBoolean("quiet_off_wrist", true),
            albumArt = prefs.getBoolean("album_art", true)
        )
    }

    fun entryFor(bundleId: String): AppEntry? = _apps.value.firstOrNull { it.bundleId == bundleId }

    /**
     * Record that [bundleId] sent a notification (adds it to the settings list).
     *
     * Called for every notification, so a known app whose entry was just written is left
     * alone: the timestamp only orders the settings list, and rewriting the whole list to
     * disk for each notification in a burst is wasted work.
     */
    fun recordSeen(bundleId: String, name: String) {
        if (bundleId.isEmpty()) return
        val now = System.currentTimeMillis()
        val existing = entryFor(bundleId)
        if (existing != null && existing.name == name && now - existing.lastSeen < SEEN_WRITE_INTERVAL_MS) return
        val updated = existing?.copy(name = name, lastSeen = now) ?: AppEntry(bundleId, name, now)
        save(listOf(updated) + _apps.value.filter { it.bundleId != bundleId })
    }

    fun setMode(bundleId: String, mode: AlertMode) = update(bundleId) { it.copy(mode = mode) }

    fun setHaptic(bundleId: String, haptic: Haptic) = update(bundleId) { it.copy(haptic = haptic) }

    /**
     * Apple Watch's "Mute for 1 hour": the app's notifications still arrive, quietly, until
     * [durationMs] from now. An app not in the list yet (trimmed) is added.
     */
    fun muteFor(bundleId: String, name: String, durationMs: Long) {
        if (bundleId.isEmpty()) return
        val until = System.currentTimeMillis() + durationMs
        if (entryFor(bundleId) == null) {
            save(listOf(AppEntry(bundleId, name, System.currentTimeMillis(), mutedUntil = until)) + _apps.value)
        } else {
            update(bundleId) { it.copy(mutedUntil = until) }
        }
    }

    fun unmute(bundleId: String) = update(bundleId) { it.copy(mutedUntil = 0L) }

    /** Drop an app from the list (test hook: removes apps that only fake notifications created). */
    fun forget(bundleId: String) = save(_apps.value.filter { it.bundleId != bundleId })

    fun setToggles(toggles: Toggles) {
        _toggles.value = toggles
        prefs.edit {
            putBoolean("stack_by_app", toggles.stackByApp)
            putBoolean("auto_launch_now_playing", toggles.autoLaunchNowPlaying)
            putBoolean("left_behind_alert", toggles.leftBehindAlert)
            putBoolean("show_missed", toggles.showMissedWhileAway)
            putBoolean("quiet_off_wrist", toggles.quietOffWrist)
            putBoolean("album_art", toggles.albumArt)
        }
    }

    private fun update(bundleId: String, transform: (AppEntry) -> AppEntry) {
        save(_apps.value.map { if (it.bundleId == bundleId) transform(it) else it })
    }

    private fun save(apps: List<AppEntry>) {
        val trimmed = apps.sortedByDescending { it.lastSeen }.take(100)
        _apps.value = trimmed
        val json = JSONObject()
        trimmed.forEach { app ->
            json.put(app.bundleId, JSONObject()
                .put("name", app.name)
                .put("lastSeen", app.lastSeen)
                .put("mode", app.mode.name)
                .put("haptic", app.haptic.name)
                .put("mutedUntil", app.mutedUntil))
        }
        prefs.edit { putString(KEY_APPS, json.toString()) }
    }

    private fun loadApps(): List<AppEntry> {
        val raw = prefs.getString(KEY_APPS, null) ?: return emptyList()
        return try {
            val json = JSONObject(raw)
            json.keys().asSequence().map { id ->
                val o = json.getJSONObject(id)
                AppEntry(
                    bundleId = id,
                    name = o.optString("name", id),
                    lastSeen = o.optLong("lastSeen"),
                    mode = runCatching { AlertMode.valueOf(o.optString("mode")) }.getOrDefault(AlertMode.ALERT),
                    haptic = runCatching { Haptic.valueOf(o.optString("haptic")) }.getOrDefault(Haptic.DEFAULT),
                    mutedUntil = o.optLong("mutedUntil")
                )
            }.sortedByDescending { it.lastSeen }.toList()
        } catch (_: Exception) {
            emptyList()
        }
    }
}
