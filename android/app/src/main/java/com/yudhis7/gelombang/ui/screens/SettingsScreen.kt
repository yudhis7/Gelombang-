package com.yudhis7.gelombang.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.yudhis7.gelombang.BuildConfig
import com.yudhis7.gelombang.R
import com.yudhis7.gelombang.ui.AppViewModel
import com.yudhis7.gelombang.ui.Ic
import com.yudhis7.gelombang.ui.theme.Palette

@Composable
fun SettingsScreen(vm: AppViewModel, onBack: () -> Unit) {
    val uri = LocalUriHandler.current
    val exportJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { u ->
        if (u != null) vm.exportFavorites(u, m3u = false)
    }
    val exportM3u = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/x-mpegurl")) { u ->
        if (u != null) vm.exportFavorites(u, m3u = true)
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) vm.importStations(u)
    }

    LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 32.dp)) {
        item { ListHeader("Settings", "Everything is stored only on this device.", onBack, null, null) }

        item { Group("Favourites") }
        item { Action(R.drawable.ic_download, "Back up favourites", "Save a .json file you can restore here or in the desktop app") { exportJson.launch("gelombang-favourites.json") } }
        item { Action(R.drawable.ic_download, "Export as playlist", "Save an .m3u playlist for other players") { exportM3u.launch("gelombang-favourites.m3u") } }
        item { Action(R.drawable.ic_upload, "Import stations", "From a Gelombang backup, .m3u or .pls file") { import.launch(arrayOf("*/*")) } }
        item { Action(R.drawable.ic_add, "Add a station by stream link", "For stations that aren't in the directory") { vm.addingStation = true } }

        item { Group("Android Auto") }
        item {
            Note(
                "Gelombang appears in Android Auto under media apps, with your favourites, recent stations, " +
                    "regions and trending lists, plus voice search.\n\n" +
                    "Because this app is installed from an APK rather than Google Play, Android Auto hides it until you allow it once: " +
                    "open the Android Auto settings on your phone, tap \"Version\" 10 times to unlock developer settings, " +
                    "then in the menu (⋮) choose Developer settings and turn on \"Unknown sources\".",
            )
        }

        item { Group("About") }
        item {
            Note(
                "Gelombang Radio ${BuildConfig.VERSION_NAME}. Station listings come from Radio Browser, a free community directory. " +
                    "Some stations only stream inside their own country, and listings can be out of date.",
            )
        }
        item { Action(R.drawable.ic_open, "Radio Browser", "radio-browser.info") { runCatching { uri.openUri("https://www.radio-browser.info/") } } }
        item { Action(R.drawable.ic_open, "Project on GitHub", "github.com/yudhis7/Gelombang-") { runCatching { uri.openUri("https://github.com/yudhis7/Gelombang-") } } }
    }
}

@Composable
private fun Group(title: String) {
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = Palette.Muted,
        modifier = Modifier.padding(start = 12.dp, top = 20.dp, bottom = 6.dp),
    )
}

@Composable
private fun Action(icon: Int, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Ic(icon, null, tint = Palette.Accent)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        color = Palette.Text2,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Palette.Surface1)
            .padding(14.dp),
    )
}
