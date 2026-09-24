package com.wearos.ancsbridge.net

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Plain HTTPS GET for Apple's public lookup/search APIs and their artwork. Call off the main thread. */
object Http {

    private const val TIMEOUT_MS = 5_000
    /** A search reply or a 300 px cover is well under this; anything bigger is not what we asked for. */
    private const val MAX_BYTES = 2 * 1024 * 1024

    /**
     * Response body, or null for any status other than 200 or a body over [MAX_BYTES].
     * Throws on network errors.
     */
    fun get(url: String): ByteArray? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            if (conn.contentLengthLong > MAX_BYTES) return null
            conn.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val chunk = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    if (out.size() + n > MAX_BYTES) return null
                    out.write(chunk, 0, n)
                }
                out.toByteArray()
            }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Decode an image no larger than needed: whole-power-of-two downsampling while both
     * sides stay at least [minPx], so an unexpectedly huge image can't fill the heap.
     */
    fun decodeBitmap(bytes: ByteArray, minPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= minPx && bounds.outHeight / (sample * 2) >= minPx) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
