package com.yudhis7.gelombang.ui.screens

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yudhis7.gelombang.R
import com.yudhis7.gelombang.data.RadioBrowser.Sort
import com.yudhis7.gelombang.data.Station
import com.yudhis7.gelombang.ui.AppViewModel
import com.yudhis7.gelombang.ui.Ic
import com.yudhis7.gelombang.ui.LoadingRows
import com.yudhis7.gelombang.ui.MessageState
import com.yudhis7.gelombang.ui.SmallSpinner
import com.yudhis7.gelombang.ui.StationRow
import com.yudhis7.gelombang.ui.friendlyError
import com.yudhis7.gelombang.ui.stationMenu
import com.yudhis7.gelombang.ui.theme.Palette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

const val PAGE_SIZE = 100

/** Loads a list of stations, optionally page by page, with sort and genre filter. */
class StationListState(
    private val scope: CoroutineScope,
    initialSort: Sort?,
    private val serverSide: Boolean,
    private val paged: Boolean,
    private val loader: suspend (offset: Int, sort: Sort, tag: String?) -> List<Station>,
) {
    var items by mutableStateOf<List<Station>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var hasMore by mutableStateOf(false)
        private set

    /** null = keep the order the list came in. */
    var sort by mutableStateOf(initialSort)
    var tag by mutableStateOf<String?>(null)

    private var job: Job? = null
    private var generation = 0

    val visible: List<Station>
        get() {
            if (serverSide) return items
            var rows = items
            tag?.let { t -> rows = rows.filter { t in it.tags } }
            sort?.let { s -> rows = rows.sortedWith(s.comparator()) }
            return rows
        }

    fun reload() = load(0)

    fun loadMore() {
        if (!loading && hasMore && error == null) load(items.size)
    }

    fun changeSort(s: Sort?) {
        sort = s
        if (serverSide) reload()
    }

    fun changeTag(t: String?) {
        tag = t
        if (serverSide) reload()
    }

    private fun load(offset: Int) {
        job?.cancel()
        val gen = ++generation
        loading = true
        error = null
        if (offset == 0) items = emptyList()
        job = scope.launch {
            try {
                val rows = loader(offset, sort ?: Sort.POPULAR, if (serverSide) tag else null)
                if (gen != generation) return@launch
                items = if (offset == 0) rows else (items + rows).distinctBy { it.uuid }
                hasMore = paged && rows.size >= PAGE_SIZE
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen == generation) error = friendlyError(e)
            } finally {
                if (gen == generation) loading = false
            }
        }
    }
}

fun topTags(stations: List<Station>, skip: Set<String>, n: Int = 12): List<String> =
    stations.flatMap { it.tags }
        .filter { it.length >= 3 && it !in skip }
        .groupingBy { it }.eachCount()
        .filter { it.value > 1 }
        .entries.sortedByDescending { it.value }
        .take(n)
        .map { it.key }

@Composable
fun rememberStationList(
    key: Any,
    initialSort: Sort?,
    serverSide: Boolean,
    paged: Boolean,
    loader: suspend (offset: Int, sort: Sort, tag: String?) -> List<Station>,
): StationListState {
    val scope = rememberCoroutineScope()
    val state = remember(key) { StationListState(scope, initialSort, serverSide, paged, loader) }
    LaunchedEffect(key) { if (state.items.isEmpty()) state.reload() }
    return state
}

/** A titled, sortable, filterable station list. Used for countries, regions, trending and search. */
@Composable
fun StationListScreen(
    vm: AppViewModel,
    state: StationListState,
    title: String,
    meta: String?,
    showCountry: Boolean,
    onBack: (() -> Unit)?,
    allowNoSort: String? = null,
    header: (@Composable () -> Unit)? = null,
    emptyText: String = "Try a different name or genre.",
) {
    val player by vm.player.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val rows = state.visible
    val tags = remember(state.items) {
        topTags(state.items, com.yudhis7.gelombang.data.AppGraph.regions.countries.values.map { it.name.lowercase() }.toSet())
    }
    val nearEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 8
        }
    }
    LaunchedEffect(nearEnd, state.items.size) { if (nearEnd) state.loadMore() }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 24.dp),
    ) {
        item {
            ListHeader(title, meta, onBack, state, allowNoSort)
        }
        header?.let { h -> item { h() } }
        if (tags.size > 2 || state.tag != null) {
            item {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(start = 4.dp, end = 12.dp),
                    modifier = Modifier.padding(bottom = 8.dp),
                ) {
                    item {
                        GenreChip("All", state.tag == null) { state.changeTag(null) }
                    }
                    val shown = if (state.tag != null && state.tag !in tags) listOf(state.tag!!) + tags else tags
                    items(shown) { t -> GenreChip(t, state.tag == t) { state.changeTag(if (state.tag == t) null else t) } }
                }
            }
        }
        when {
            state.loading && state.items.isEmpty() -> item { LoadingRows() }
            state.error != null && state.items.isEmpty() -> item {
                MessageState(R.drawable.ic_globe, "Directory unavailable", state.error!!, "Try again") { state.reload() }
            }
            rows.isEmpty() -> item { MessageState(R.drawable.ic_search, "No stations found", emptyText) }
            else -> {
                items(rows, key = { it.uuid }) { st ->
                    StationRow(
                        station = st,
                        isCurrent = player.station?.uuid == st.uuid,
                        isPlaying = player.isPlaying,
                        isFavorite = favorites.any { it.uuid == st.uuid },
                        showCountry = showCountry,
                        onClick = { vm.play(st, rows) },
                        onFavorite = { vm.toggleFavorite(st) },
                        menu = stationMenu(vm, st),
                    )
                }
                if (state.loading) item { SmallSpinner() }
                if (state.error != null) item {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        TextButton(onClick = { state.loadMore() }) { Text("Couldn't load more. Tap to retry.") }
                    }
                }
            }
        }
    }
}

@Composable
private fun GenreChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = Palette.Accent.copy(alpha = 0.16f),
            selectedLabelColor = Palette.Accent,
            labelColor = Palette.Text2,
        ),
    )
}

@Composable
fun ListHeader(title: String, meta: String?, onBack: (() -> Unit)?, state: StationListState?, allowNoSort: String?) {
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            IconButton(onClick = onBack) { Ic(R.drawable.ic_back, "Back") }
        } else {
            Spacer(Modifier.width(4.dp))
        }
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!meta.isNullOrBlank()) Text(meta, style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
        }
        if (state != null) SortMenu(state, allowNoSort)
    }
}

@Composable
private fun SortMenu(state: StationListState, allowNoSort: String?) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) {
            Text(state.sort?.label ?: allowNoSort ?: "Sort", color = Palette.Text2)
            Spacer(Modifier.width(4.dp))
            Ic(R.drawable.ic_down, null, tint = Palette.Muted)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val options = buildList<Pair<String, Sort?>> {
                if (allowNoSort != null) add(allowNoSort to null)
                Sort.entries.forEach { add(it.label to it) }
            }
            options.forEach { (label, s) ->
                DropdownMenuItem(
                    text = { Text(label, color = if (state.sort == s) Palette.Accent else Palette.Text) },
                    onClick = {
                        open = false
                        state.changeSort(s)
                    },
                )
            }
        }
    }
}

@Composable
fun Spacing(h: Int) = Spacer(Modifier.height(h.dp))
