package com.company.paperdocs

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.company.paperdocs.data.Document
import com.company.paperdocs.io.DocumentFiles
import com.company.paperdocs.io.ExportFormat
import com.company.paperdocs.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { PaperDocsTheme { PaperDocsApp() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaperDocsApp(vm: DocsViewModel = viewModel()) {
    val documents by vm.documents.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var export by remember { mutableStateOf<Document?>(null) }
    var share by remember { mutableStateOf(false) }
    var drive by remember { mutableStateOf(false) }
    val account by vm.account.collectAsStateWithLifecycle()
    val driveReady by vm.driveReady.collectAsStateWithLifecycle()
    var busy by remember { mutableStateOf(false) }
    var pendingDocument by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingFormat by rememberSaveable { mutableStateOf(ExportFormat.PDF.name) }

    LaunchedEffect(vm) { vm.events.collect { snackbar.showSnackbar(it) } }

    // Prompt as soon as a conflict is detected, wherever the reader happens to be.
    val conflict by vm.conflictToResolve.collectAsStateWithLifecycle()
    conflict?.let { pending ->
        ConflictDialog(
            conflict = pending,
            onKeepMine = { vm.resolveConflict(acceptLocal = true) },
            onTakeCloud = { vm.resolveConflict(acceptLocal = false) },
            onDismiss = { vm.dismissConflictPrompt() },
        )
    }

    val activity = LocalActivity.current
    // Drive needs its own OAuth consent grant, separate from the Firebase sign-in.
    val driveConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        activity?.let { vm.completeDriveConsent(it, result.data) }
    }

    LaunchedEffect(account?.uid, activity) {
        if (account != null && activity != null) vm.primeDriveToken(activity)
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try {
                val (title, content) = withContext(Dispatchers.IO) { DocumentFiles.import(context, uri) }
                vm.importDocument(title, content)
            } catch (error: Exception) { vm.message(error.message ?: "Could not import that document") }
            finally { busy = false }
        }
    }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && uri != null) scope.launch {
            busy = true
            try {
                // Read from the retained ViewModel after the document picker returns.
                val document = vm.active?.takeIf { it.id == pendingDocument } ?: vm.documents.value.firstOrNull { it.id == pendingDocument } ?: error("Document is no longer available")
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { DocumentFiles.write(document, ExportFormat.valueOf(pendingFormat), it) } ?: error("Could not write to that location")
                }
                vm.message("Document exported")
            } catch (error: Exception) { vm.message("Export failed: ${error.message}") }
            finally { busy = false; pendingDocument = null }
        }
    }

    val active = vm.active
    if (active == null) LibraryScreen(vm, documents, onImport = { importLauncher.launch(arrayOf("text/plain", "text/html", ExportFormat.DOCX.mime)) }, onExport = { export = it; share = false }, snackbar = snackbar)
    else key(active.id) { EditorScreen(vm, active, snackbar) { export = it; share = false } }

    export?.let { document ->
        ModalBottomSheet(onDismissRequest = { export = null }, containerColor = Color.White) {
            Text("Your words, out in the world", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
            Text(document.title, color = DocsMuted, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
            Row(Modifier.padding(horizontal = 24.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilterChip(selected = !share, onClick = { share = false }, label = { Text("Save a file") }, leadingIcon = { Icon(Icons.Outlined.Download, null, Modifier.size(18.dp)) })
                FilterChip(selected = share, onClick = { share = true }, label = { Text("Send a copy") }, leadingIcon = { Icon(Icons.Outlined.IosShare, null, Modifier.size(18.dp)) })
                FilterChip(
                    selected = drive,
                    onClick = {
                        if (account != null && !driveReady && activity != null) {
                            vm.connectDrive(activity) { pending -> driveConsent.launch(androidx.activity.result.IntentSenderRequest.Builder(pending).build()) }
                        }
                        drive = true
                    },
                    enabled = account != null,
                    label = { Text(if (driveReady) "Google Drive" else "Connect Drive") },
                    leadingIcon = {
                        Icon(if (driveReady) Icons.Outlined.CloudDone else Icons.Outlined.CloudUpload, null, Modifier.size(18.dp))
                    },
                )
            }
            if (account == null) {
                Text(
                    "Sign in from the menu to save copies straight to Google Drive.",
                    color = DocsMuted, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 6.dp),
                )
            }
            ExportFormat.entries.forEach { format ->
                ListItem(headlineContent = { Text(format.label) }, supportingContent = { Text(format.description) }, leadingContent = { Icon(if (format == ExportFormat.PDF) Icons.Outlined.PictureAsPdf else Icons.Outlined.Description, null, tint = DocsBlue) }, trailingContent = { Text(".${format.extension}", color = DocsMuted) }, modifier = Modifier.clickable {
                    export = null
                    if (drive) {
                        scope.launch {
                            busy = true
                            try {
                                val bytes = withContext(Dispatchers.IO) {
                                    ByteArrayOutputStream().also { DocumentFiles.write(document, format, it) }.toByteArray()
                                }
                                vm.saveToDrive(document, DocumentFiles.filename(document.title, format), format.mime, bytes)
                            } catch (error: Exception) { vm.message("Drive upload failed: ${error.message}") }
                            finally { busy = false }
                        }
                    } else if (share) scope.launch {
                        busy = true
                        try {
                            val file = withContext(Dispatchers.IO) {
                                val directory = File(context.cacheDir, "exports/${UUID.randomUUID()}").apply { check(mkdirs()) }
                                File(directory, DocumentFiles.filename(document.title, format)).also { file -> file.outputStream().use { DocumentFiles.write(document, format, it) } }
                            }
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = format.mime
                                putExtra(Intent.EXTRA_STREAM, uri)
                                putExtra(Intent.EXTRA_SUBJECT, document.title)
                                clipData = ClipData.newRawUri(document.title, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, "Send a copy"))
                        } catch (error: Exception) { vm.message("Could not share: ${error.message}") }
                        finally { busy = false }
                    } else {
                        pendingDocument = document.id
                        pendingFormat = format.name
                        saveLauncher.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type = format.mime
                            putExtra(Intent.EXTRA_TITLE, DocumentFiles.filename(document.title, format))
                        })
                    }
                }.padding(horizontal = 8.dp))
            }
            Spacer(Modifier.height(28.dp))
        }
    }
    if (busy) AlertDialog(onDismissRequest = {}, title = { Text("Preparing your document…") }, text = { LinearProgressIndicator(Modifier.fillMaxWidth()) }, confirmButton = {})
}
