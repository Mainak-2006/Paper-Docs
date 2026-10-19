package com.company.paperdocs.ui

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.company.paperdocs.DocsViewModel
import com.company.paperdocs.cloud.Account
import com.company.paperdocs.data.SyncState

/** Account entry point: Google sign-in and sync status. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSheet(vm: DocsViewModel, onDismiss: () -> Unit) {
    val account by vm.account.collectAsStateWithLifecycle()
    val busy by vm.accountBusy.collectAsStateWithLifecycle()
    val restoring by vm.accountRestoring.collectAsStateWithLifecycle()
    val activity = LocalActivity.current ?: return
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmAccountDeletion by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = { vm.clearAccountMessage(); onDismiss() }, containerColor = Color.White) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).imePadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Your account", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "Close account settings") }
            }
            Spacer(Modifier.height(4.dp))

            // Shown here rather than as a snackbar: a modal sheet covers the scaffold's
            // snackbar host, so a snackbar raised here would never be seen.
            val notice by vm.accountMessage.collectAsStateWithLifecycle()
            notice?.let { text ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.size(20.dp))
                        Text("  $text", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }

            val user = account
            if (user != null) {
                SignedInCard(user)
                Spacer(Modifier.height(18.dp))
                SyncSummary(vm)
                val error = vm.syncError.collectAsStateWithLifecycle().value
                if (error != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text(error.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                            if (error.retryable) {
                                TextButton(onClick = { vm.retrySync() }, modifier = Modifier.padding(top = 4.dp)) {
                                    Text("Retry now", color = MaterialTheme.colorScheme.onErrorContainer)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
                OutlinedButton(onClick = { vm.signOut() }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.AutoMirrored.Outlined.Logout, null, Modifier.size(18.dp))
                    Text("  Sign out")
                }
                Text(
                    "Signing out keeps every document on this device. They will sync again the next time you sign in.",
                    style = MaterialTheme.typography.bodySmall, color = DocsMuted, modifier = Modifier.padding(top = 10.dp),
                )

                Spacer(Modifier.height(20.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                Text("YOUR DATA", style = MaterialTheme.typography.labelSmall, color = DocsMuted)
                Text(
                    "Remove every document and account entry Paper Docs holds in the cloud for " +
                        "${user.email.ifBlank { user.name }}. Your documents stay on this device.",
                    style = MaterialTheme.typography.bodySmall, color = DocsMuted, modifier = Modifier.padding(top = 6.dp),
                )
                TextButton(
                    onClick = { confirmDelete = true },
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Icon(Icons.Outlined.DeleteForever, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                    Text("  Delete my cloud data", color = MaterialTheme.colorScheme.error)
                }
                if (confirmDelete) {
                    AlertDialog(
                        onDismissRequest = { confirmDelete = false },
                        icon = { Icon(Icons.Outlined.WarningAmber, null, tint = MaterialTheme.colorScheme.error) },
                        title = { Text("Delete all cloud data?") },
                        text = {
                            Text(
                                "Every document Paper Docs has uploaded for " +
                                    "${user.email.ifBlank { user.name }} will be permanently removed, along with the " +
                                    "email lookup that lets other people share documents with you.\n\n" +
                                    "Documents on this device are kept.",
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = { confirmDelete = false; vm.deleteAllCloudData() }) {
                                Text("Delete everything", color = MaterialTheme.colorScheme.error)
                            }
                        },
                        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
                    )
                }

                Spacer(Modifier.height(20.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                Text("DELETE ACCOUNT", style = MaterialTheme.typography.labelSmall, color = DocsMuted)
                Text(
                    "Sign out and close your Paper Docs account. Every document is removed from " +
                        "the cloud and from this device, and the account itself is deleted from " +
                        "Firebase. This cannot be undone.",
                    style = MaterialTheme.typography.bodySmall, color = DocsMuted, modifier = Modifier.padding(top = 6.dp),
                )
                TextButton(
                    enabled = !busy,
                    onClick = { confirmAccountDeletion = true },
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Icon(Icons.Outlined.PersonOff, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                    Text("  Delete my account and data", color = MaterialTheme.colorScheme.error)
                }
                if (confirmAccountDeletion) {
                    AlertDialog(
                        onDismissRequest = { confirmAccountDeletion = false },
                        icon = { Icon(Icons.Outlined.WarningAmber, null, tint = MaterialTheme.colorScheme.error) },
                        title = { Text("Delete your account?") },
                        text = {
                            Text(
                                "This permanently deletes:\n\n" +
                                    "• Every document, version and note, on this device and in the cloud\n" +
                                    "• Your Google account's Paper Docs account\n" +
                                    "• Your presence and sharing entries in other people's documents\n\n" +
                                    "If you only want to stop syncing while keeping your documents, " +
                                    "use \"Delete my cloud data\" above, or sign out.",
                            )
                        },
                        confirmButton = {
                            TextButton(
                                enabled = !busy,
                                onClick = { confirmAccountDeletion = false; onDismiss(); vm.deleteAccount() },
                            ) { Text("Delete my account", color = MaterialTheme.colorScheme.error) }
                        },
                        dismissButton = { TextButton(onClick = { confirmAccountDeletion = false }) { Text("Cancel") } },
                    )
                }
            } else if (restoring) {
                // Firebase is restoring the saved session; do not flash a sign-in prompt.
                Row(Modifier.fillMaxWidth().padding(vertical = 28.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("  Restoring your account…", style = MaterialTheme.typography.bodyMedium, color = DocsMuted)
                }
            } else {
                Text(
                    "Sign in to keep documents in sync across devices and collaborate in real time. " +
                        "Everything still works offline if you skip this.",
                    style = MaterialTheme.typography.bodyMedium, color = DocsMuted,
                )
                Button(
                    enabled = !busy,
                    onClick = { vm.signInWithGoogle(activity) },
                    modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
                ) { Text(if (busy) "Signing in…" else "Continue with Google") }
                if (!vm.cloudConfigured) {
                    Surface(color = Color(0xFFFEF7E0), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) {
                        Text(
                            "Add app/google-services.json to enable sign-in, Drive sync, and live collaboration.",
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(14.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

@Composable
private fun SignedInCard(user: Account) {
    Surface(color = Color(0xFFF0F4F9), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(DocsBlue),
                contentAlignment = Alignment.Center,
            ) { Text(user.initial, color = Color.White, style = MaterialTheme.typography.titleMedium) }
            Column(Modifier.padding(start = 14.dp)) {
                Text(user.name, style = MaterialTheme.typography.titleSmall)
                if (user.email.isNotEmpty()) Text(user.email, style = MaterialTheme.typography.bodySmall, color = DocsMuted)
            }
        }
    }
}

@Composable
private fun SyncSummary(vm: DocsViewModel) {
    val documents by vm.documents.collectAsStateWithLifecycle()
    val synced = documents.count { it.syncState == SyncState.SYNCED }
    val pending = documents.count { it.syncState == SyncState.SYNCING }
    val conflicts = documents.count { it.syncState == SyncState.CONFLICT }
    val local = documents.count { it.syncState == SyncState.LOCAL }
    val failed = documents.count { it.syncState == SyncState.FAILED }

    Column {
        Text("SYNC", color = DocsMuted, style = MaterialTheme.typography.labelSmall)
        SyncRow(Icons.Outlined.CloudDone, "$synced documents in the cloud", DocsGreen)
        if (pending > 0) SyncRow(Icons.Outlined.CloudSync, "$pending waiting to upload", DocsMuted)
        if (conflicts > 0) SyncRow(Icons.Outlined.WarningAmber, "$conflicts need your attention", MaterialTheme.colorScheme.error)
        if (failed > 0) SyncRow(Icons.Outlined.CloudOff, "$failed could not be uploaded", MaterialTheme.colorScheme.error)
        if (local > 0) SyncRow(Icons.Outlined.PhoneAndroid, "$local only on this device", DocsMuted)
    }
}

@Composable
private fun SyncRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color) {
    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
        Text("  $label", style = MaterialTheme.typography.bodyMedium)
    }
}