package com.company.paperdocs.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.company.paperdocs.DocsViewModel
import com.company.paperdocs.data.Collaborator
import com.company.paperdocs.data.Document
import com.company.paperdocs.data.ShareRole

/** Manage who else can open a document, and with which role. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareSheet(vm: DocsViewModel, document: Document, onDismiss: () -> Unit) {
    val people by vm.collaborators.collectAsStateWithLifecycle()
    var email by rememberSaveable { mutableStateOf("") }
    var role by rememberSaveable { mutableStateOf(ShareRole.VIEWER) }

    val notice by vm.shareNotice.collectAsStateWithLifecycle()
    LaunchedEffect(document.id) { vm.loadCollaborators(document) }
    LaunchedEffect(notice) { if (notice != null) { kotlinx.coroutines.delay(4000); vm.clearShareNotice() } }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color.White) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).imePadding()) {
            Text("Share “${document.title}”", style = MaterialTheme.typography.headlineSmall, maxLines = 2)
            Text(
                if (document.remoteId.isEmpty()) "This document has not finished syncing yet."
                else "People need a Paper Docs account with the same email address.",
                style = MaterialTheme.typography.bodySmall, color = DocsMuted,
                modifier = Modifier.padding(top = 6.dp),
            )
            // Rendered here rather than as a snackbar, which a modal sheet would hide.
            notice?.let { text ->
                Surface(
                    color = Color(0xFFE8F0FE), shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                ) {
                    Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(14.dp))
                }
            }
            Spacer(Modifier.height(18.dp))

            OutlinedTextField(
                value = email, onValueChange = { email = it },
                label = { Text("Email address") },
                singleLine = true,
                enabled = document.remoteId.isNotEmpty(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Text("ACCESS", style = MaterialTheme.typography.labelSmall, color = DocsMuted)
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ShareRole.entries.forEach { option ->
                    FilterChip(
                        selected = role == option,
                        onClick = { role = option },
                        label = { Text(option.label) },
                        leadingIcon = {
                            Icon(
                                if (option.canEdit) Icons.Outlined.Edit else Icons.Outlined.Visibility,
                                null, Modifier.size(18.dp),
                            )
                        },
                    )
                }
            }
            Button(
                enabled = document.remoteId.isNotEmpty() && email.isNotBlank() && email.contains('@'),
                onClick = { vm.shareWith(document, email.trim(), role); email = "" },
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
            ) {
                Icon(Icons.Outlined.PersonAdd, null, Modifier.size(18.dp))
                Text("  Share")
            }
            Text(
                if (role == ShareRole.VIEWER) "Viewers can read and export, but not change the text."
                else "Editors can read, write, and share.",
                style = MaterialTheme.typography.bodySmall, color = DocsMuted, modifier = Modifier.padding(top = 8.dp),
            )

            val list = people[document.id].orEmpty()
            if (list.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                Text("PEOPLE WITH ACCESS", style = MaterialTheme.typography.labelSmall, color = DocsMuted)
                list.forEach { person -> CollaboratorRow(person) { vm.revoke(document, person) } }
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun CollaboratorRow(person: Collaborator, onRemove: () -> Unit) {
    Surface(color = Color(0xFFF7F9FC), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(34.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Outlined.AccountCircle, null, tint = DocsMuted, modifier = Modifier.size(28.dp))
            }
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text(person.name.ifBlank { shortUid(person.uid) }, style = MaterialTheme.typography.titleSmall)
                Text(person.role.label, style = MaterialTheme.typography.bodySmall, color = DocsMuted)
            }
            TextButton(onClick = onRemove) { Text("Remove", color = MaterialTheme.colorScheme.error) }
        }
    }
}

private fun shortUid(uid: String) = if (uid.length > 10) uid.take(8) + "…" else uid