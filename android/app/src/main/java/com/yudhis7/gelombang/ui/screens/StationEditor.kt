package com.yudhis7.gelombang.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.yudhis7.gelombang.data.AppGraph
import com.yudhis7.gelombang.data.Station
import com.yudhis7.gelombang.ui.AppViewModel
import com.yudhis7.gelombang.ui.theme.Palette

/** Add or edit a station by its direct stream link. */
@Composable
fun StationEditor(vm: AppViewModel, existing: Station?, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var url by remember { mutableStateOf(existing?.url.orEmpty()) }
    var homepage by remember { mutableStateOf(existing?.homepage.orEmpty()) }
    var tags by remember { mutableStateOf(existing?.tags?.joinToString(", ").orEmpty()) }
    var code by remember { mutableStateOf(existing?.countryCode.orEmpty()) }
    var pickCountry by remember { mutableStateOf(false) }
    var urlError by remember { mutableStateOf<String?>(null) }

    fun build(): Station? {
        val u = url.trim()
        if (!(u.startsWith("http://", true) || u.startsWith("https://", true)) || u.contains(' ')) {
            urlError = "Enter a link that starts with http:// or https://"
            return null
        }
        return Station(
            uuid = existing?.uuid ?: Station.newCustomId(),
            name = name.trim().ifBlank { u },
            url = u,
            homepage = homepage.trim(),
            favicon = existing?.favicon.orEmpty(),
            tags = tags.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.take(8),
            country = if (code.isNotEmpty()) AppGraph.regions.name(code) else "",
            countryCode = code,
            hls = Station.isHlsUrl(u),
            custom = true,
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.Surface1,
        title = { Text(if (existing == null) "Add a station" else "Edit station") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "Paste a direct stream link (MP3, AAC, Ogg or HLS .m3u8). It's saved to your favourites.",
                    color = Palette.Muted,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(name, { name = it }, label = { Text("Station name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                val err = urlError
                OutlinedTextField(
                    url,
                    {
                        url = it
                        urlError = null
                    },
                    label = { Text("Stream link") },
                    singleLine = true,
                    isError = urlError != null,
                    supportingText = if (err != null) {
                        { Text(err) }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Box {
                    OutlinedButton(onClick = { pickCountry = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (code.isEmpty()) "Country: not set" else "Country: ${AppGraph.regions.name(code)}", color = Palette.Text)
                    }
                    DropdownMenu(expanded = pickCountry, onDismissRequest = { pickCountry = false }, modifier = Modifier.heightIn(max = 360.dp)) {
                        DropdownMenuItem(text = { Text("Not set") }, onClick = { code = ""; pickCountry = false })
                        AppGraph.regions.groups.forEach { g ->
                            g.countries.forEach { c ->
                                DropdownMenuItem(text = { Text(c.name) }, onClick = { code = c.code; pickCountry = false })
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(tags, { tags = it }, label = { Text("Genres (optional)") }, placeholder = { Text("news, pop") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    homepage, { homepage = it },
                    label = { Text("Website (optional)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                build()?.let {
                    vm.saveCustom(it)
                    onDismiss()
                }
            }) { Text(if (existing == null) "Add" else "Save", color = Palette.Accent) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { build()?.let { vm.play(it) } }) { Text("Test", color = Palette.Text2) }
                TextButton(onClick = onDismiss) { Text("Cancel", color = Palette.Text2) }
            }
        },
    )
}
