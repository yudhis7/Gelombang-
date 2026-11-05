package com.yudhis7.gelombang.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yudhis7.gelombang.R
import com.yudhis7.gelombang.data.AppGraph
import com.yudhis7.gelombang.data.Regions
import com.yudhis7.gelombang.ui.AppViewModel
import com.yudhis7.gelombang.ui.CodeChip
import com.yudhis7.gelombang.ui.Dest
import com.yudhis7.gelombang.ui.Ic
import com.yudhis7.gelombang.ui.theme.Palette
import java.text.NumberFormat

/** Regions and countries. On wide tablets the selected country's stations show alongside. */
@Composable
fun ExploreScreen(vm: AppViewModel, twoPane: Boolean) {
    if (!twoPane) {
        CountryDirectory(vm, selected = null) { code -> vm.open(Dest.Country(code)) }
        return
    }
    var selected by rememberSaveable { mutableStateOf("ID") }
    Row(Modifier.fillMaxSize()) {
        Box(Modifier.width(320.dp).fillMaxHeight()) {
            CountryDirectory(vm, selected) { selected = it }
        }
        VerticalDivider(color = Palette.Surface2)
        Box(Modifier.weight(1f).fillMaxHeight()) {
            CountryScreen(vm, selected, onBack = null)
        }
    }
}

@Composable
private fun CountryDirectory(vm: AppViewModel, selected: String?, onCountry: (String) -> Unit) {
    val counts by vm.counts.collectAsStateWithLifecycle()
    val nf = remember { NumberFormat.getIntegerInstance() }
    LazyColumn(contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 24.dp)) {
        item {
            Text("Explore", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 8.dp, bottom = 8.dp))
            NavRow(R.drawable.ic_trend, "Trending in APAC", null, active = false) { vm.open(Dest.Trending) }
        }
        AppGraph.regions.groups.forEach { g ->
            item(key = "g-" + g.id) {
                val total = counts?.let { c -> g.countries.sumOf { c[it.code] ?: 0 } }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { vm.open(Dest.Group(g.id)) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(g.name, style = MaterialTheme.typography.titleSmall, color = Palette.Text2, modifier = Modifier.weight(1f))
                    if (total != null) Text(nf.format(total), style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
                    Spacer(Modifier.width(4.dp))
                    Ic(R.drawable.ic_next, null, tint = Palette.Muted, modifier = Modifier.alpha(0.6f))
                }
            }
            items(g.countries, key = { it.code }) { c ->
                val n = counts?.get(c.code)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (selected == c.code) Palette.Surface2 else Palette.Bg)
                        .clickable { onCountry(c.code) }
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .alpha(if (counts != null && (n ?: 0) == 0) 0.45f else 1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CodeChip(c.code, active = selected == c.code)
                    Spacer(Modifier.width(10.dp))
                    Text(c.name, modifier = Modifier.weight(1f), color = if (selected == c.code) Palette.Text else Palette.Text2)
                    Text(
                        when {
                            n != null -> nf.format(n)
                            counts == null -> "…"
                            else -> "0"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.Muted,
                    )
                }
            }
        }
    }
}

@Composable
private fun NavRow(icon: Int, label: String, meta: String?, active: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (active) Palette.Surface2 else Palette.Bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Ic(icon, null, tint = Palette.Accent)
        Spacer(Modifier.width(12.dp))
        Text(label, modifier = Modifier.weight(1f))
        if (meta != null) Text(meta, color = Palette.Muted)
    }
}

@Composable
fun CountryScreen(vm: AppViewModel, code: String, onBack: (() -> Unit)?) {
    val counts by vm.counts.collectAsStateWithLifecycle()
    val country = AppGraph.regions.countries[code]
    val state = rememberStationList(key = "country-$code", initialSort = AppGraph.library.sort, serverSide = true, paged = true) { offset, sort, tag ->
        AppGraph.api.stationsByCountry(code, offset = offset, limit = PAGE_SIZE, sort = sort, tag = tag)
    }
    val meta = listOfNotNull(
        counts?.get(code)?.let { "${NumberFormat.getIntegerInstance().format(it)} stations" },
        country?.tz?.let { "Local time ${Regions.localTime(it)}" },
    ).joinToString(" · ")
    StationListScreen(
        vm = vm,
        state = state,
        title = country?.name ?: code,
        meta = meta,
        showCountry = false,
        onBack = onBack,
    )
    // Remember the chosen sort for next time.
    LaunchedEffect(state.sort) { state.sort?.let { AppGraph.library.sort = it } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GroupScreen(vm: AppViewModel, groupId: String, onBack: () -> Unit) {
    val group = AppGraph.regions.group(groupId) ?: return
    val state = rememberStationList(key = "group-$groupId", initialSort = com.yudhis7.gelombang.data.RadioBrowser.Sort.POPULAR, serverSide = false, paged = false) { _, _, _ ->
        AppGraph.api.topAcross(group.countries.map { it.code }, if (group.countries.size > 4) 15 else 40)
    }
    StationListScreen(
        vm = vm,
        state = state,
        title = group.name,
        meta = "Most popular across ${group.countries.size} countries",
        showCountry = true,
        onBack = onBack,
        header = {
            FlowRow(Modifier.padding(start = 4.dp, end = 8.dp, bottom = 4.dp)) {
                group.countries.forEach { c ->
                    AssistChip(
                        onClick = { vm.open(Dest.Country(c.code)) },
                        label = { Text(c.name) },
                        leadingIcon = { CodeChip(c.code) },
                        modifier = Modifier.padding(end = 6.dp),
                    )
                }
            }
        },
    )
}

@Composable
fun TrendingScreen(vm: AppViewModel, onBack: () -> Unit) {
    val state = rememberStationList(key = "trending", initialSort = com.yudhis7.gelombang.data.RadioBrowser.Sort.POPULAR, serverSide = false, paged = false) { _, _, _ ->
        AppGraph.api.topAcross(AppGraph.trendingCodes(), 12)
    }
    StationListScreen(
        vm = vm,
        state = state,
        title = "Trending in APAC",
        meta = "Most played stations across the region right now",
        showCountry = true,
        onBack = onBack,
    )
}
