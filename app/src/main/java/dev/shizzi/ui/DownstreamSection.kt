package dev.shizzi.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import dev.shizzi.DownstreamMode

@Composable
fun DownstreamSection(selected: DownstreamMode, onSelect: (DownstreamMode) -> Unit) {
    val context = LocalContext.current
    var isPickerOpen by remember { mutableStateOf(false) }

    SettingsChoice(
        label = SettingsText(title = "Share over"),
        value = downstreamModeLabel(selected),
        onClick = { isPickerOpen = true },
    )

    SettingsAction(
        label = SettingsText(
            title = "Hotspot name & password",
            subtitle = "Band and security are set in Android settings",
        ),
        isExternal = true,
        onClick = context::openTetherSettings,
    )

    if (!isPickerOpen) return

    DownstreamModePicker(
        selected = selected,
        onSelect = onSelect,
        onDismiss = { isPickerOpen = false },
    )
}
