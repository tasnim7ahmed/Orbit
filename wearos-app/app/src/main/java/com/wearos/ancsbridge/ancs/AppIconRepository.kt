package com.wearos.ancsbridge.ancs

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.Log
import android.util.LruCache
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.wearos.ancsbridge.net.Http
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.Locale

/**
 * Full-color icons for iPhone apps, shown as the notification's large icon.
 *
 * ANCS only gives us the app's bundle ID. Apple's public App Store lookup API
 * (itunes.apple.com/lookup?bundleId=…) maps that to the app's official artwork,
 * so any App Store app gets its real icon — not just a hardcoded list. Icons are
 * fetched once over the watch's Wi-Fi/LTE and cached on disk permanently.
 *
 * Apple's built-in apps (Messages, Phone, Mail…) aren't in the App Store; they,
 * and anything fetched while offline, get a generated iOS-style icon: the mapped
 * glyph on the app's brand color.
 */
object AppIconRepository {

    private const val TAG = "AppIconRepository"
    private const val ICON_SIZE_PX = 144
    private const val PREFS = "app_icons"
    private const val RETRY_AFTER_MISS_MS = 3L * 24 * 60 * 60 * 1000 // 3 days

    private val memory = LruCache<String, Bitmap>(40)
    // Touched from the service and from complication/tile coroutines
    private val inFlight: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Built-in iOS apps → brand color for the generated icon. */
    private val appleAppColors = mapOf(
        "com.apple.MobileSMS" to 0xFF34C759,
        "com.apple.mobilephone" to 0xFF34C759,
        "com.apple.facetime" to 0xFF34C759,
        "com.apple.mobilemail" to 0xFF1A8CFF,
        "com.apple.mobilecal" to 0xFFFF3B30,
        "com.apple.reminders" to 0xFF007AFF,
        "com.apple.mobiletimer" to 0xFF1C1C1E,
        "com.apple.Health" to 0xFFFF2D55,
        "com.apple.Fitness" to 0xFF1C1C1E,
        "com.apple.findmy" to 0xFF34C759,
        "com.apple.Passbook" to 0xFF1C1C1E,
        "com.apple.Music" to 0xFFFA2D48,
        "com.apple.podcasts" to 0xFF9933CC,
        "com.apple.news" to 0xFFFF3B30,
        "com.apple.weather" to 0xFF1E88E5,
        "com.apple.Maps" to 0xFF34C759,
        "com.apple.Home" to 0xFFFF9500,
        "com.apple.Preferences" to 0xFF8E8E93,
        "com.apple.AppStore" to 0xFF007AFF,
        "com.apple.shortcuts" to 0xFF5856D6,
    )

    private val categoryColors = mapOf(
        1 to 0xFF34C759, 2 to 0xFFFF3B30, 3 to 0xFF34C759, // calls / voicemail
        4 to 0xFF5856D6, 5 to 0xFFFF9500, 6 to 0xFF1A8CFF, 7 to 0xFFFF3B30,
        8 to 0xFFFF2D55, 9 to 0xFF30B0C7, 10 to 0xFF34C759, 11 to 0xFFAF52DE
    )

    private fun iconFile(context: Context, bundleId: String) =
        File(File(context.filesDir, "app_icons").apply { mkdirs() }, "${bundleId.replace('/', '_')}.png")

    /** Cached App Store icon (memory or disk). Null if we haven't fetched it yet. */
    suspend fun cached(context: Context, bundleId: String): Bitmap? {
        if (bundleId.isEmpty()) return null
        memory.get(bundleId)?.let { return it }
        return withContext(Dispatchers.IO) {
            val file = iconFile(context, bundleId)
            if (file.exists()) BitmapFactory.decodeFile(file.path)?.also { memory.put(bundleId, it) } else null
        }
    }

    /** In-memory App Store icon only (no disk I/O) — for non-suspending callers. */
    fun peek(bundleId: String): Bitmap? = memory.get(bundleId)

    /** Whether a network lookup is worth trying for this bundle ID right now. */
    fun shouldFetch(context: Context, bundleId: String): Boolean {
        if (bundleId.isEmpty() || bundleId.startsWith("com.apple.")) return false
        if (bundleId in inFlight) return false
        val missedAt = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("miss_$bundleId", 0)
        return System.currentTimeMillis() - missedAt > RETRY_AFTER_MISS_MS
    }

    /** Look up and download the App Store icon; caches it on success. */
    suspend fun fetch(context: Context, bundleId: String): Bitmap? {
        if (!inFlight.add(bundleId)) return null
        try {
            return withContext(Dispatchers.IO) {
                val artworkUrl = lookupArtworkUrl(bundleId, null)
                    ?: Locale.getDefault().country.takeIf { it.isNotEmpty() && it != "US" }
                        ?.let { lookupArtworkUrl(bundleId, it) }
                if (artworkUrl == null) {
                    Log.i(TAG, "No App Store listing for $bundleId")
                    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putLong("miss_$bundleId", System.currentTimeMillis()) }
                    return@withContext null
                }
                val raw = download(artworkUrl) ?: return@withContext null
                val icon = roundedIcon(raw)
                iconFile(context, bundleId).outputStream().use { icon.compress(Bitmap.CompressFormat.PNG, 100, it) }
                memory.put(bundleId, icon)
                Log.i(TAG, "Cached App Store icon for $bundleId")
                icon
            }
        } catch (e: Exception) {
            // Offline or API hiccup — no negative cache, retry on the next notification
            Log.w(TAG, "Icon fetch failed for $bundleId: ${e.message}")
            return null
        } finally {
            inFlight.remove(bundleId)
        }
    }

    /**
     * iOS-style placeholder: the mapped glyph in white on the app's (or category's)
     * color. Used for Apple's own apps and until the real icon is cached.
     */
    fun generated(context: Context, bundleId: String, categoryId: Int): Bitmap {
        val key = "gen:$bundleId:$categoryId"
        memory.get(key)?.let { return it }

        val color = (appleAppColors[bundleId] ?: categoryColors[categoryId] ?: 0xFF8E8E93).toInt()
        val size = ICON_SIZE_PX
        val out = createBitmap(size, size)
        val canvas = Canvas(out)
        canvas.drawPath(roundedSquarePath(size), Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })

        ContextCompat.getDrawable(context, AppIconMapper.getIconResId(bundleId, categoryId))?.mutate()?.let { glyph ->
            glyph.setTint(Color.WHITE)
            // Vector glyphs already carry 25% padding; draw them over most of the square
            val inset = size / 8
            glyph.setBounds(inset, inset, size - inset, size - inset)
            glyph.draw(canvas)
        }
        memory.put(key, out)
        return out
    }

    private fun lookupArtworkUrl(bundleId: String, country: String?): String? {
        val query = "bundleId=" + URLEncoder.encode(bundleId, "UTF-8") +
            (country?.let { "&country=" + it.lowercase(Locale.US) } ?: "")
        val body = Http.get("https://itunes.apple.com/lookup?$query")?.toString(Charsets.UTF_8) ?: return null
        val results = JSONObject(body).optJSONArray("results") ?: return null
        if (results.length() == 0) return null
        val app = results.getJSONObject(0)
        val url = app.optString("artworkUrl512").ifEmpty { app.optString("artworkUrl100") }
        // mzstatic serves any size — ask for exactly what we render
        return url.ifEmpty { null }?.replace(Regex("\\d+x\\d+bb"), "${ICON_SIZE_PX}x${ICON_SIZE_PX}bb")
    }

    private fun download(url: String): Bitmap? =
        Http.get(url)?.let { Http.decodeBitmap(it, ICON_SIZE_PX) }

    /**
     * Square App Store artwork → iOS-style rounded square, inset so it survives the
     * circular crop Wear OS applies to notification icons.
     */
    private fun roundedIcon(src: Bitmap): Bitmap {
        val size = ICON_SIZE_PX
        val out = createBitmap(size, size)
        val canvas = Canvas(out)
        val path = roundedSquarePath(size)
        canvas.clipPath(path)
        val bounds = RectF()
        path.computeBounds(bounds, true)
        canvas.drawBitmap(src, null, bounds, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        return out
    }

    private fun roundedSquarePath(size: Int): Path {
        // A rounded square of side s with corner radius 0.225s fits in a circle of
        // diameter size when s ≤ size / 1.228 — use 0.8 for a little margin.
        val side = size * 0.8f
        val offset = (size - side) / 2f
        val radius = side * 0.225f
        return Path().apply {
            addRoundRect(RectF(offset, offset, offset + side, offset + side), radius, radius, Path.Direction.CW)
        }
    }
}
