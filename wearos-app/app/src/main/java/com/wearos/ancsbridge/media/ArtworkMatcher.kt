package com.wearos.ancsbridge.media

import java.util.Locale

/**
 * Picks the album art for what the iPhone is playing from Apple's search results.
 *
 * Apple Media Service sends only text (title, artist, album), so the art is looked up by
 * name, and a wrong cover is worse than none: a result is used only when both the title
 * and the artist agree. Pure logic, kept apart from the network code so it is unit tested.
 */
object ArtworkMatcher {

    /** One search result, reduced to what matching needs. */
    data class Candidate(
        val title: String,
        val artist: String,
        val album: String,
        val artworkUrl: String
    )

    /**
     * Lower case, letters and digits only, and without the decorations that differ
     * between services: "Yellow (Remastered)", "Song - Live", "Track [feat. X]".
     */
    fun normalize(text: String): String {
        val core = text
            .replace(Regex("[(\\[].*?[)\\]]"), " ")
            .substringBefore(" - ")
            .lowercase(Locale.ROOT)
        return core.replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    }

    /** The main artist of "A, B & C" / "A feat. B" / "A x B". */
    private fun leadArtist(artist: String): String =
        normalize(artist.split(Regex(",|&| feat\\.? | ft\\.? | x ", RegexOption.IGNORE_CASE)).first())

    /** The best song for [title] by [artist], or null when nothing agrees on both. */
    fun pickSong(title: String, artist: String, album: String, candidates: List<Candidate>): Candidate? {
        val wantTitle = normalize(title)
        val wantArtist = leadArtist(artist)
        val wantAlbum = normalize(album)
        if (wantTitle.isEmpty() || wantArtist.isEmpty()) return null

        return candidates
            .filter { it.artworkUrl.isNotEmpty() }
            .mapNotNull { c ->
                val t = normalize(c.title)
                val a = normalize(c.artist)
                val titleScore = when {
                    t == wantTitle -> 2
                    t.isNotEmpty() && (t.startsWith(wantTitle) || wantTitle.startsWith(t)) -> 1
                    else -> return@mapNotNull null
                }
                if (a.isEmpty() || !(a.contains(wantArtist) || wantArtist.contains(a))) return@mapNotNull null
                val albumScore = if (wantAlbum.isNotEmpty() && normalize(c.album) == wantAlbum) 2 else 0
                c to titleScore + albumScore
            }
            .maxByOrNull { it.second }
            ?.first
    }

    /**
     * A podcast whose show name matches one of [showNames] exactly (after normalizing).
     * Players differ in whether the show arrives as the album or the artist, so both are tried.
     */
    fun pickPodcast(showNames: List<String>, candidates: List<Candidate>): Candidate? {
        val wanted = showNames.map { normalize(it) }.filter { it.isNotEmpty() }.toSet()
        if (wanted.isEmpty()) return null
        return candidates.firstOrNull { it.artworkUrl.isNotEmpty() && normalize(it.album) in wanted }
    }

    /** mzstatic serves any size: ask for exactly the pixels we draw. */
    fun sizedUrl(url: String, px: Int): String = url.replace(Regex("\\d+x\\d+bb"), "${px}x${px}bb")
}
