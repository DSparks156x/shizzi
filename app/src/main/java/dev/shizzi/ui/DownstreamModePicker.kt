package dev.shizzi.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.shizzi.DownstreamMode
import dev.shizzi.ui.theme.ShizziTheme

private val CheckSize = 20.dp

fun downstreamModeLabel(mode: DownstreamMode): String = when (mode) {
    DownstreamMode.WIFI -> "Wi-Fi"
    DownstreamMode.USB -> "USB"
    DownstreamMode.BOTH -> "Wi-Fi and USB"
}

private fun descriptionOf(mode: DownstreamMode): String = when (mode) {
    DownstreamMode.WIFI -> "Share through your hotspot"
    DownstreamMode.USB -> "Share with a computer over a USB cable"
    DownstreamMode.BOTH -> "Share through your hotspot and over USB"
}

@Composable
fun DownstreamModePicker(
    selected: DownstreamMode,
    onSelect: (DownstreamMode) -> Unit,
    onDismiss: () -> Unit,
) {
    ThemedBottomSheet(onDismiss = onDismiss) {
        Text(
            text = "Share over",
            style = ShizziTheme.typography.heading,
            color = ShizziTheme.colors.onSurface,
        )

        DownstreamMode.entries.forEach { mode ->
            DownstreamModeOption(
                mode = mode,
                isSelected = mode == selected,
                onSelect = {
                    onSelect(mode)
                    onDismiss()
                },
            )
        }

        Spacer(Modifier.height(ShizziTheme.spacing.lg))
    }
}

@Composable
private fun DownstreamModeOption(
    mode: DownstreamMode,
    isSelected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = ShizziTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = downstreamModeLabel(mode),
                style = ShizziTheme.typography.subheading,
                color = ShizziTheme.colors.onSurface,
            )

            Text(
                text = descriptionOf(mode),
                style = ShizziTheme.typography.body,
                color = ShizziTheme.colors.onSurfaceMuted,
            )
        }

        if (!isSelected) return@Row

        Icon(
            imageVector = Icons.Filled.Check,
            contentDescription = null,
            tint = ShizziTheme.colors.primary,
            modifier = Modifier.size(CheckSize),
        )
    }
}
