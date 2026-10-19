package com.company.paperdocs.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.company.paperdocs.DocsViewModel
import com.company.paperdocs.cloud.SyncConflict
import com.company.paperdocs.data.RichContent
import java.text.DateFormat
import java.util.Date

/**
 * Shown when a document was edited both on this device and in the cloud. Nothing is discarded
 * until the reader picks a side, and either choice can be undone from version history.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConflictDialog(conflict: SyncConflict, onKeepMine: () -> Unit, onTakeCloud: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.WarningAmber, null, tint = Color(0xFFE37400)) },
        title = { Text("Choose which version to keep") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "“${conflict.local.title}” changed on this device and in the cloud at the same time. " +
                        "Nothing has been deleted — your last version is saved in version history either way.",
                    style = MaterialTheme.typography.bodyMedium, color = DocsMuted,
                )
                Spacer(Modifier.height(16.dp))
                ConflictSide(
                    heading = "This device",
                    detail = "Edited ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(conflict.local.updatedAt))}",
                    content = conflict.local.content,
                    accent = DocsBlue,
                )
                Spacer(Modifier.height(12.dp))
                ConflictSide(
                    heading = "Google Drive copy",
                    detail = "Synced ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(conflict.remote.updatedAt))}",
                    content = conflict.remote.content,
                    accent = Color(0xFF188038),
                )
            }
        },
        confirmButton = { TextButton(onClick = onTakeCloud) { Text("Use cloud copy") } },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text("Decide later", color = DocsMuted) }
                TextButton(onClick = onKeepMine) { Text("Keep mine") }
            }
        },
    )
}

@Composable
private fun ConflictSide(heading: String, detail: String, content: RichContent, accent: Color) {
    Surface(color = Color(0xFFF7F9FC), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (heading.startsWith("This")) Icons.Outlined.PhoneAndroid else Icons.Outlined.CloudDone,
                    null, tint = accent, modifier = Modifier.size(16.dp),
                )
                Text("  $heading", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text("${content.wordCount} words", style = MaterialTheme.typography.labelSmall, color = DocsMuted)
            }
            Text(detail, style = MaterialTheme.typography.bodySmall, color = DocsMuted, modifier = Modifier.padding(top = 2.dp))
            Text(
                content.text.trim().ifEmpty { "(empty document)" },
                style = MaterialTheme.typography.bodySmall,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp).heightIn(max = 96.dp).verticalScroll(rememberScrollState()),
            )
        }
    }
}