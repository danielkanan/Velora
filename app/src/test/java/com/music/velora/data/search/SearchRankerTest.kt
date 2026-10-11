package com.music.velora.data.search

import com.music.velora.data.model.BrowseItem
import com.music.velora.data.model.BrowseType
import com.music.velora.data.model.SearchFilter
import com.music.velora.data.model.SearchResult
import com.music.velora.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchRankerTest {

    @Test
    fun artistRanksAboveLooseSongMatchForNameQuery() {
        val artist = SearchResult.Browse(
            BrowseItem("UC1", "The Weeknd", "Artist • 12M subscribers", null, BrowseType.ARTIST),
        )
        val song = SearchResult.Track(
            Song("v1", "The Weeknd", "Some Other Act", null),
        )
        val ranked = SearchRanker.rank(
            "the weeknd",
            SearchFilter.ALL,
            listOf(song, artist),
        )
        assertTrue(ranked.first() is SearchResult.Browse)
    }

    @Test
    fun titleMatchBeatsObscureUploadWithEngagement() {
        val obscure = SearchResult.Track(
            Song("a", "Lost in the Fire", "Sylvara Omnell", null, engagementScore = 1_000),
        )
        val popular = SearchResult.Track(
            Song(
                "b",
                "Lost in the Fire",
                "Gesaffelstein & The Weeknd",
                null,
                engagementScore = 50_000_000,
                artistId = "UCW",
            ),
        )
        val ranked = SearchRanker.rank(
            "lost in the fire",
            SearchFilter.SONGS,
            listOf(obscure, popular),
        )
        assertEquals("b", (ranked.first() as SearchResult.Track).song.videoId)
    }

    @Test
    fun parsesEngagementFromSubtitleSegment() {
        assertEquals(12_400_000L, SearchRanker.parseEngagement("12.4M views"))
        assertEquals(12_000_000L, SearchRanker.parseEngagement("12M subscribers"))
    }

    @Test
    fun alanWalkerArtistBeforeTitleSpamSongs() {
        val alan = SearchResult.Browse(
            BrowseItem("UCA", "Alan Walker", "Artist • 4.2M subscribers", null, BrowseType.ARTIST),
        )
        val spam = SearchResult.Track(
            Song("spam", "Alan Walker - Faded (remix)", "Random Channel", null),
        )
        val real = SearchResult.Track(
            Song(
                "real",
                "Faded",
                "Alan Walker",
                null,
                artistId = "UCA",
                engagementScore = 100_000_000,
            ),
        )
        val composed = SearchRanker.composeAllTab(
            "alan walker",
            SearchRanker.rank("alan walker", SearchFilter.ALL, listOf(spam, real, alan)),
        )
        assertTrue(composed.first() is SearchResult.Browse)
        assertEquals("UCA", (composed.first() as SearchResult.Browse).item.browseId)
    }

    @Test
    fun artistNameSearchSkipsBackgroundEnrichment() {
        val weeknd = SearchResult.Browse(
            BrowseItem("UCW", "The Weeknd", "Artist • 12M subscribers", null, BrowseType.ARTIST),
        )
        val rows = listOf(weeknd)
        assertTrue(
            !SearchRanker.needsBackgroundEnrichment("the weekend", SearchFilter.ALL, rows),
        )
    }

    @Test
    fun songTitleSearchStillEnrichesWhenCatalogHitMissing() {
        val weak = SearchResult.Track(
            Song("a", "Lost in the Fire", "Nobody", null),
        )
        assertTrue(
            SearchRanker.needsBackgroundEnrichment("lost in the fire", SearchFilter.ALL, listOf(weak)),
        )
    }

    @Test
    fun artistTabRanksWeekndAboveMicroAccounts() {
        val weeknd = SearchResult.Browse(
            BrowseItem("UCW", "The Weeknd", "Artist", null, BrowseType.ARTIST),
        )
        val junk = SearchResult.Browse(
            BrowseItem("j", "On the weekend", "Artist • 19 subscribers", null, BrowseType.ARTIST),
        )
        val ranked = SearchRanker.rank(
            "the weekend",
            SearchFilter.ARTISTS,
            listOf(junk, weeknd),
        )
        assertEquals("UCW", (ranked.first() as SearchResult.Browse).item.browseId)
    }

    @Test
    fun weekendTypoPrefersTheWeekndOverMicroArtists() {
        val weeknd = SearchResult.Browse(
            BrowseItem("UCW", "The Weeknd", "Artist • 12M subscribers", null, BrowseType.ARTIST),
        )
        val junk = SearchResult.Browse(
            BrowseItem("j", "On the weekend", "Artist • 19 subscribers", null, BrowseType.ARTIST),
        )
        val ranked = SearchRanker.rank(
            "the weekend",
            SearchFilter.ALL,
            listOf(junk, weeknd),
        )
        assertEquals("UCW", (ranked.first() as SearchResult.Browse).item.browseId)
    }

    @Test
    fun songTitleQueryDoesNotPromoteSquatterArtist() {
        val squatter = SearchResult.Browse(
            BrowseItem("sq", "Lost In The Fire", "Artist • 5 subscribers", null, BrowseType.ARTIST),
        )
        val hit = SearchResult.Track(
            Song("b", "Lost in the Fire", "Gesaffelstein & The Weeknd", null, engagementScore = 40_000_000),
        )
        val ranked = SearchRanker.rank(
            "lost in the fire",
            SearchFilter.ALL,
            listOf(squatter, hit),
        )
        val composed = SearchRanker.composeAllTab("lost in the fire", ranked)
        assertTrue(composed.first() is SearchResult.Track)
        assertEquals("b", (composed.first() as SearchResult.Track).song.videoId)
    }

    @Test
    fun composeAllTabInterleavesArtistsAfterAnchorSong() {
        val weeknd = BrowseItem("UCW", "The Weeknd", "Artist • 12M subscribers", null, BrowseType.ARTIST)
        val obscure = SearchResult.Track(
            Song("a", "Lost in the Fire", "Sylvara Omnell", null),
        )
        val hit = SearchResult.Track(
            Song(
                "b",
                "Lost in the Fire",
                "Gesaffelstein & The Weeknd",
                null,
                artistId = "UCW",
                artists = listOf(com.music.velora.data.model.ArtistCredit("The Weeknd", "UCW")),
            ),
        )
        val artistRow = SearchResult.Browse(weeknd)
        val ranked = SearchRanker.rank(
            "lost in the fire",
            SearchFilter.ALL,
            listOf(obscure, artistRow, hit),
        )
        val composed = SearchRanker.composeAllTab("lost in the fire", ranked)
        assertEquals("b", (composed.first() as SearchResult.Track).song.videoId)
        assertTrue(composed.drop(1).first() is SearchResult.Browse)
        assertEquals("UCW", (composed[1] as SearchResult.Browse).item.browseId)
    }
}
