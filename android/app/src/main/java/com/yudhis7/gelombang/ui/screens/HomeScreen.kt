package com.yudhis7.gelombang.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yudhis7.gelombang.data.AppGraph
import com.yudhis7.gelombang.data.Regions
import com.yudhis7.gelombang.ui.AppViewModel
import com.yudhis7.gelombang.ui.CodeChip
import com.yudhis7.gelombang.ui.Dest
import com.yudhis7.gelombang.ui.SectionHeader
import com.yudhis7.gelombang.ui.StationCard
import com.yudhis7.gelombang.ui.theme.Palette
import kotlinx.coroutines.delay
import java.text.NumberFormat
import java.util.Calendar

private val CLOCKS = listOf(
    "Jakarta" to "Asia/Jakarta",
    "Singapore" to "Asia/Singapore",
    "Tokyo" to "Asia/Tokyo",
    "Sydney" to "Australia/Sydney",
    "Auckland" to "Pacific/Auckland",
    "Mumbai" to "Asia/Kolkata",
)

private fun greeting(): String {
    val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return when {
        h < 5 -> "Late night listening"
        h < 12 -> "Good morning"
        h < 17 -> "Good afternoon"
        else -> "Good evening"
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(vm: AppViewModel) {
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val recents by vm.recents.collectAsStateWithLifecycle()
    val counts by vm.counts.collectAsStateWithLifecycle()
    val player by vm.player.collectAsStateWithLifecycle()
    var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            tick = System.currentTimeMillis()
        }
    }
    val nf = remember { NumberFormat.getIntegerInstance() }
    val total = counts?.values?.sum()

    BoxWithConstraints {
        val cardWidth = if (maxWidth > 700.dp) 168.dp else 140.dp
        val regionColumns = if (maxWidth > 900.dp) 3 else if (maxWidth > 560.dp) 2 else 1
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp)) {
            item {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(
                            Brush.linearGradient(listOf(Palette.Accent.copy(alpha = 0.22f), Palette.Surface1, Palette.Violet.copy(alpha = 0.25f))),
                        )
                        .border(1.dp, Palette.Surface3, RoundedCornerShape(18.dp))
                        .padding(20.dp),
                ) {
                    Text(greeting(), style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        (if (total != null) "${nf.format(total)} live stations" else "Live stations") +
                            " across Asia-Pacific, Australia and New Zealand.",
                        color = Palette.Text2,
                    )
                    Spacer(Modifier.height(14.dp))
                    key(tick) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(CLOCKS) { (city, tz) ->
                            Column(
                                Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0x8C0D0F14))
                                    .padding(horizontal = 12.dp, vertical = 7.dp),
                            ) {
                                Text(Regions.localTime(tz), style = MaterialTheme.typography.titleMedium)
                                Text(city, style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
                            }
                        }
                    }
                    }
                }
            }

            item {
                SectionHeader("Favourites", if (favorites.size > 6) "See all" else null) { vm.selectTab(Dest.Library) }
                if (favorites.isEmpty()) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .border(1.dp, Palette.Border, RoundedCornerShape(14.dp))
                            .padding(18.dp),
                    ) {
                        Text("Tap the star on any station and it lands here, ready to play in one tap.", color = Palette.Muted)
                    }
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(favorites, key = { it.uuid }) { st ->
                            StationCard(st, player.station?.uuid == st.uuid, player.isPlaying, cardWidth) { vm.play(st, favorites) }
                        }
                    }
                }
            }

            if (recents.isNotEmpty()) {
                item {
                    SectionHeader("Recently played", "See all") { vm.selectTab(Dest.Library) }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(recents.take(12), key = { it.uuid }) { st ->
                            StationCard(st, player.station?.uuid == st.uuid, player.isPlaying, cardWidth) { vm.play(st, recents) }
                        }
                    }
                }
            }

            item {
                SectionHeader("Explore the region", "Trending in APAC") { vm.open(Dest.Trending) }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    maxItemsInEachRow = regionColumns,
                ) {
                    AppGraph.regions.groups.forEach { g ->
                        val n = counts?.let { c -> g.countries.sumOf { c[it.code] ?: 0 } }
                        Column(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Palette.Surface1)
                                .border(1.dp, Palette.Surface3, RoundedCornerShape(14.dp))
                                .clickable { vm.open(Dest.Group(g.id)) }
                                .padding(16.dp),
                        ) {
                            Text(g.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                (n?.let { "${nf.format(it)} stations · " } ?: "") + "${g.countries.size} countries",
                                style = MaterialTheme.typography.bodySmall,
                                color = Palette.Muted,
                            )
                            Spacer(Modifier.height(10.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                g.countries.forEach { CodeChip(it.code) }
                            }
                        }
                    }
                }
            }
        }
    }
}
