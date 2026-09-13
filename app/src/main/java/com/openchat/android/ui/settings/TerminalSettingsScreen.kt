package com.openchat.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.openchat.android.AppGraph
import com.openchat.android.core.storage.TerminalSettings
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Terminal settings (spec §27): font size/family, cursor blink, scrollback
 * depth, color theme, extra keys row and keep-screen-on. Every change is
 * persisted immediately through SettingsStore.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalSettingsScreen(nav: NavHostController) {
    val settings by AppGraph.settings.settings.collectAsState()
    val ts: TerminalSettings = settings.terminal

    var scrollbackText by remember(ts.scrollback) { mutableStateOf(ts.scrollback.toString()) }
    var themeMenuOpen by remember { mutableStateOf(false) }
    var fontMenuOpen by remember { mutableStateOf(false) }

    fun updateTerminal(transform: (TerminalSettings) -> TerminalSettings) {
        AppGraph.settings.update { it.copy(terminal = transform(it.terminal)) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Terminal") },
                navigationIcon = { IconButtonBack { nav.popBackStack() } },
            )
        },
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Font size: " + String.format(Locale.US, "%.1f sp", ts.fontSizeSp),
                style = MaterialTheme.typography.bodyLarge,
            )
            Slider(
                value = ts.fontSizeSp,
                onValueChange = { v ->
                    updateTerminal { it.copy(fontSizeSp = (v * 10).roundToInt() / 10f) }
                },
                valueRange = 8f..20f,
            )

            SwitchRow("Cursor blink", ts.cursorBlink) { v ->
                updateTerminal { it.copy(cursorBlink = v) }
            }

            OutlinedTextField(
                value = scrollbackText,
                onValueChange = { v ->
                    scrollbackText = v
                    v.toIntOrNull()?.let { n -> if (n in 0..100_000) updateTerminal { it.copy(scrollback = n) } }
                },
                label = { Text("Scrollback lines") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                supportingText = { Text("How much output history is kept (applies to new sessions)") },
                modifier = Modifier.fillMaxWidth(),
            )

            ExposedDropdownMenuBox(
                expanded = themeMenuOpen,
                onExpandedChange = { themeMenuOpen = it },
            ) {
                OutlinedTextField(
                    value = ts.theme,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Color theme") },
                    trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth(),
                )
                ExposedDropdownMenu(expanded = themeMenuOpen, onDismissRequest = { themeMenuOpen = false }) {
                    listOf("dark", "light").forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = {
                                themeMenuOpen = false
                                updateTerminal { it.copy(theme = option) }
                            },
                        )
                    }
                }
            }

            ExposedDropdownMenuBox(
                expanded = fontMenuOpen,
                onExpandedChange = { fontMenuOpen = it },
            ) {
                OutlinedTextField(
                    value = ts.fontFamily,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Font family") },
                    trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth(),
                )
                ExposedDropdownMenu(expanded = fontMenuOpen, onDismissRequest = { fontMenuOpen = false }) {
                    listOf("monospace", "default").forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = {
                                fontMenuOpen = false
                                updateTerminal { it.copy(fontFamily = option) }
                            },
                        )
                    }
                }
            }

            SwitchRow("Show extra keys row", ts.showExtraKeys) { v ->
                updateTerminal { it.copy(showExtraKeys = v) }
            }

            SwitchRow("Keep screen on in terminal", settings.keepScreenOnInTerminal) { v ->
                AppGraph.settings.update { it.copy(keepScreenOnInTerminal = v) }
            }
        }
    }
}
