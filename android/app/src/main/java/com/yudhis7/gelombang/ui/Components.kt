package com.yudhis7.gelombang.ui

import android.content.Intent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.yudhis7.gelombang.R
import com.yudhis7.gelombang.data.Station
import com.yudhis7.gelombang.playback.ArtworkProvider
import com.yudhis7.gelombang.playback.placeLine
import com.yudhis7.gelombang.ui.theme.Palette

@Composable
fun Ic(res: Int, contentDescription: String?, modifier: Modifier = Modifier, tint: Color = Color.Unspecified) {
    Icon(
        painter = painterResource(res),
        contentDescription = contentDescription,
        modifier = modifier,
        tint = if (tint == Color.Unspecified) androidx.compose.material3.LocalContentColor.current else tint,
    )
}

fun hueGradient(seed: String): Brush {
    val hue = ArtworkProvider.hueFor(seed)
    return Brush.linearGradient(
        listOf(Color.hsv(hue, 0.62f, 0.72f), Color.hsv((hue + 40f) % 360f, 0.6f, 0.46f)),
    )
}

/** Station logo, or coloured initials when there is no usable logo. */
@Composable
fun StationArt(station: Station, size: Dp, corner: Dp = 10.dp, playing: Boolean? = null) {
    var loaded by remember(station.favicon) { mutableStateOf(false) }
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(hueGradient(station.name)),
        contentAlignment = Alignment.Center,
    ) {
        if (!loaded) {
            Text(
                ArtworkProvider.initials(station.name),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.34f).sp,
            )
        }
        if (station.favicon.startsWith("http")) {
            AsyncImage(
                model = station.favicon,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                onSuccess = { loaded = true },
                onError = { loaded = false },
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (loaded) Modifier.background(Color.White).padding(size * 0.06f) else Modifier),
            )
        }
        if (playing != null) {
            Box(Modifier.fillMaxSize().background(Color(0x99060A10)), contentAlignment = Alignment.Center) {
                EqBars(playing, height = size * 0.38f)
            }
        }
    }
}

@Composable
fun EqBars(playing: Boolean, height: Dp = 16.dp, color: Color = Palette.Accent) {
    val t = rememberInfiniteTransition(label = "eq")
    val phases = listOf(0, 220, 440)
    Row(horizontalArrangement = Arrangement.spacedBy(height * 0.18f), verticalAlignment = Alignment.Bottom) {
        phases.forEach { delay ->
            val v by t.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(520, delayMillis = delay), RepeatMode.Reverse),
                label = "bar",
            )
            val scale = if (playing) v else 0.45f
            Box(
                Modifier
                    .width(height * 0.22f)
                    .height(height)
                    .graphicsLayer {
                        scaleY = scale
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
                    }
                    .clip(RoundedCornerShape(2.dp))
                    .background(color),
            )
        }
    }
}

@Composable
fun CodeChip(code: String, active: Boolean = false) {
    Box(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (active) Palette.Accent.copy(alpha = 0.18f) else Palette.Surface3)
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text(
            code,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.4.sp,
            color = if (active) Palette.Accent else Palette.Text2,
        )
    }
}

@Composable
fun StationRow(
    station: Station,
    isCurrent: Boolean,
    isPlaying: Boolean,
    isFavorite: Boolean,
    showCountry: Boolean,
    onClick: () -> Unit,
    onFavorite: () -> Unit,
    menu: List<MenuAction>,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isCurrent) Palette.Surface2 else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(start = 8.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StationArt(station, 48.dp, playing = if (isCurrent) isPlaying else null)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                station.name,
                style = MaterialTheme.typography.titleSmall,
                color = if (isCurrent) Palette.Accent else Palette.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = listOf(
                if (showCountry) station.placeLine() else station.state,
                station.tags.take(3).joinToString(", "),
            ).filter { it.isNotBlank() }.joinToString(" · ")
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (station.countryCode.isNotEmpty()) {
                    CodeChip(station.countryCode)
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    sub.ifBlank { station.tech.ifBlank { if (station.custom) "Your station" else "" } },
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onFavorite) {
            Ic(
                if (isFavorite) R.drawable.ic_star_filled else R.drawable.ic_star,
                if (isFavorite) "Remove from favourites" else "Add to favourites",
                tint = if (isFavorite) Palette.Warn else Palette.Muted,
            )
        }
        OverflowMenu(menu)
    }
}

data class MenuAction(val label: String, val icon: Int, val danger: Boolean = false, val run: () -> Unit)

@Composable
fun OverflowMenu(actions: List<MenuAction>) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Ic(R.drawable.ic_more, "More", tint = Palette.Muted) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            actions.forEach { a ->
                DropdownMenuItem(
                    text = { Text(a.label, color = if (a.danger) Palette.Danger else Palette.Text) },
                    leadingIcon = { Ic(a.icon, null, tint = if (a.danger) Palette.Danger else Palette.Text2) },
                    onClick = {
                        open = false
                        a.run()
                    },
                )
            }
        }
    }
}

/** The standard menu for a station row. */
@Composable
fun stationMenu(
    vm: AppViewModel,
    station: Station,
    extra: List<MenuAction> = emptyList(),
): List<MenuAction> {
    val uri = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val fav = vm.isFavorite(station.uuid)
    return buildList {
        add(MenuAction(if (fav) "Remove from favourites" else "Add to favourites", R.drawable.ic_star) { vm.toggleFavorite(station) })
        if (station.homepage.startsWith("http")) {
            add(MenuAction("Open station website", R.drawable.ic_open) { runCatching { uri.openUri(station.homepage) } })
        }
        add(MenuAction("Copy stream link", R.drawable.ic_copy) {
            clipboard.setText(AnnotatedString(station.url))
            vm.say("Stream link copied")
        })
        add(MenuAction("Share station", R.drawable.ic_share) {
            val text = listOf(station.name, station.homepage.ifBlank { station.url }).joinToString(" — ")
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
            context.startActivity(Intent.createChooser(send, null))
        })
        if (station.custom) {
            add(MenuAction("Edit station", R.drawable.ic_edit) { vm.editing = station })
            add(MenuAction("Delete station", R.drawable.ic_delete, danger = true) { vm.deleteCustom(station) })
        }
        addAll(extra)
    }
}

@Composable
fun StationCard(station: Station, isCurrent: Boolean, isPlaying: Boolean, width: Dp, onClick: () -> Unit) {
    Column(
        Modifier
            .width(width)
            .clip(RoundedCornerShape(14.dp))
            .background(if (isCurrent) Palette.Surface2 else Palette.Surface1)
            .clickable(onClick = onClick)
            .padding(10.dp),
    ) {
        StationArt(station, width - 20.dp, corner = 10.dp, playing = if (isCurrent) isPlaying else null)
        Spacer(Modifier.height(8.dp))
        Text(
            station.name,
            style = MaterialTheme.typography.titleSmall,
            color = if (isCurrent) Palette.Accent else Palette.Text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            station.placeLine(),
            style = MaterialTheme.typography.bodySmall,
            color = Palette.Muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun SectionHeader(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) {
            Text(
                action,
                color = Palette.Accent,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onAction).padding(6.dp),
            )
        }
    }
}

@Composable
fun MessageState(icon: Int, title: String, text: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(Palette.Surface2),
            contentAlignment = Alignment.Center,
        ) { Ic(icon, null, tint = Palette.Accent) }
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(text, color = Palette.Muted, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = onAction, colors = ButtonDefaults.buttonColors(containerColor = Palette.Accent)) {
                Text(actionLabel, color = Palette.AccentInk)
            }
        }
    }
}

@Composable
fun LoadingRows(count: Int = 8) {
    Column(Modifier.fillMaxWidth()) {
        repeat(count) {
            Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(Palette.Surface1))
                Spacer(Modifier.width(12.dp))
                Column {
                    Box(Modifier.width(180.dp).height(12.dp).clip(RoundedCornerShape(4.dp)).background(Palette.Surface2))
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.width(120.dp).height(10.dp).clip(RoundedCornerShape(4.dp)).background(Palette.Surface1))
                }
            }
        }
    }
}

@Composable
fun SmallSpinner() {
    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp, color = Palette.Accent)
    }
}

@Composable
fun Divider() = HorizontalDivider(color = Palette.Surface2)

val ScreenPadding = PaddingValues(horizontal = 16.dp)

fun friendlyError(e: Throwable): String {
    val m = e.message.orEmpty()
    return if (e is java.io.IOException || m.contains("Unable to resolve", true) || m.contains("timeout", true)) {
        "Could not reach the radio directory. Check your internet connection."
    } else {
        m.ifBlank { "Something went wrong." }
    }
}
