package com.yudhis7.gelombang.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yudhis7.gelombang.R
import com.yudhis7.gelombang.playback.placeLine
import com.yudhis7.gelombang.ui.screens.CountryScreen
import com.yudhis7.gelombang.ui.screens.ExploreScreen
import com.yudhis7.gelombang.ui.screens.GroupScreen
import com.yudhis7.gelombang.ui.screens.HomeScreen
import com.yudhis7.gelombang.ui.screens.LibraryScreen
import com.yudhis7.gelombang.ui.screens.NowPlayingScreen
import com.yudhis7.gelombang.ui.screens.SearchScreen
import com.yudhis7.gelombang.ui.screens.SettingsScreen
import com.yudhis7.gelombang.ui.screens.StationEditor
import com.yudhis7.gelombang.ui.screens.TrendingScreen
import com.yudhis7.gelombang.ui.theme.GelombangTheme
import com.yudhis7.gelombang.ui.theme.Palette

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            GelombangTheme { AppRoot(vm) }
        }
    }
}

private data class Tab(val dest: Dest, val label: String, val icon: Int)

private val TABS = listOf(
    Tab(Dest.Home, "Home", R.drawable.ic_home),
    Tab(Dest.Explore, "Explore", R.drawable.ic_globe),
    Tab(Dest.Search, "Search", R.drawable.ic_search),
    Tab(Dest.Library, "Library", R.drawable.ic_library),
)

@Composable
fun AppRoot(vm: AppViewModel) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        vm.messages.collect { snackbar.showSnackbar(it) }
    }
    BackHandler(enabled = vm.showPlayer || vm.stack.size > 1) { vm.back() }

    BoxWithConstraints(Modifier.fillMaxSize().background(Palette.Bg)) {
        val wide = maxWidth >= 600.dp
        val twoPane = maxWidth >= 840.dp
        if (wide) {
            Row(Modifier.fillMaxSize().safeDrawingPadding()) {
                NavigationRail(
                    containerColor = Palette.Panel,
                    header = {
                        Spacer(Modifier.height(8.dp))
                        Box(
                            Modifier.size(40.dp).clip(RoundedCornerShape(11.dp)).background(Palette.Brand),
                            contentAlignment = Alignment.Center,
                        ) {
                            Ic(R.drawable.ic_stat_radio, "Gelombang Radio", tint = Color.White)
                        }
                        Spacer(Modifier.height(12.dp))
                    },
                ) {
                    TABS.forEach { t ->
                        NavigationRailItem(
                            selected = vm.tab == t.dest,
                            onClick = { vm.selectTab(t.dest) },
                            icon = { Ic(t.icon, t.label) },
                            label = { Text(t.label) },
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = Palette.Accent,
                                selectedTextColor = Palette.Text,
                                indicatorColor = Palette.Surface2,
                                unselectedIconColor = Palette.Muted,
                                unselectedTextColor = Palette.Muted,
                            ),
                        )
                    }
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Box(Modifier.weight(1f).fillMaxWidth()) { Content(vm, twoPane) }
                    MiniPlayer(vm)
                }
            }
        } else {
            Scaffold(
                containerColor = Palette.Bg,
                bottomBar = {
                    Column {
                        MiniPlayer(vm)
                        NavigationBar(containerColor = Palette.Panel) {
                            TABS.forEach { t ->
                                NavigationBarItem(
                                    selected = vm.tab == t.dest,
                                    onClick = { vm.selectTab(t.dest) },
                                    icon = { Ic(t.icon, t.label) },
                                    label = { Text(t.label) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = Palette.Accent,
                                        selectedTextColor = Palette.Text,
                                        indicatorColor = Palette.Surface2,
                                        unselectedIconColor = Palette.Muted,
                                        unselectedTextColor = Palette.Muted,
                                    ),
                                )
                            }
                        }
                    }
                },
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) { Content(vm, twoPane = false) }
            }
        }

        AnimatedVisibility(
            visible = vm.showPlayer,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
        ) {
            NowPlayingScreen(vm)
        }

        SnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(bottom = if (wide) 96.dp else 150.dp),
        )
    }

    if (vm.addingStation) StationEditor(vm, null) { vm.addingStation = false }
    vm.editing?.let { st -> StationEditor(vm, st) { vm.editing = null } }
}

@Composable
private fun Content(vm: AppViewModel, twoPane: Boolean) {
    when (val d = vm.current) {
        Dest.Home -> HomeScreen(vm)
        Dest.Explore -> ExploreScreen(vm, twoPane)
        Dest.Search -> SearchScreen(vm)
        Dest.Library -> LibraryScreen(vm)
        Dest.Settings -> SettingsScreen(vm) { vm.back() }
        Dest.Trending -> TrendingScreen(vm) { vm.back() }
        is Dest.Group -> GroupScreen(vm, d.id) { vm.back() }
        is Dest.Country -> CountryScreen(vm, d.code) { vm.back() }
    }
}

@Composable
private fun MiniPlayer(vm: AppViewModel) {
    val ui by vm.player.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val station = ui.station ?: return
    val fav = favorites.any { it.uuid == station.uuid }
    Row(
        Modifier
            .fillMaxWidth()
            .background(Palette.Panel)
            .clickable { vm.showPlayer = true }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StationArt(station, 44.dp, corner = 9.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(station.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (ui.isPlaying) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(Palette.Accent))
                    Spacer(Modifier.width(6.dp))
                }
                val error = ui.error
                val song = ui.song
                Text(
                    when {
                        error != null -> error
                        ui.busy -> "Connecting…"
                        song != null -> song
                        ui.isPlaying -> station.placeLine()
                        else -> "Paused"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (ui.error != null) Palette.Danger else Palette.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = { vm.toggleFavorite(station) }) {
            Ic(
                if (fav) R.drawable.ic_star_filled else R.drawable.ic_star,
                if (fav) "Remove from favourites" else "Add to favourites",
                tint = if (fav) Palette.Warn else Palette.Muted,
            )
        }
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Palette.Text)
                .clickable { vm.toggle() },
            contentAlignment = Alignment.Center,
        ) {
            if (ui.busy) {
                CircularProgressIndicator(Modifier.size(20.dp), color = Palette.Bg, strokeWidth = 2.5.dp)
            } else {
                Ic(if (ui.isPlaying) R.drawable.ic_pause else R.drawable.ic_play, if (ui.isPlaying) "Pause" else "Play", tint = Palette.Bg)
            }
        }
    }
}
