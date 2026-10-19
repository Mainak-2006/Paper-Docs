package com.company.paperdocs.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.company.paperdocs.DocsViewModel
import com.company.paperdocs.cloud.DriveFile
import java.text.DateFormat
import java.util.Date

/** Browses Drive and imports a copy back into the local library. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriveBrowserSheet(vm: DocsViewModel, onDismiss: () -> Unit) {
    var files by remember { mutableStateOf<List<DriveFile>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var failure by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            files = vm.driveFiles()
        } catch (error: Exception) {
            failure = error.message
        } finally {
            loading = false
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color.White) {
        Column(Modifier.padding(horizontal = 24.dp).navigationBarsPadding()) {
            Text("From Google Drive", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Importing keeps a copy on this device. The Drive file is left untouched.",
                style = MaterialTheme.typography.bodySmall, color = DocsMuted,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            when {
                loading -> Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                failure != null -> Text(
                    failure.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
                files.isEmpty() -> Text(
                    "No Paper Docs files found in Drive yet. Export one to get started.",
                    style = MaterialTheme.typography.bodyMedium, color = DocsMuted, modifier = Modifier.padding(vertical = 24.dp),
                )
                else -> LazyColumn(Modifier.heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(files, key = { it.id }) { file ->
                        DriveRow(file, onImport = { vm.importFromDrive(file); onDismiss() })
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun DriveRow(file: DriveFile, onImport: () -> Unit) {
    val importable = file.mimeType in setOf(
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "text/plain", "text/html", "application/pdf",
    )
    Surface(color = Color(0xFFF7F9FC), shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (file.mimeType == "application/vnd.google-apps.document") Icons.Outlined.Description
                else Icons.Outlined.InsertDriveFile,
                null, tint = if (importable) DocsBlue else DocsMuted,
            )
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(file.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    buildString {
                        append(if (file.mimeType == "application/vnd.google-apps.document") "Google Docs" else file.mimeType.substringAfterLast('/').uppercase())
                        if (file.size > 0) append(" · ${file.size / 1024} KB")
                        if (file.modifiedTime.isNotEmpty()) {
                            runCatching {
                                append(" · " + DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(file.modifiedTime)))
                            }
                        }
                    },
                    style = MaterialTheme.typography.bodySmall, color = DocsMuted,
                )
            }
            if (importable) {
                TextButton(onClick = onImport) { Text("Import") }
            } else {
                // Native Google Docs formats cannot be parsed by the local importers.
                Text("Open in Drive", style = MaterialTheme.typography.labelSmall, color = DocsMuted)
            }
        }
    }
}