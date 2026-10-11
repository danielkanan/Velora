package com.music.velora.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.NorthWest
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import coil3.compose.AsyncImage
import com.music.velora.data.model.BrowseItem
import com.music.velora.data.model.BrowseType
import com.music.velora.data.model.EntityType
import com.music.velora.data.model.ROW_ART_PX
import com.music.velora.data.model.SEARCH_FILTER_CHIP_ORDER
import com.music.velora.data.model.SearchFilter
import com.music.velora.data.model.artworkAt
import com.music.velora.data.model.SearchResult
import com.music.velora.data.model.Song
import com.music.velora.data.model.UiState
import com.music.velora.R
import com.music.velora.data.model.SearchHistoryEntity
import com.music.velora.ui.components.MessageState
import com.music.velora.ui.components.PAGE_GUTTER
import com.music.velora.ui.components.topBarContentPadding
import com.music.velora.ui.components.topBarHeight
import com.music.velora.ui.components.SearchField
import com.music.velora.ui.components.frostedSurface
import androidx.compose.runtime.SideEffect
import com.music.velora.ui.components.SongRow
import com.music.velora.ui.components.shelfTypeLabel
import com.music.velora.ui.components.thumbnailBorder
import com.music.velora.ui.components.songListSkeleton
import com.music.velora.ui.haptics.Haptic
import com.music.velora.ui.haptics.rememberHaptics
import dev.chrisbanes.haze.HazeState
import java.util.Locale

/** Filter chip row hosted below the top bar when results are showing. */
val SearchFilterChromeHeight = 44.dp

/** Vertical padding on search result rows (songs, artists, albums, history, etc.). */
private val SearchResultRowPaddingV = 10.dp

private val SearchResultArtSize = 58.dp
private val SearchSquareArtCorner = 5.dp

private fun searchSquareArtShape() = RoundedCornerShape(SearchSquareArtCorner)

/** Matches [SongRow] primary line — 17sp, normal weight. */
@Composable
private fun searchResultTitleStyle() = MaterialTheme.typography.titleMedium.copy(
    fontWeight = FontWeight.W400,
    fontSize = 17.sp,
)

@Composable
private fun SearchResultTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = searchResultTitleStyle(),
        color = MaterialTheme.colorScheme.onBackground,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** Space between the in-bar search field and the filter row (field bottom pad). */
val SearchFilterBelowBarGap = 4.dp

@Composable
fun searchFilterChromeTopPadding(): Dp = topBarHeight() + SearchFilterBelowBarGap

/**
 * Optional filter chips for the frosted chrome layout — the search field lives
 * in [FrostedTopBar.leadingBarContent].
 */
@Composable
fun SearchScreenChrome(
    showFilters: Boolean,
    filter: SearchFilter,
    onFilterChange: (SearchFilter) -> Unit,
    hazeState: HazeState,
    modifier: Modifier = Modifier,
    onHeightChanged: (Dp) -> Unit = {},
) {
    val density = LocalDensity.current
    if (!showFilters) {
        SideEffect { onHeightChanged(0.dp) }
        return
    }
    SearchFilterTabs(
        filter = filter,
        onFilterChange = onFilterChange,
        hazeState = hazeState,
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { size ->
                onHeightChanged(with(density) { size.height.toDp() })
            },
    )
}

@Composable
fun SearchScreen(
    query: String,
    onQueryChange: (String) -> Unit,
    filter: SearchFilter,
    onFilterChange: (SearchFilter) -> Unit,
    results: UiState<List<SearchResult>>?,
    loadingMore: Boolean,
    onLoadMore: () -> Unit,
    listState: LazyListState,
    scrollResetTrigger: Int,
    focusRequested: Boolean,
    onFocusHandled: () -> Unit,
    onSongClick: (List<Song>, Int) -> Unit,
    onSongLongPress: (Song) -> Unit,
    onSongSwipe: (Song) -> Unit,
    onBrowseClick: (BrowseItem) -> Unit,
    /**
     * Holding an album or playlist hit rather than tapping it — the same menu
     * the shelves open, so a release found by searching can go on the queue
     * without a trip through its page.
     */
    onBrowseLongPress: ((BrowseItem) -> Unit)? = null,
    history: List<SearchHistoryEntity>,
    suggestions: List<String>,
    typeaheadResults: List<SearchResult>,
    onSubmit: () -> Unit,
    onSuggestionClick: (String) -> Unit,
    onHistoryClick: (SearchHistoryEntity) -> Unit,
    onHistoryClear: () -> Unit,
    /** Long-press handler for typeahead rows — opens the song actions sheet. */
    onTypeaheadLongPress: ((Song) -> Unit)? = null,
    /**
     * When false, the field/filters are drawn by [SearchScreenChrome] in the
     * app chrome. The list still reserves space for them.
     */
    embedChrome: Boolean = true,
    /** Height of externally hosted chrome when [embedChrome] is false. */
    externalChromeHeight: Dp = 0.dp,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues,
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    // Tapping the search tab from the nav bar sets focusRequested;
    // respond by focusing the field and opening the keyboard.
    // Skip when chrome is external — MainActivity owns that focus requester.
    LaunchedEffect(focusRequested, embedChrome) {
        if (focusRequested && embedChrome) {
            focusRequester.requestFocus()
            keyboardController?.show()
            onFocusHandled()
        }
    }
    // Search keeps one list state while its contents change. Reset it for each
    // new request so choosing a recent search cannot inherit the history's
    // previous scroll position (or a previous result page's position).
    LaunchedEffect(scrollResetTrigger) {
        if (scrollResetTrigger > 0) listState.scrollToItem(0)
    }
    // A non-empty suggestion list means the field is mid-edit — see
    // MainViewModel.suggestions. Nothing below it is worth showing while it is
    // up: the results are for whatever was searched before this edit began,
    // and so are the filter tabs above them.
    val suggesting = suggestions.isNotEmpty()
    // Live media results arrive from the parallel typeahead pipeline; show
    // them only while the user is still typing (suggestions visible), so they
    // appear as a dropdown beneath the text completions rather than floating
    // after the search has committed.
    val showTypeahead = typeaheadResults.isNotEmpty() && suggesting
    LaunchedEffect(listState, results, loadingMore) {
        if (results !is UiState.Success) return@LaunchedEffect
        snapshotFlow {
            val layout = listState.layoutInfo
            (layout.visibleItemsInfo.lastOrNull()?.index ?: -1) to layout.totalItemsCount
        }.collect { (lastVisible, total) ->
            if (!loadingMore && total > 0 && lastVisible >= total - 4) onLoadMore()
        }
    }
    // When [embedChrome] is false, MainActivity hosts [SearchScreenChrome]
    // with the frosted floating chrome. Otherwise keep the stacked in-page field.
    val density = LocalDensity.current
    // Seeded so the first frame already clears the field before measure lands.
    var chromeHeight by remember { mutableStateOf(0.dp) }

    @Composable
    fun EmbeddedSearchChrome(modifier: Modifier = Modifier) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .onSizeChanged { size ->
                    chromeHeight = with(density) { size.height.toDp() }
                },
        ) {
            SearchField(
                query = query,
                onQueryChange = onQueryChange,
                onSubmit = onSubmit,
                focusRequester = focusRequester,
                modifier = Modifier.padding(start = PAGE_GUTTER, end = PAGE_GUTTER, bottom = 4.dp),
            )
            if (results != null && !suggesting) {
                SearchFilterTabs(
                    filter = filter,
                    onFilterChange = onFilterChange,
                    hazeState = null,
                )
            }
        }
    }

    fun LazyListScope.searchBody() {
        when {
            suggesting -> {
                searchSuggestions(
                    suggestions = suggestions,
                    // Picking one is done typing, so the keyboard comes down
                    // with it and the results get the whole screen.
                    onClick = { term ->
                        onSuggestionClick(term)
                        focusManager.clearFocus()
                    },
                    onFill = onQueryChange,
                )
                if (showTypeahead) {
                    searchTypeaheadDropdown(
                        typeaheadResults = typeaheadResults,
                        onSongClick = { song -> onSongClick(listOf(song), 0) },
                        onSongLongPress = onTypeaheadLongPress,
                        onBrowseClick = { item ->
                            onBrowseClick(item)
                        },
                    )
                }
            }
            results == null -> if (history.isEmpty()) {
                item { MessageState(stringResource(R.string.search_empty)) }
            } else {
                recentSearches(
                    history = history,
                    onClick = onHistoryClick,
                    onClear = onHistoryClear,
                    onSongLongPress = onSongLongPress,
                    onSongSwipe = onSongSwipe,
                    onBrowseLongPress = onBrowseLongPress,
                )
            }
            results is UiState.Loading -> songListSkeleton(
                circular = filter == SearchFilter.ARTISTS,
                artSize = SearchResultArtSize,
                cornerRadius = SearchSquareArtCorner,
                widescreenArt = filter == SearchFilter.VIDEOS,
            )
            results is UiState.Error -> item { MessageState(results.message) }
            results is UiState.Success -> {
                item(key = "search:results-top-inset") {
                    Spacer(Modifier.height(6.dp))
                }
                val tracks = results.data
                    .mapNotNull { row -> when (row) {
                        is SearchResult.TopTrack -> row.song
                        is SearchResult.Track -> row.song
                        is SearchResult.Browse -> null
                        else -> null
                    } }
                searchSections(results.data).forEach { section ->
                    section.title?.let { title ->
                        item(key = "search-section:$title") {
                            Text(
                                text = title,
                                modifier = Modifier.padding(
                                    start = PAGE_GUTTER,
                                    end = PAGE_GUTTER,
                                    top = 16.dp,
                                    bottom = 6.dp,
                                ),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    itemsIndexed(
                        items = section.rows,
                        key = { index, row ->
                            when (row) {
                                is SearchResult.TopTrack -> "top_${row.song.videoId}_$index"
                                is SearchResult.Track -> "track_${row.song.videoId}_$index"
                                is SearchResult.Browse -> "browse_${row.item.browseId}_$index"
                            }
                        },
                    ) { index, row ->
                        when (row) {
                            is SearchResult.TopTrack,
                            is SearchResult.Track -> {
                                val song = row.searchSong()!!
                                SongRow(
                                    song = song,
                                    modifier = Modifier.padding(vertical = 6.dp),
                                    artworkSize = SearchResultArtSize,
                                    artworkCornerRadius = SearchSquareArtCorner,
                                    widescreenArtwork = filter == SearchFilter.VIDEOS || song.isVideo,
                                    onClick = {
                                        onSongClick(tracks, tracks.indexOf(song).coerceAtLeast(0))
                                    },
                                    onLongPress = { onSongLongPress(song) },
                                    onSwipeToQueue = { onSongSwipe(song) },
                                    showDuration = false,
                                    subtitle = buildSearchSubtitle(
                                        typeLabel = song.shelfTypeLabel(),
                                        detail = song.searchArtistNames(),
                                    ),
                                )
                            }
                            is SearchResult.Browse -> BrowseRow(
                                item = row.item,
                                onClick = { onBrowseClick(row.item) },
                                onLongPress = onBrowseLongPress?.let { { it(row.item) } },
                            )
                        }
                        if (index < section.rows.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = PAGE_GUTTER),
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                    }
                }
                if (loadingMore) {
                    songListSkeleton(
                        count = 3,
                        keyPrefix = "skeleton:search:more",
                        circular = filter == SearchFilter.ARTISTS,
                        artSize = SearchResultArtSize,
                        cornerRadius = SearchSquareArtCorner,
                        widescreenArt = filter == SearchFilter.VIDEOS,
                    )
                }
                item(key = "search:results-bottom-inset") {
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }

    if (!embedChrome) {
        // List fills the screen under the floating logo / account / glass
        // search field drawn by MainActivity — same idea as Home under Liquid Glass.
        LazyColumn(
            state = listState,
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = searchFilterChromeTopPadding() + externalChromeHeight,
                bottom = contentPadding.calculateBottomPadding(),
            ),
        ) {
            searchBody()
        }
    } else {
        Column(modifier = modifier.fillMaxSize()) {
            // Search field and filter tabs stay fixed at the top, outside the
            // scrolling list, so they're always reachable rather than scrolling
            // away with the results or recent searches beneath them.
            // Clear the top bar — status bar inset + bar height + breathing gap —
            // so the search field sits below the buttons instead of overlapping them.
            EmbeddedSearchChrome(modifier = Modifier.padding(top = topBarContentPadding()))
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()),
            ) {
                searchBody()
            }
        }
    }
}

private data class SearchSection(val title: String?, val rows: List<SearchResult>)

private fun SearchResult.searchSong(): Song? = when (this) {
    is SearchResult.TopTrack -> song
    is SearchResult.Track -> song
    is SearchResult.Browse -> null
}

/** Top results keeps YouTube's mixed order — each row already says what it is. */
private fun searchSections(rows: List<SearchResult>): List<SearchSection> {
    return listOf(SearchSection(null, rows))
}

/**
 * What YouTube would complete the half-typed query to, in place of the results
 * while it is being typed.
 *
 * The first row is the text as typed, put there by the view model rather than
 * taken from YouTube's answer, so running exactly what was asked for is always
 * the nearest row to the keyboard rather than something the thumb has to aim
 * past.
 */
private fun LazyListScope.searchSuggestions(
    suggestions: List<String>,
    onClick: (String) -> Unit,
    onFill: (String) -> Unit,
) {
    // This is a list-level inset rather than padding hidden inside the first
    // row. It keeps the gap under the field stable even when that row changes
    // its text or icon treatment.
    item(key = "suggestions:top-inset") { Spacer(Modifier.height(8.dp)) }
    // Skip the echo of the typed text (element 0) — it's already visible in
    // the search field itself — and cap at N so the list stays compact above
    // the playable-media cards.
    itemsIndexed(suggestions.drop(1).take(3), key = { _, term -> "suggest:$term" }) { _, term ->
        SuggestionRow(
            term = term,
            isQueryAction = false,
            onFill = { onFill(term) },
            onClick = { onClick(term) },
        )
    }
}

/**
 * One typeahead row: tap the text to search it, or the arrow to put it in the
 * field and carry on typing — the pair YouTube, Google and every mobile
 * keyboard's own suggestion strip use, and the reason a longer completion
 * isn't a dead end when it's only nearly right.
 */
@Composable
private fun SuggestionRow(
    term: String,
    isQueryAction: Boolean,
    onFill: (() -> Unit)?,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(
                start = PAGE_GUTTER,
                end = 8.dp,
                top = SearchResultRowPaddingV,
                bottom = SearchResultRowPaddingV,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(16.dp))
        SearchResultTitle(
            text = if (isQueryAction) """${stringResource(R.string.search)} "$term"""" else term,
            modifier = Modifier.weight(1f),
        )
        if (onFill != null) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onFill),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.NorthWest,
                    contentDescription = stringResource(R.string.recent_search_edit, term),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        } else {
            // Match the arrow button's full touch target, not only its width.
            // A width-only spacer made the first row shorter than the ones
            // below, so its vertical rhythm looked visibly uneven.
            Spacer(Modifier.size(40.dp))
        }
    }
}

/**
 * Hybrid dropdown shown beneath text suggestions while typing: a horizontal
 * divider, then live media rows (cover art + title + subtitle) that play on
 * tap and open the song menu on long-press.
 */
private fun LazyListScope.searchTypeaheadDropdown(
    typeaheadResults: List<SearchResult>,
    onSongClick: (Song) -> Unit,
    onSongLongPress: ((Song) -> Unit)?,
    onBrowseClick: (BrowseItem) -> Unit,
) {
    item(key = "typeahead:divider") {
        HorizontalDivider(
            modifier = Modifier.padding(start = PAGE_GUTTER, end = PAGE_GUTTER),
            thickness = 0.5.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
        )
    }
    items(typeaheadResults, key = { result ->
        when (result) {
            is SearchResult.Track -> "ta:track:${result.song.videoId}"
            is SearchResult.Browse -> "ta:browse:${result.item.browseId}"
            is SearchResult.TopTrack -> "ta:top:${result.song.videoId}"
        }
    }) { result ->
        when (result) {
            is SearchResult.Track -> TypeaheadSongRow(
                song = result.song,
                onClick = { onSongClick(result.song) },
                onLongPress = onSongLongPress?.let { { it(result.song) } },
            )
            is SearchResult.Browse -> BrowseRow(
                item = result.item,
                onClick = { onBrowseClick(result.item) },
                onLongPress = onSongLongPress?.let { { /* browse long-press not applicable */ } },
            )
            is SearchResult.TopTrack -> TypeaheadSongRow(
                song = result.song,
                onClick = { onSongClick(result.song) },
                onLongPress = onSongLongPress?.let { { it(result.song) } },
            )
        }
    }
}

/**
 * A single media row inside the typeahead dropdown: 52dp cover art, title,
 * and artist/album subtitle. Tap plays the track; long-press opens the menu.
 */
@Composable
private fun TypeaheadSongRow(
    song: Song,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
            .padding(horizontal = PAGE_GUTTER, vertical = SearchResultRowPaddingV),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val widescreenArt = song.isVideo
        val artModifier = if (widescreenArt) {
            Modifier.height(SearchResultArtSize).aspectRatio(16f / 9f)
        } else {
            Modifier.size(SearchResultArtSize)
        }
        AsyncImage(
            model = song.artworkAt(ROW_ART_PX),
            contentDescription = null,
            contentScale = if (widescreenArt) ContentScale.Crop else ContentScale.Fit,
            modifier = artModifier
                .clip(searchSquareArtShape())
                .thumbnailBorder(searchSquareArtShape())
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            SearchResultTitle(text = song.title)
            Spacer(Modifier.height(2.dp))
            SearchEntitySubtitle(
                typeLabel = song.shelfTypeLabel(),
                detail = song.searchArtistNames(),
            )
        }
    }
}

/**
 * What was searched for before, shown in place of the results while the field
 * is empty — the same spot Spotify and Apple Music put it, and the reason the
 * blank search page isn't just a sentence any more.
 */
private fun LazyListScope.recentSearches(
    history: List<SearchHistoryEntity>,
    onClick: (SearchHistoryEntity) -> Unit,
    onClear: () -> Unit,
    onSongLongPress: (Song) -> Unit,
    onSongSwipe: (Song) -> Unit,
    onBrowseLongPress: ((BrowseItem) -> Unit)?,
) {
    item(key = "recent:header") {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PAGE_GUTTER, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.recent_searches),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.clear),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(percent = 50))
                        .clickable(onClick = onClear)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = PAGE_GUTTER),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
    itemsIndexed(
        items = history,
        key = { _, entity -> "recent:${entity.id}" },
    ) { index, entity ->
        when (entity.entityType) {
            EntityType.TRACK, EntityType.MUSIC_VIDEO -> {
                val song = entity.toRecentSong()
                SongRow(
                    song = song,
                    modifier = Modifier.padding(vertical = 6.dp),
                    artworkSize = SearchResultArtSize,
                    artworkCornerRadius = SearchSquareArtCorner,
                    widescreenArtwork = entity.entityType == EntityType.MUSIC_VIDEO,
                    onClick = { onClick(entity) },
                    onLongPress = { onSongLongPress(song) },
                    onSwipeToQueue = { onSongSwipe(song) },
                    showDuration = false,
                    subtitle = buildSearchSubtitle(
                        typeLabel = song.shelfTypeLabel(),
                        detail = entity.subtitle,
                    ),
                )
            }
            EntityType.ALBUM, EntityType.ARTIST, EntityType.PLAYLIST -> {
                val item = entity.toBrowseItem()
                BrowseRow(
                    item = item,
                    onClick = { onClick(entity) },
                    onLongPress = onBrowseLongPress?.let { { it(item) } },
                )
            }
        }
        if (index < history.lastIndex) {
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = PAGE_GUTTER),
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

private fun SearchHistoryEntity.toRecentSong(): Song = Song(
    videoId = id,
    title = title,
    artist = subtitle,
    thumbnailUrl = artworkUrl,
    isVideo = entityType == EntityType.MUSIC_VIDEO,
)

private fun SearchHistoryEntity.toBrowseItem(): BrowseItem {
    val browseType = entityType.toBrowseType()
    return BrowseItem(
        browseId = id,
        title = title,
        subtitle = subtitle,
        thumbnailUrl = artworkUrl,
        type = browseType,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BrowseRow(item: BrowseItem, onClick: () -> Unit, onLongPress: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
            .padding(horizontal = PAGE_GUTTER, vertical = SearchResultRowPaddingV),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = item.thumbnailUrl.artworkAt(ROW_ART_PX),
            contentDescription = null,
            modifier = Modifier
                .size(SearchResultArtSize)
                .clip(
                    if (item.type == BrowseType.ARTIST) CircleShape
                    else searchSquareArtShape(),
                )
                .thumbnailBorder(
                    if (item.type == BrowseType.ARTIST) CircleShape
                    else searchSquareArtShape(),
                )
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            SearchResultTitle(text = item.title)
            Spacer(Modifier.height(2.dp))
            SearchEntitySubtitle(
                typeLabel = browseSearchTypeLabel(item.type),
                detail = browseSearchDetail(item.subtitle, item.type),
            )
        }
    }
}

/** Same bullet home cards and YouTube rows use between kind and credit. */
private const val SEARCH_ENTITY_BULLET = " • "

/** Plain string for [SongRow] subtitles — same words as [SearchEntitySubtitle]. */
private fun buildSearchSubtitle(typeLabel: String, detail: String): String {
    val trimmed = detail.trim()
    return if (trimmed.isEmpty()) typeLabel else typeLabel + SEARCH_ENTITY_BULLET + trimmed
}

/**
 * YouTube already prefixes browse subtitles with "Album • …" — drop the kind
 * so we do not paint "Album • Album • …".
 */
private fun browseSearchDetail(subtitle: String, type: BrowseType): String {
    if (subtitle.isBlank()) return ""
    return subtitle
        .split(Regex("""[•·｜|]"""))
        .map { it.trim() }
        .map { segment ->
            if (type == BrowseType.ALBUM) stripAlbumYearFromSegment(segment) else segment
        }
        .filter { segment ->
            segment.isNotEmpty() && !shouldHideBrowseSubtitleSegment(segment, type)
        }
        .joinToString(", ")
}

private fun stripAlbumYearFromSegment(segment: String): String {
    if (ALBUM_YEAR_SEGMENT.matches(segment.trim())) return ""
    return segment
        .replace(Regex("""\s*[•·]\s*\d{4}\s*$"""), "")
        .replace(Regex("""\s+\d{4}\s*$"""), "")
        .trim()
}

private fun shouldHideBrowseSubtitleSegment(segment: String, type: BrowseType): Boolean {
    val lower = segment.lowercase(Locale.ROOT)
    if (lower in SEARCH_KIND_WORDS) return true
    return when (type) {
        BrowseType.ARTIST -> isArtistMetricsSegment(lower)
        BrowseType.PLAYLIST -> isPlaylistMetricsSegment(lower)
        BrowseType.ALBUM -> ALBUM_YEAR_SEGMENT.matches(segment.trim())
        else -> false
    }
}

private fun isArtistMetricsSegment(lower: String): Boolean {
    if (ARTIST_STAT_SEGMENT.matches(lower)) return true
    if (ARTIST_METRIC_PHRASES.any { lower.contains(it) }) return true
    return ARTIST_LOOSE_COUNT.matches(lower.trim())
}

private val ARTIST_LOOSE_COUNT = Regex("""^[\d.,]+\s*[KMB]?$""", RegexOption.IGNORE_CASE)

private fun isPlaylistMetricsSegment(lower: String): Boolean {
    if (PLAYLIST_VIEW_SEGMENT.matches(lower)) return true
    return PLAYLIST_METRIC_PHRASES.any { lower.contains(it) }
}

private fun EntityType.toBrowseType(): BrowseType = when (this) {
    EntityType.ARTIST -> BrowseType.ARTIST
    EntityType.ALBUM -> BrowseType.ALBUM
    EntityType.PLAYLIST -> BrowseType.PLAYLIST
    EntityType.TRACK, EntityType.MUSIC_VIDEO -> BrowseType.OTHER
}

private val SEARCH_KIND_WORDS = setOf(
    "album", "single", "ep", "playlist", "artist", "podcast", "episode", "song", "video",
)

private val ARTIST_STAT_SEGMENT = Regex(
    """^[\d.,]+\s*[KMB]?\s*(subscribers?|monthly\s+(audience|listeners?)|listeners?)\b.*$""",
    RegexOption.IGNORE_CASE,
)

private val ARTIST_METRIC_PHRASES = listOf(
    "monthly audience",
    "monthly listener",
    "monthly listeners",
    "subscribers",
    "subscriber",
)

private val PLAYLIST_VIEW_SEGMENT = Regex(
    """^[\d.,]+\s*[KMB]?\s*(views?|plays?)\b.*$""",
    RegexOption.IGNORE_CASE,
)

private val PLAYLIST_METRIC_PHRASES = listOf(
    " views",
    " view",
    " plays",
    " play",
)

private val ALBUM_YEAR_SEGMENT = Regex("""^\d{4}$""")

@Composable
private fun SearchEntitySubtitle(
    typeLabel: String,
    detail: String,
    modifier: Modifier = Modifier,
) {
    val style = MaterialTheme.typography.bodyMedium
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        text = buildSearchSubtitle(typeLabel, detail),
        style = style,
        color = color,
        modifier = modifier,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

private fun Song.searchArtistNames(): String {
    val fromCredits = artists.map { it.name }.filter { it.isNotBlank() }.distinct().joinToString(", ")
    return fromCredits.ifBlank { artist.trim() }
}

@Composable
private fun browseSearchTypeLabel(type: BrowseType): String = when (type) {
    BrowseType.ALBUM -> stringResource(R.string.album)
    BrowseType.ARTIST -> stringResource(R.string.artist)
    BrowseType.PLAYLIST -> stringResource(R.string.playlist)
    BrowseType.OTHER -> type.name.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }
}.replaceFirstChar { it.titlecase(Locale.ROOT) }

@Composable
private fun searchEntityTypeLabel(type: EntityType): String = when (type) {
    EntityType.TRACK -> stringResource(R.string.shelf_song_item)
    EntityType.MUSIC_VIDEO -> stringResource(R.string.shelf_music_video_item)
    EntityType.ALBUM -> stringResource(R.string.album)
    EntityType.ARTIST -> stringResource(R.string.artist)
    EntityType.PLAYLIST -> stringResource(R.string.playlist)
}.replaceFirstChar { it.titlecase(Locale.ROOT) }

/**
 * Filter pills rather than a tab row: squarish rounded rectangles, the selected
 * one inverted. They scroll horizontally so a long label set never squeezes the
 * text, and the gutter padding sits inside the scroll so it scrolls with them.
 */
@Composable
private fun SearchFilterTabs(
    filter: SearchFilter,
    onFilterChange: (SearchFilter) -> Unit,
    hazeState: HazeState?,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    val chipShape = remember { RoundedCornerShape(percent = 50) }
    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = PAGE_GUTTER, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SEARCH_FILTER_CHIP_ORDER.forEach { entry ->
            val selected = entry == filter
            val scheme = MaterialTheme.colorScheme
            val fillModifier = when {
                selected -> Modifier.background(scheme.onBackground, chipShape)
                hazeState != null -> Modifier.frostedSurface(shape = chipShape, hazeState = hazeState)
                else -> Modifier.background(scheme.surfaceVariant, chipShape)
            }
            Box(
                modifier = Modifier
                    .then(fillModifier)
                    .clickable {
                        if (!selected) haptics.play(Haptic.Select)
                        onFilterChange(entry)
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = entry.label,
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                    color = if (selected) scheme.background else scheme.onBackground,
                    maxLines = 1,
                )
            }
        }
    }
}
