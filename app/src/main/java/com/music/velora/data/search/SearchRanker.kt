package com.music.velora.data.search

import com.music.velora.data.model.BrowseItem
import com.music.velora.data.model.BrowseType
import com.music.velora.data.model.SearchFilter
import com.music.velora.data.model.SearchResult
import com.music.velora.data.model.Song
import java.text.Normalizer
import java.util.Locale
import kotlin.math.max

/**
 * Re-orders YouTube Music search rows for the typed query.
 */
object SearchRanker {

    private enum class QueryIntent { ARTIST_NAME, SONG_TITLE, MIXED }

    fun rank(query: String, filter: SearchFilter, rows: List<SearchResult>): List<SearchResult> {
        if (rows.isEmpty() || query.isBlank()) return rows
        if (filter == SearchFilter.ARTISTS) {
            return rankArtistTab(query, rows)
        }
        val intent = queryIntent(query, rows)
        val indexed = rows.mapIndexed { index, row ->
            Scored(index, row, score(query, filter, row, intent))
        }
        return indexed.sortedWith(
            compareByDescending<Scored> { it.score }.thenBy { it.index },
        ).map { it.row }
    }

    fun composeAllTab(query: String, ranked: List<SearchResult>): List<SearchResult> {
        if (ranked.isEmpty() || query.isBlank()) return ranked
        val intent = queryIntent(query, ranked)
        val scores = ranked.associateWith { score(query, SearchFilter.ALL, it, intent) }
        if (intent == QueryIntent.ARTIST_NAME) {
            val artist = pickPrimaryArtist(query, ranked) ?: return composeTitleFirst(query, ranked, scores, intent)
            return composeArtistFirst(query, ranked, scores, artist)
        }
        return composeTitleFirst(query, ranked, scores, intent)
    }

    fun asTracks(rows: List<SearchResult>): List<SearchResult.Track> = rows.mapNotNull { row ->
        when (row) {
            is SearchResult.TopTrack -> SearchResult.Track(row.song)
            is SearchResult.Track -> row
            is SearchResult.Browse -> null
        }
    }

    fun alternateSearchQueries(query: String, suggestions: List<String>): List<String> {
        val trimmed = query.trim()
        val n = normalize(trimmed)
        val canon = queryForMatching(trimmed)
        return buildList {
            if (canon != n) add(canon)
            suggestions.forEach { s ->
                val t = s.trim()
                if (t.isNotEmpty() && !t.equals(trimmed, ignoreCase = true)) add(t)
            }
        }.distinct()
    }

    fun artistSearchQueries(query: String): List<String> {
        val trimmed = query.trim()
        val n = normalize(trimmed)
        val canon = queryForMatching(trimmed)
        return if (canon != n) listOf(trimmed, canon) else listOf(trimmed)
    }

    /**
     * Second-pass tab merging reshuffles the list. Skip it when the fast path
     * already nailed the intent (e.g. artist name searches).
     */
    fun needsBackgroundEnrichment(
        query: String,
        filter: SearchFilter,
        rows: List<SearchResult>,
    ): Boolean {
        if (filter != SearchFilter.ALL && filter != SearchFilter.SONGS) return false
        val intent = queryIntent(query, rows)
        if (intent == QueryIntent.ARTIST_NAME) return false
        if (intent == QueryIntent.SONG_TITLE) {
            return !hasStrongCatalogSongMatch(query, rows)
        }
        return true
    }

    private fun hasStrongCatalogSongMatch(query: String, rows: List<SearchResult>): Boolean {
        val q = queryForMatching(query)
        return rows.mapNotNull { songOf(it) }.any { song ->
            similarity(q, song.title) >= 0.88f &&
                (song.artistId != null || song.artists.any { it.browseId != null } ||
                    song.engagementScore >= 250_000L)
        }
    }

    fun mergeAndRank(
        query: String,
        filter: SearchFilter,
        primary: List<SearchResult>,
        extra: List<SearchResult>,
    ): List<SearchResult> {
        val seen = HashSet<String>()
        val merged = buildList {
            for (row in primary + extra) {
                val key = row.identityKey()
                if (seen.add(key)) add(row)
            }
        }
        return rank(query, filter, merged)
    }

    private data class Scored(val index: Int, val row: SearchResult, val score: Float)

    private fun SearchResult.identityKey(): String = when (this) {
        is SearchResult.TopTrack -> "v:${song.videoId}"
        is SearchResult.Track -> "v:${song.videoId}"
        is SearchResult.Browse -> "b:${item.browseId}"
    }

    private fun queryIntent(query: String, rows: List<SearchResult>): QueryIntent {
        val q = queryForMatching(query)
        val words = queryWordCount(q)
        if (words >= 4) return QueryIntent.SONG_TITLE

        val bestTitleSim = rows.mapNotNull { songOf(it) }
            .maxOfOrNull { similarity(q, it.title) } ?: 0f
        if (words >= 3 && bestTitleSim >= 0.92f) return QueryIntent.SONG_TITLE

        val artist = pickPrimaryArtist(query, rows)
        if (artist != null) {
            val sim = similarity(q, artist.item.title)
            val subs = engagementFromBrowseSubtitle(artist.item.subtitle)
            if (sim >= 0.78f && subs >= 3_000) return QueryIntent.ARTIST_NAME
            if (sim >= 0.88f && words <= 3) return QueryIntent.ARTIST_NAME
        }
        return QueryIntent.MIXED
    }

    private fun rankArtistTab(query: String, rows: List<SearchResult>): List<SearchResult> {
        val artists = rows.filterIsInstance<SearchResult.Browse>()
            .filter { it.item.type == BrowseType.ARTIST }
        val other = rows.filter { it !in artists }
        val sorted = artists.sortedWith(
            compareByDescending<SearchResult.Browse> { artistProfileScore(query, it.item) },
        )
        return sorted + other
    }

    private fun pickPrimaryArtist(query: String, rows: List<SearchResult>): SearchResult.Browse? =
        rows.filterIsInstance<SearchResult.Browse>()
            .filter { it.item.type == BrowseType.ARTIST }
            .maxByOrNull { artistProfileScore(query, it.item) }

    private fun artistProfileScore(query: String, item: BrowseItem): Float {
        val titleSim = similarity(query, item.title)
        val subs = engagementFromBrowseSubtitle(item.subtitle)
        val pop = engagementBoost(subs)
        return titleSim * 1.25f + pop * 1.6f - artistSquatterPenalty(query, item, titleSim)
    }

    private fun score(
        query: String,
        filter: SearchFilter,
        row: SearchResult,
        intent: QueryIntent,
    ): Float = when (row) {
        is SearchResult.TopTrack -> trackScore(query, row.song, intent)
        is SearchResult.Track -> trackScore(query, row.song, intent)
        is SearchResult.Browse -> browseScore(query, filter, row, intent)
    }

    private fun browseScore(
        query: String,
        filter: SearchFilter,
        row: SearchResult.Browse,
        intent: QueryIntent,
    ): Float {
        val titleSim = similarity(query, row.item.title)
        val subtitleSim = similarity(query, row.item.subtitle)
        val popularity = engagementBoost(engagementFromBrowseSubtitle(row.item.subtitle))
        return when (row.item.type) {
            BrowseType.ARTIST -> {
                val nameBoost = if (titleSim >= 0.82f) 0.18f else 0f
                var s = titleSim * 1.1f + nameBoost + popularity - artistSquatterPenalty(query, row.item, titleSim)
                if (intent == QueryIntent.ARTIST_NAME && titleSim >= 0.75f) {
                    s += 0.55f + popularity * 0.5f
                }
                s
            }
            BrowseType.ALBUM -> {
                var s = max(titleSim, subtitleSim * 0.85f) * 0.95f + popularity * 0.35f
                if (intent == QueryIntent.ARTIST_NAME) s += albumArtistBoost(query, row.item)
                s
            }
            BrowseType.PLAYLIST -> {
                val base = titleSim * 0.9f + subtitleSim * 0.2f + popularity * 0.25f
                if (intent == QueryIntent.ARTIST_NAME) base * 0.45f - 0.12f else base
            }
            BrowseType.OTHER -> titleSim * 0.85f + subtitleSim * 0.15f
        }
    }

    private fun albumArtistBoost(query: String, item: BrowseItem): Float {
        val q = queryForMatching(query)
        if (similarity(q, item.subtitle) >= 0.55f) return 0.12f
        if (artistTokens(item.subtitle).any { similarity(q, it) >= 0.78f }) return 0.15f
        return 0f
    }

    private fun artistSquatterPenalty(query: String, item: BrowseItem, titleSim: Float): Float {
        if (item.type != BrowseType.ARTIST) return 0f
        val subs = engagementFromBrowseSubtitle(item.subtitle)
        if (titleSim < 0.85f) return 0f
        // YT often omits subscriber counts — don't treat unknown as a micro-account.
        if (subs == 0L) return 0f
        return when {
            subs >= 50_000 -> 0f
            subs >= 5_000 -> if (titleSim >= 0.95f) 0.25f else 0f
            subs >= 500 -> 0.45f
            else -> 0.75f
        }
    }

    private fun trackScore(query: String, song: Song, intent: QueryIntent): Float {
        val q = queryForMatching(query)
        val titleSim = similarity(q, song.title)
        val artistSim = similarity(q, song.artist)
        val tokenCount = queryWordCount(normalize(query))
        val textScore = if (tokenCount >= 3) {
            titleSim * 0.82f + artistSim * 0.18f
        } else {
            artistSim * 0.55f + titleSim * 0.35f + max(titleSim, artistSim) * 0.1f
        }
        var s = textScore +
            titleMatchBoost(query, song) +
            catalogBoost(song) +
            engagementBoost(song.engagementScore) -
            uploadPenalty(query, song) -
            titleKeywordSpamPenalty(query, song)
        if (intent == QueryIntent.ARTIST_NAME) {
            if (songCreditedToQueryArtist(query, song)) {
                s += 0.25f
            } else {
                s -= 0.35f
            }
        }
        return s
    }

    private fun songCreditedToQueryArtist(query: String, song: Song): Boolean {
        val q = queryForMatching(query)
        if (similarity(q, song.artist) >= 0.78f) return true
        return artistTokens(song.artist).any { similarity(q, it) >= 0.78f }
    }

    /** "Alan Walker - Faded" by a random channel — query in title, not in credits. */
    private fun titleKeywordSpamPenalty(query: String, song: Song): Float {
        val q = queryForMatching(query)
        if (queryWordCount(q) > 3) return 0f
        if (songCreditedToQueryArtist(query, song)) return 0f
        val titleSim = similarity(q, song.title)
        val artistSim = similarity(q, song.artist)
        if (titleSim < 0.72f) return 0f
        if (titleSim <= artistSim + 0.05f) return 0f
        return 0.35f + if (titleSim >= 0.88f) 0.2f else 0f
    }

    private fun uploadPenalty(query: String, song: Song): Float {
        if (similarity(queryForMatching(query), song.title) < 0.82f) return 0f
        if (song.artistId != null || song.artists.any { it.browseId != null }) return 0f
        if (song.albumId != null) return 0f
        if (song.engagementScore >= 500_000L) return 0f
        return 0.42f
    }

    private fun titleMatchBoost(query: String, song: Song): Float {
        val q = normalize(query)
        val t = normalize(song.title)
        if (q.isEmpty() || t.isEmpty()) return 0f
        if (q == t) return 0.14f
        if (t.contains(q) || q.contains(t)) return 0.07f
        return 0f
    }

    private fun catalogBoost(song: Song): Float {
        var boost = 0f
        if (song.artistId != null) boost += 0.14f
        if (song.artists.any { it.browseId != null }) boost += 0.08f
        if (song.albumId != null) boost += 0.08f
        return boost
    }

    private fun engagementBoost(score: Long): Float {
        if (score <= 0L) return 0f
        val log10 = kotlin.math.log10(score.toDouble().coerceAtLeast(1.0))
        return ((log10 - 2.5) / 5.5).toFloat().coerceIn(0f, 0.5f)
    }

    private fun songOf(row: SearchResult): Song? = when (row) {
        is SearchResult.TopTrack -> row.song
        is SearchResult.Track -> row.song
        is SearchResult.Browse -> null
    }

    internal fun artistTokens(artist: String): List<String> =
        artist.split(ARTIST_SPLIT)
            .map { it.trim() }
            .filter { it.isNotBlank() }

    private fun composeTitleFirst(
        query: String,
        ranked: List<SearchResult>,
        scores: Map<SearchResult, Float>,
        intent: QueryIntent,
    ): List<SearchResult> {
        val used = LinkedHashSet<String>()
        val out = ArrayList<SearchResult>(ranked.size)

        fun add(row: SearchResult) {
            if (used.add(row.identityKey())) out.add(row)
        }

        val tracksByScore = ranked.filter { songOf(it) != null }
            .sortedByDescending { scores[it] ?: 0f }
        val anchor = tracksByScore.firstOrNull { row ->
            val song = songOf(row) ?: return@firstOrNull false
            similarity(query, song.title) >= 0.82f
        } ?: tracksByScore.firstOrNull()
        val anchorSong = anchor?.let { songOf(it) }
        val tokens = anchorSong?.let { artistTokens(it.artist) } ?: emptyList()

        if (anchor != null) add(anchor)

        if (tokens.isNotEmpty()) {
            ranked.filterIsInstance<SearchResult.Browse>()
                .filter { it.item.type == BrowseType.ARTIST }
                .sortedByDescending { scores[it] ?: 0f }
                .forEach { browse ->
                    val credited = tokens.any { token -> similarity(token, browse.item.title) >= 0.78f }
                    if (!credited) return@forEach
                    val subs = engagementFromBrowseSubtitle(browse.item.subtitle)
                    val namedLikeQuery = similarity(query, browse.item.title) >= 0.88f
                    if (namedLikeQuery && subs < 5_000) return@forEach
                    add(browse)
                }
        }

        ranked.filterIsInstance<SearchResult.Browse>()
            .filter { it.item.type == BrowseType.ALBUM }
            .sortedByDescending { scores[it] ?: 0f }
            .forEach { browse ->
                val albumMatch = anchorSong?.albumName?.let {
                    similarity(it, browse.item.title) >= 0.8f
                } == true
                val creditMatch = tokens.any { token ->
                    browse.item.subtitle.contains(token, ignoreCase = true) ||
                        browse.item.title.contains(token, ignoreCase = true)
                }
                if (albumMatch || creditMatch) add(browse)
            }

        ranked.filter { row ->
            row is SearchResult.Browse && row.item.type != BrowseType.PLAYLIST ||
                songOf(row) != null
        }.forEach { add(it) }

        if (intent != QueryIntent.ARTIST_NAME) {
            ranked.filterIsInstance<SearchResult.Browse>()
                .filter { it.item.type == BrowseType.PLAYLIST }
                .sortedByDescending { scores[it] ?: 0f }
                .take(2)
                .forEach { add(it) }
        }
        return out
    }

    private fun composeArtistFirst(
        query: String,
        ranked: List<SearchResult>,
        scores: Map<SearchResult, Float>,
        artist: SearchResult.Browse,
    ): List<SearchResult> {
        val used = LinkedHashSet<String>()
        val out = ArrayList<SearchResult>(ranked.size)
        fun add(row: SearchResult) {
            if (used.add(row.identityKey())) out.add(row)
        }
        val name = artist.item.title
        add(artist)

        ranked.filterIsInstance<SearchResult.Browse>()
            .filter { it.item.type == BrowseType.ALBUM }
            .sortedByDescending { scores[it] ?: 0f }
            .forEach { browse ->
                if (albumArtistBoost(query, browse.item) > 0f) add(browse)
            }

        ranked.filter { row ->
            val song = songOf(row) ?: return@filter false
            songCreditedToQueryArtist(query, song) ||
                similarity(name, song.artist) >= 0.55f ||
                artistTokens(song.artist).any { similarity(name, it) >= 0.78f }
        }.sortedByDescending { scores[it] ?: 0f }
            .forEach { add(it) }

        ranked.filter { row ->
            row is SearchResult.Browse && row.item.type != BrowseType.PLAYLIST ||
                (songOf(row) != null)
        }.forEach { add(it) }

        ranked.filterIsInstance<SearchResult.Browse>()
            .filter { it.item.type == BrowseType.PLAYLIST }
            .sortedByDescending { scores[it] ?: 0f }
            .forEach { add(it) }

        return out
    }

    private val ARTIST_SPLIT = Regex(
        """\s*[,&]\s*|\s+(?:feat\.?|ft\.?|x|and)\s+""",
        RegexOption.IGNORE_CASE,
    )

    internal fun queryForMatching(query: String): String {
        val n = normalize(query)
        return when (n) {
            "the weekend", "weekend" -> "the weeknd"
            else -> n
        }
    }

    private fun queryWordCount(normalizedQuery: String): Int =
        normalizedQuery.split(" ").count { it.isNotBlank() }

    internal fun engagementFromBrowseSubtitle(subtitle: String): Long =
        subtitle.split("•", "·")
            .mapNotNull { parseEngagement(it.trim()) }
            .maxOrNull() ?: 0L

    internal fun similarity(query: String, text: String): Float {
        val q = queryForMatching(query)
        val t = normalize(text)
        if (t.isEmpty() || q.isEmpty()) return 0f
        if (q == t) return 1f
        if (t.contains(q) || q.contains(t)) return 0.88f
        val qTokens = q.split(" ").filter { it.length > 1 }
        if (qTokens.isEmpty()) return 0f
        val tTokens = t.split(" ").filter { it.isNotBlank() }.toSet()
        val hits = qTokens.count { qt ->
            val qn = normalizeArtistToken(qt)
            tTokens.any { tt ->
                val tn = normalizeArtistToken(tt)
                tn == qn || tn.startsWith(qn) || qn.startsWith(tn)
            }
        }
        return hits.toFloat() / qTokens.size
    }

    private fun normalizeArtistToken(token: String): String = when (token) {
        "weekend", "weekends" -> "weeknd"
        else -> token
    }

    internal fun normalize(text: String): String {
        val folded = Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
        return folded.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    internal fun parseEngagement(segment: String): Long? {
        val match = ENGAGEMENT.find(segment.trim()) ?: return null
        val number = match.groupValues[1].replace(",", ".").toDoubleOrNull() ?: return null
        val unit = match.groupValues.getOrNull(2)?.uppercase(Locale.ROOT).orEmpty()
        val multiplier = when (unit) {
            "K" -> 1_000L
            "M" -> 1_000_000L
            "B" -> 1_000_000_000L
            else -> 1L
        }
        return (number * multiplier).toLong()
    }

    private val ENGAGEMENT = Regex(
        """([\d.,]+)\s*([KMB])?\s+(views|plays|likes|subscribers)\b""",
        RegexOption.IGNORE_CASE,
    )
}
