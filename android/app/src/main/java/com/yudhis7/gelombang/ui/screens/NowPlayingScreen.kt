package com.yudhis7.gelombang.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yudhis7.gelombang.R
import com.yudhis7.gelombang.playback.placeLine
import com.yudhis7.gelombang.ui.AppViewModel
import com.yudhis7.gelombang.ui.Ic
import com.yudhis7.gelombang.ui.PlayerUi
import com.yudhis7.gelombang.ui.StationArt
import com.yudhis7.gelombang.ui.hueGradient
import com.yudhis7.gelombang.ui.theme.Palette
import kotlinx.coroutines.delay

private val SLEEP_OPTIONS = listOf(15, 30, 45, 60, 90, 120)

@Composable
fun NowPlayingScreen(vm: AppViewModel) {
    val ui by vm.player.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val station = ui.station ?: return
    val fav = favorites.any { it.uuid == station.uuid }

    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.Bg)
            .background(
                Brush.verticalGradient(
                    listOf(Color(0x55000000), Palette.Bg),
                ),
            )
            .clickable(enabled = false) {},
    ) {
        Box(Modifier.fillMaxSize().background(hueGradient(station.name)).background(Color(0xE60D0F14)))
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.showPlayer = false }) { Ic(R.drawable.ic_down, "Close") }
                Text(
                    "Now playing",
                    style = MaterialTheme.typography.titleSmall,
                    color = Palette.Text2,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                )
                IconButton(onClick = { vm.stop() }) { Ic(R.drawable.ic_stop, "Stop", tint = Palette.Text2) }
            }
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val landscape = maxWidth > maxHeight && maxWidth >= 560.dp
                if (landscape) {
                    Row(
                        Modifier.fillMaxSize().padding(24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(32.dp),
                    ) {
                        StationArt(station, minOf(maxHeight - 48.dp, maxWidth * 0.42f), corner = 22.dp)
                        Column(Modifier.weight(1f)) { Details(vm, ui, fav) }
                    }
                } else {
                    Column(
                        Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        StationArt(station, minOf(maxWidth - 48.dp, maxHeight * 0.45f, 420.dp), corner = 22.dp)
                        Spacer(Modifier.height(28.dp))
                        Column(Modifier.widthIn(max = 520.dp)) { Details(vm, ui, fav) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Details(vm: AppViewModel, ui: PlayerUi, fav: Boolean) {
    val station = ui.station ?: return
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val sleepEndsAt by vm.sleepEndsAt.collectAsStateWithLifecycle()

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(station.name, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (ui.isPlaying) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(Palette.Accent))
                    Spacer(Modifier.width(6.dp))
                    Text("LIVE", color = Palette.Accent, style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    when {
                        ui.error != null -> ui.error
                        ui.busy -> "Connecting…"
                        ui.song != null -> ui.song
                        else -> listOf(station.placeLine(), station.tech).filter { it.isNotBlank() }.joinToString(" · ")
                    },
                    color = when {
                        ui.error != null -> Palette.Danger
                        ui.song != null -> Palette.Text
                        else -> Palette.Muted
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (ui.song != null) {
                Text(station.placeLine(), color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        IconButton(onClick = { vm.toggleFavorite(station) }) {
            Ic(
                if (fav) R.drawable.ic_star_filled else R.drawable.ic_star,
                if (fav) "Remove from favourites" else "Add to favourites",
                tint = if (fav) Palette.Warn else Palette.Text2,
            )
        }
    }

    Spacer(Modifier.height(28.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        RoundButton(R.drawable.ic_prev, "Previous station", 52.dp, enabled = ui.canSkip) { vm.previous() }
        Box(
            Modifier
                .size(76.dp)
                .clip(CircleShape)
                .background(Palette.Text)
                .clickable { vm.toggle() },
            contentAlignment = Alignment.Center,
        ) {
            if (ui.busy) {
                CircularProgressIndicator(Modifier.size(30.dp), color = Palette.Bg, strokeWidth = 3.dp)
            } else {
                Ic(
                    if (ui.isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                    if (ui.isPlaying) "Pause" else "Play",
                    tint = Palette.Bg,
                    modifier = Modifier.size(34.dp),
                )
            }
        }
        RoundButton(R.drawable.ic_next, "Next station", 52.dp, enabled = ui.canSkip) { vm.next() }
    }

    Spacer(Modifier.height(24.dp))
    HorizontalDivider(color = Palette.Surface2)
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
        SleepButton(vm, sleepEndsAt)
        if (ui.song != null) {
            ActionButton(R.drawable.ic_copy, "Copy song") {
                clipboard.setText(AnnotatedString(ui.song))
                vm.say("Song title copied")
            }
        }
        ActionButton(R.drawable.ic_share, "Share") {
            val text = (ui.song?.let { "$it — " } ?: "") + station.name + (station.homepage.takeIf { it.startsWith("http") }?.let { " $it" } ?: "")
            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
        }
        if (station.homepage.startsWith("http")) {
            ActionButton(R.drawable.ic_open, "Website") { runCatching { uriHandler.openUri(station.homepage) } }
        }
    }
}

@Composable
private fun SleepButton(vm: AppViewModel, endsAt: Long?) {
    var open by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(endsAt) {
        while (endsAt != null) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    Box {
        ActionButton(
            R.drawable.ic_moon,
            if (endsAt != null) formatLeft(endsAt - now) else "Sleep",
            tint = if (endsAt != null) Palette.Accent else Palette.Text2,
        ) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text("Stop playing after", color = Palette.Muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            SLEEP_OPTIONS.forEach { m ->
                DropdownMenuItem(text = { Text(AppViewModel.sleepLabel(m)) }, onClick = {
                    open = false
                    vm.setSleep(m)
                })
            }
            if (endsAt != null) {
                DropdownMenuItem(text = { Text("Turn off sleep timer", color = Palette.Danger) }, onClick = {
                    open = false
                    vm.setSleep(0)
                })
            }
        }
    }
}

private fun formatLeft(ms: Long): String {
    val s = (ms.coerceAtLeast(0) + 999) / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

@Composable
private fun RoundButton(icon: Int, label: String, size: Dp, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Ic(icon, label, tint = if (enabled) Palette.Text else Palette.Surface3, modifier = Modifier.size(28.dp))
    }
}

@Composable
private fun ActionButton(icon: Int, label: String, tint: Color = Palette.Text2, onClick: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Ic(icon, null, tint = tint)
        Spacer(Modifier.height(4.dp))
        Text(label, color = tint, fontSize = 12.sp)
    }
}
