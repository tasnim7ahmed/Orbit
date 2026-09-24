package com.wearos.ancsbridge.media

import com.wearos.ancsbridge.media.ArtworkMatcher.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArtworkMatcherTest {

    // Shape of a real itunes.apple.com/search?term=Coldplay+Yellow&entity=song reply
    private val yellowResults = listOf(
        Candidate("Yellow", "Coldplay", "Parachutes", "https://x/parachutes/100x100bb.jpg"),
        Candidate("Yellow (Live)", "Coldplay", "Live 2012", "https://x/live/100x100bb.jpg"),
        Candidate(
            "Coldplay - Yellow - Piano Instrumental", "Piano Instrumentals & Piano Covers",
            "Coldplay - Piano Instrumentals (Piano Covers) - EP", "https://x/piano/100x100bb.jpg"
        )
    )

    @Test
    fun normalizeDropsDecorations() {
        assertEquals("yellow", ArtworkMatcher.normalize("Yellow (Remastered 2011)"))
        assertEquals("yellow", ArtworkMatcher.normalize("Yellow - Live at Wembley"))
        assertEquals("blinding lights", ArtworkMatcher.normalize("Blinding Lights [feat. X]"))
        assertEquals("beyoncé", ArtworkMatcher.normalize("Beyoncé"))
    }

    @Test
    fun albumBreaksTiesBetweenVersions() {
        assertEquals("Parachutes", ArtworkMatcher.pickSong("Yellow", "Coldplay", "Parachutes", yellowResults)?.album)
        assertEquals("Live 2012", ArtworkMatcher.pickSong("Yellow", "Coldplay", "Live 2012", yellowResults)?.album)
    }

    @Test
    fun exactTitleBeatsLooseTitleWithoutAlbum() {
        assertEquals("Parachutes", ArtworkMatcher.pickSong("Yellow", "Coldplay", "", yellowResults)?.album)
    }

    @Test
    fun wrongArtistIsNeverUsed() {
        assertNull(ArtworkMatcher.pickSong("Yellow", "Katy Perry", "", yellowResults))
    }

    @Test
    fun wrongTitleIsNeverUsed() {
        assertNull(ArtworkMatcher.pickSong("Clocks", "Coldplay", "", yellowResults))
    }

    @Test
    fun featuredArtistsStillMatchTheLeadArtist() {
        val results = listOf(Candidate("Stay", "The Kid LAROI & Justin Bieber", "F*CK LOVE 3", "https://x/stay/100x100bb.jpg"))
        assertEquals("F*CK LOVE 3", ArtworkMatcher.pickSong("Stay", "The Kid LAROI, Justin Bieber", "", results)?.album)
    }

    @Test
    fun missingTitleOrArtistMatchesNothing() {
        assertNull(ArtworkMatcher.pickSong("", "Coldplay", "", yellowResults))
        assertNull(ArtworkMatcher.pickSong("Yellow", "", "", yellowResults))
    }

    @Test
    fun resultWithoutArtworkIsSkipped() {
        val results = listOf(Candidate("Yellow", "Coldplay", "Parachutes", ""))
        assertNull(ArtworkMatcher.pickSong("Yellow", "Coldplay", "", results))
    }

    @Test
    fun podcastMatchesShowNameExactly() {
        val shows = listOf(
            Candidate("The Daily", "The New York Times", "The Daily", "https://x/daily/600x600bb.jpg"),
            Candidate("Up First from NPR", "NPR", "Up First from NPR", "https://x/upfirst/600x600bb.jpg")
        )
        assertEquals("https://x/daily/600x600bb.jpg", ArtworkMatcher.pickPodcast(listOf("The Daily"), shows)?.artworkUrl)
        assertNull(ArtworkMatcher.pickPodcast(listOf("Daily Show"), shows))
    }

    @Test
    fun sizedUrlAsksForTheDrawnSize() {
        assertEquals(
            "https://is1-ssl.mzstatic.com/a/b.jpg/300x300bb.jpg",
            ArtworkMatcher.sizedUrl("https://is1-ssl.mzstatic.com/a/b.jpg/100x100bb.jpg", 300)
        )
    }
}
