package com.wearos.ancsbridge.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import androidx.core.content.edit
import com.wearos.ancsbridge.model.MediaState
import com.wearos.ancsbridge.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale

/**
 * Album art for what the iPhone is playing.
 *
 * Apple Media Service carries no artwork, only the track's text. Apple's public search
 * API (itunes.apple.com/search, the same service the app icons come from) finds the
 * song, or the podcast show, by name.
 *
 * Wi-Fi is the watch's biggest battery cost, so the network is used as little as possible:
 * covers are cached on disk per album (every track of an album shares one lookup), and a
 * track with no trustworthy match is remembered as a miss for a few days. A network
 * failure is not a miss: it is simply tried again the next time the track comes up.
 */
object ArtworkRepository {

    private const val TAG = "Artwork"
    /** Drawn full-screen on Now Playing; the watch is 384 px across. */
    const val ART_PX = 300
    private const val MAX_DISK_FILES = 40
    private const val MISS_PREFS = "artwork_misses"
    private const val RETRY_AFTER_MISS_MS = 3L * 24 * 60 * 60 * 1000 // 3 days
    private const val MAX_MISSES = 200

    private val memory = LruCache<String, Bitmap>(6)

    /** Identity of a track, for tagging its artwork; null when there is nothing to look up. */
    fun keyOf(media: MediaState): String? {
        if (!media.available || media.title.isBlank()) return null
        return listOf(media.title, media.artist, media.album).joinToString("\u001f") { it.trim().lowercase(Locale.ROOT) }
    }

    /** Covers are stored per album when the player names one, else per track. */
    private fun coverKey(media: MediaState): String =
        if (media.album.isNotBlank()) {
            "album\u001f" + listOf(media.artist, media.album).joinToString("\u001f") { it.trim().lowercase(Locale.ROOT) }
        } else {
            "track\u001f" + keyOf(media)
        }

    /** Cached or freshly looked-up art for [media], or null. Never throws. */
    suspend fun fetch(context: Context, media: MediaState): Bitmap? {
        val trackKey = keyOf(media) ?: return null
        val coverKey = coverKey(media)
        memory.get(coverKey)?.let { return it }
        return withContext(Dispatchers.IO) {
            try {
                val file = cacheFile(context, coverKey)
                if (file.exists()) {
                    BitmapFactory.decodeFile(file.path)?.let { cached ->
                        file.setLastModified(System.currentTimeMillis())
                        memory.put(coverKey, cached)
                        return@withContext cached
                    }
                }
                if (recentlyMissed(context, trackKey)) return@withContext null
                val url = lookup(media)
                if (url == null) {
                    Log.i(TAG, "No album art match")
                    recordMiss(context, trackKey)
                    return@withContext null
                }
                val bytes = Http.get(ArtworkMatcher.sizedUrl(url, ART_PX)) ?: return@withContext null
                val bitmap = Http.decodeBitmap(bytes, ART_PX) ?: return@withContext null
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                prune(file.parentFile)
                memory.put(coverKey, bitmap)
                Log.i(TAG, "Cached album art")
                bitmap
            } catch (e: Exception) {
                // Offline, a timeout or a server error: no miss recorded, try again next time
                Log.w(TAG, "Album art lookup failed: ${e.javaClass.simpleName}")
                null
            }
        }
    }

    private fun lookup(media: MediaState): String? {
        if (media.artist.isNotBlank()) {
            val songs = search("${media.artist} ${media.title}", "song")
            ArtworkMatcher.pickSong(media.title, media.artist, media.album, songs)?.let { return it.artworkUrl }
        }
        // Podcast episodes rarely match a song; their show has the art
        val shows = listOf(media.album, media.artist).filter { it.isNotBlank() }.distinct()
        for (show in shows) {
            ArtworkMatcher.pickPodcast(shows, search(show, "podcast"))?.let { return it.artworkUrl }
        }
        return null
    }

    /** Search results; throws when the search itself failed, so that is never taken for "no match". */
    private fun search(term: String, entity: String): List<ArtworkMatcher.Candidate> {
        val url = "https://itunes.apple.com/search?media=" + (if (entity == "song") "music" else "podcast") +
            "&entity=$entity&limit=10&term=" + URLEncoder.encode(term, "UTF-8")
        val body = Http.get(url)?.toString(Charsets.UTF_8) ?: throw IOException("search failed")
        val results = JSONObject(body).optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).map { i ->
            val r = results.getJSONObject(i)
            ArtworkMatcher.Candidate(
                title = r.optString(if (entity == "song") "trackName" else "collectionName"),
                artist = r.optString("artistName"),
                album = r.optString("collectionName"),
                artworkUrl = r.optString("artworkUrl600").ifEmpty { r.optString("artworkUrl100") }
            )
        }
    }

    private fun missPrefs(context: Context) = context.getSharedPreferences(MISS_PREFS, Context.MODE_PRIVATE)

    private fun recentlyMissed(context: Context, trackKey: String): Boolean {
        val at = missPrefs(context).getLong(hash(trackKey), 0L)
        return at != 0L && System.currentTimeMillis() - at < RETRY_AFTER_MISS_MS
    }

    private fun recordMiss(context: Context, trackKey: String) {
        val prefs = missPrefs(context)
        val now = System.currentTimeMillis()
        // Expired entries go, and the list stays short
        val entries = prefs.all.mapNotNull { (k, v) -> (v as? Long)?.let { k to it } }
        prefs.edit {
            putLong(hash(trackKey), now)
            entries.filter { now - it.second >= RETRY_AFTER_MISS_MS }.forEach { remove(it.first) }
            entries.filter { now - it.second < RETRY_AFTER_MISS_MS }
                .sortedBy { it.second }
                .dropLast(MAX_MISSES - 1)
                .forEach { remove(it.first) }
        }
    }

    private fun hash(key: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
        return digest.joinToString("") { String.format(Locale.ROOT, "%02x", it) }
    }

    private fun cacheFile(context: Context, coverKey: String): File =
        File(File(context.cacheDir, "artwork").apply { mkdirs() }, "${hash(coverKey)}.jpg")

    /** Keep the most recently used covers only. */
    private fun prune(dir: File?) {
        val files = dir?.listFiles() ?: return
        if (files.size <= MAX_DISK_FILES) return
        files.sortedBy { it.lastModified() }.take(files.size - MAX_DISK_FILES).forEach { it.delete() }
    }
}
