package com.openchat.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.openchat.android.core.model.ErrorInfo
import com.openchat.android.core.model.RepairAction
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Shared UI building blocks for every screen (Agent 2-c).
 * These are the only cross-screen widgets; screens compose them freely.
 */

// ---------------------------------------------------------------------------
// ErrorCard — structured, actionable error display (spec §24)
// ---------------------------------------------------------------------------

/**
 * Renders an [ErrorInfo] with distinct colors for title/detail/causes/suggestions
 * and action buttons. Renders nothing when [info] is null.
 *
 * - RETRY button when the error is retryable and [onRetry] provided.
 * - Repair button when [info.repairAction] is a concrete repair and [onRepair] provided.
 * - "Open logs" button when [onOpenLogs] provided.
 */
@Composable
fun ErrorCard(
    info: ErrorInfo?,
    onRetry: (() -> Unit)? = null,
    onRepair: ((RepairAction) -> Unit)? = null,
    onOpenLogs: (() -> Unit)? = null,
) {
    if (info == null) return
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.10f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                info.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.error,
            )
            Text(info.detail, style = MaterialTheme.typography.bodyMedium)
            if (info.causes.isNotEmpty()) {
                Text(
                    "Possible causes:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                info.causes.forEach { cause ->
                    Text("• $cause", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (info.suggestions.isNotEmpty()) {
                Text(
                    "What you can do:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                info.suggestions.forEach { s ->
                    Text("→ $s", style = MaterialTheme.typography.bodySmall)
                }
            }
            val action = info.repairAction
            val repairLabel = when (action) {
                RepairAction.UBUNTU_REPAIR -> "Repair Ubuntu"
                RepairAction.UBUNTU_RESET -> "Reset Ubuntu"
                RepairAction.OPENCODE_REINSTALL -> "Reinstall OpenCode"
                RepairAction.INSTALL_UBUNTU -> "Install Ubuntu"
                else -> null
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (info.retryable && onRetry != null) {
                    Button(onClick = onRetry) { Text("Retry") }
                }
                if (repairLabel != null && onRepair != null && action != null) {
                    Button(onClick = { onRepair(action) }) { Text(repairLabel) }
                }
                if (onOpenLogs != null) {
                    OutlinedButton(onClick = onOpenLogs) { Text("Open logs") }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// LabeledTextField
// ---------------------------------------------------------------------------

/** Standard labeled input field used across all settings/edit screens. */
@Composable
fun LabeledTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    supportingText: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = singleLine,
        visualTransformation = visualTransformation,
        supportingText = supportingText?.let { t -> { Text(t) } },
        keyboardOptions = keyboardOptions,
    )
}

// ---------------------------------------------------------------------------
// ConfirmDialog
// ---------------------------------------------------------------------------

/** Yes/No confirmation dialog for destructive or important actions. */
@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Confirm") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

// ---------------------------------------------------------------------------
// SectionHeader / StatusPill
// ---------------------------------------------------------------------------

/** Small colored section label used to group list content. */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

/**
 * Compact status pill. [ok] == true → green tone, false → red tone,
 * null → neutral tone (e.g. "busy" or "unknown").
 */
@Composable
fun StatusPill(text: String, ok: Boolean?, modifier: Modifier = Modifier) {
    val container = when (ok) {
        true -> MaterialTheme.colorScheme.secondaryContainer
        false -> MaterialTheme.colorScheme.errorContainer
        null -> MaterialTheme.colorScheme.surfaceVariant
    }
    val content = when (ok) {
        true -> MaterialTheme.colorScheme.onSecondaryContainer
        false -> MaterialTheme.colorScheme.onErrorContainer
        null -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = container,
        contentColor = content,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

// ---------------------------------------------------------------------------
// KeyValueEditor — editable map of header/env entries
// ---------------------------------------------------------------------------

/**
 * Editable list of key/value rows with per-row delete and an add button.
 * Emits the current map on every change. Duplicate keys collapse via [Map].
 */
@Composable
fun KeyValueEditor(initial: Map<String, String>, onChange: (Map<String, String>) -> Unit) {
    val rows = remember(initial) {
        initial.entries.map { it.key to it.value }.toMutableStateList()
    }
    fun emit() {
        onChange(rows.toMap())
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEachIndexed { index, pair ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                OutlinedTextField(
                    value = pair.first,
                    onValueChange = {
                        rows[index] = it to pair.second
                        emit()
                    },
                    modifier = Modifier.weight(0.4f),
                    label = { Text("Key") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = pair.second,
                    onValueChange = {
                        rows[index] = pair.first to it
                        emit()
                    },
                    modifier = Modifier.weight(0.6f),
                    label = { Text("Value") },
                    singleLine = true,
                )
                IconButton(onClick = {
                    rows.removeAt(index)
                    emit()
                }) {
                    Icon(AppIcons.Delete, contentDescription = "Remove entry")
                }
            }
        }
        TextButton(onClick = {
            rows.add("" to "")
            emit()
        }) { Text("+ Add entry") }
    }
}

// ---------------------------------------------------------------------------
// CopyIconButton — clipboard copy with brief "Copied" feedback
// ---------------------------------------------------------------------------

/** Copies [text] to the clipboard and flashes green for a moment. */
@Composable
fun CopyIconButton(text: String, modifier: Modifier = Modifier, tint: Color = Color.Unspecified) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }
    IconButton(onClick = {
        clipboard.setText(AnnotatedString(text))
        copied = true
    }, modifier = modifier) {
        Icon(
            CopyGlyph,
            contentDescription = if (copied) "Copied" else "Copy",
            tint = when {
                tint != Color.Unspecified -> tint
                copied -> MaterialTheme.colorScheme.secondary
                else -> LocalContentColor.current
            },
        )
    }
}

/** Small "two sheets" copy icon drawn locally (content_copy is not in icons-core). */
private val CopyGlyph: ImageVector = ImageVector.Builder(
    name = "CopyGlyph",
    defaultWidth = 20.dp,
    defaultHeight = 20.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(fill = SolidColor(Color.White), pathFillType = PathFillType.EvenOdd) {
        moveTo(4f, 1f)
        lineTo(16f, 1f)
        lineTo(16f, 15f)
        lineTo(4f, 15f)
        close()
        moveTo(6f, 3f)
        lineTo(14f, 3f)
        lineTo(14f, 13f)
        lineTo(6f, 13f)
        close()
    }
    path(fill = SolidColor(Color.White)) {
        moveTo(8f, 5f)
        lineTo(20f, 5f)
        lineTo(20f, 21f)
        lineTo(8f, 21f)
        close()
    }
}.build()

// ---------------------------------------------------------------------------
// Small formatting helpers shared by several screens
// ---------------------------------------------------------------------------

/** Human readable byte size: B / KB / MB / GB / TB. */
fun humanizeBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var i = 0
    while (value >= 1024.0 && i < units.lastIndex) {
        value /= 1024.0
        i++
    }
    return if (i == 0) "$bytes B" else String.format(Locale.US, "%.1f %s", value, units[i])
}

/** Short date-time formatting for timestamps shown in lists; "—" for null/0. */
fun formatDate(ms: Long?): String {
    if (ms == null || ms <= 0L) return "—"
    return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.getDefault())
        .format(Date(ms))
}

/**
 * Relative time for process starts and other recent timestamps:
 * "just now", "5 min ago", "3 h ago", "2 d ago", older falls back to [formatDate].
 */
fun humanizeTime(ms: Long): String {
    if (ms <= 0L) return "—"
    val deltaMs = System.currentTimeMillis() - ms
    val minutes = deltaMs / 60_000L
    return when {
        deltaMs < 60_000L -> "just now"
        minutes < 60L -> "$minutes min ago"
        minutes < 24L * 60L -> "${minutes / 60L} h ago"
        minutes < 7L * 24L * 60L -> "${minutes / (24L * 60L)} d ago"
        else -> formatDate(ms)
    }
}
