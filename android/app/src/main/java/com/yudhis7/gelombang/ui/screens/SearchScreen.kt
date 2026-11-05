package com.yudhis7.gelombang.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.yudhis7.gelombang.R
import com.yudhis7.gelombang.data.AppGraph
import com.yudhis7.gelombang.ui.AppViewModel
import com.yudhis7.gelombang.ui.Ic
import com.yudhis7.gelombang.ui.MessageState
import com.yudhis7.gelombang.ui.theme.Palette
import kotlinx.coroutines.delay

@Composable
fun SearchScreen(vm: AppViewModel) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val query = vm.searchQuery.trim()
    val scope = vm.searchScope

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = vm.searchQuery,
            onValueChange = { vm.searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 16.dp)
                .focusRequester(focus),
            placeholder = { Text("Stations or genres, e.g. jazz, news, Prambors") },
            leadingIcon = { Ic(R.drawable.ic_search, null, tint = Palette.Muted) },
            trailingIcon = {
                if (vm.searchQuery.isNotEmpty()) {
                    IconButton(onClick = { vm.searchQuery = "" }) { Ic(R.drawable.ic_close, "Clear", tint = Palette.Muted) }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Palette.Accent,
                unfocusedBorderColor = Palette.Surface3,
                focusedContainerColor = Palette.Surface1,
                unfocusedContainerColor = Palette.Surface1,
            ),
        )
        Row(Modifier.padding(start = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (scope != null) {
                InputChip(
                    selected = true,
                    onClick = { vm.searchScope = null },
                    label = { Text("in ${AppGraph.regions.name(scope)}") },
                    trailingIcon = { Ic(R.drawable.ic_close, "Search everywhere", modifier = Modifier.padding(0.dp)) },
                )
            } else {
                Text("Searching all of Asia-Pacific, Australia and New Zealand", style = MaterialTheme.typography.bodySmall, color = Palette.Muted)
            }
        }
        Spacer(Modifier.width(0.dp))

        var debounced by remember { mutableStateOf(query) }
        LaunchedEffect(query) {
            if (query.length >= 2) delay(450)
            debounced = query
        }
        if (debounced.length < 2) {
            MessageState(
                R.drawable.ic_search,
                "Find a station",
                "Search by station name or by genre. Results come from every country in the region.",
            )
        } else {
            val term = debounced
            val state = rememberStationList(key = "search-$term-$scope", initialSort = null, serverSide = false, paged = false) { _, _, _ ->
                AppGraph.api.search(term, scope)
            }
            StationListScreen(
                vm = vm,
                state = state,
                title = "“$term”",
                meta = if (scope != null) "Results in ${AppGraph.regions.name(scope)}" else "Results across the region",
                showCountry = true,
                onBack = null,
                allowNoSort = "Best match",
            )
        }
    }
    LaunchedEffect(Unit) {
        if (vm.searchQuery.isEmpty()) runCatching { focus.requestFocus() }
    }
}
