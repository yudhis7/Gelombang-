package com.yudhis7.gelombang.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yudhis7.gelombang.R
import com.yudhis7.gelombang.ui.AppViewModel
import com.yudhis7.gelombang.ui.Dest
import com.yudhis7.gelombang.ui.Ic
import com.yudhis7.gelombang.ui.MenuAction
import com.yudhis7.gelombang.ui.MessageState
import com.yudhis7.gelombang.ui.StationRow
import com.yudhis7.gelombang.ui.stationMenu
import com.yudhis7.gelombang.ui.theme.Palette

@Composable
fun LibraryScreen(vm: AppViewModel) {
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val recents by vm.recents.collectAsStateWithLifecycle()
    val custom by vm.custom.collectAsStateWithLifecycle()
    val player by vm.player.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Library", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = { vm.open(Dest.Settings) }) { Ic(R.drawable.ic_settings, "Settings") }
            }
            SecondaryTabRow(selectedTabIndex = tab, containerColor = Palette.Bg, contentColor = Palette.Accent) {
                listOf("Favourites", "Recent", "My stations").forEachIndexed { i, label ->
                    Tab(
                        selected = tab == i,
                        onClick = { tab = i },
                        text = { Text(label, color = if (tab == i) Palette.Text else Palette.Muted) },
                    )
                }
            }
            val list = when (tab) {
                0 -> favorites
                1 -> recents
                else -> custom
            }
            if (list.isEmpty()) {
                when (tab) {
                    0 -> MessageState(R.drawable.ic_star, "No favourites yet", "Tap the star on any station to keep it here. Favourites also show up in Android Auto.")
                    1 -> MessageState(R.drawable.ic_clock, "Nothing played yet", "Stations you listen to will show up here.")
                    else -> MessageState(
                        R.drawable.ic_radio,
                        "Add your own streams",
                        "Have a stream link that isn't in the directory? Add it here and it plays like any other station.",
                        "Add station",
                    ) { vm.addingStation = true }
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 96.dp)) {
                    if (tab == 1) {
                        item {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Spacer(Modifier.weight(1f))
                                TextButton(onClick = { vm.clearRecents() }) { Text("Clear history", color = Palette.Muted) }
                            }
                        }
                    }
                    items(list, key = { it.uuid }) { st ->
                        val extra = if (tab == 1) listOf(MenuAction("Remove from history", R.drawable.ic_delete) { vm.removeRecent(st) }) else emptyList()
                        StationRow(
                            station = st,
                            isCurrent = player.station?.uuid == st.uuid,
                            isPlaying = player.isPlaying,
                            isFavorite = favorites.any { it.uuid == st.uuid },
                            showCountry = true,
                            onClick = { vm.play(st, list) },
                            onFavorite = { vm.toggleFavorite(st) },
                            menu = stationMenu(vm, st, extra),
                        )
                    }
                }
            }
        }
        if (tab == 2 || (tab == 0 && favorites.isNotEmpty())) {
            ExtendedFloatingActionButton(
                onClick = { vm.addingStation = true },
                icon = { Ic(R.drawable.ic_add, null) },
                text = { Text("Add station") },
                containerColor = Palette.Accent,
                contentColor = Palette.AccentInk,
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            )
        }
    }
}
