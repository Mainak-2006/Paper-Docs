package com.company.paperdocs.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.company.paperdocs.DocsViewModel
import com.company.paperdocs.data.*
import com.company.paperdocs.editor.DocumentEditText
import com.company.paperdocs.editor.RichEditorController
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(vm: DocsViewModel, document: Document, snackbar: SnackbarHostState, onExport: (Document) -> Unit) {
    val controller = remember(document.id) { RichEditorController(document.content, vm::edit) }
    var rename by remember { mutableStateOf(false) }
    var share by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var format by remember { mutableStateOf(false) }
    var find by rememberSaveable(document.id) { mutableStateOf(false) }
    var wordCount by remember { mutableStateOf(false) }
    var notes by remember { mutableStateOf(false) }
    var versions by remember { mutableStateOf(false) }
    var outline by remember { mutableStateOf(false) }
    BackHandler { vm.close() }

    Scaffold(
        modifier = Modifier.imePadding(), containerColor = Color(0xFFEEF1F5),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column(Modifier.background(Color.White).statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { vm.close() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to documents") }
                    Column(Modifier.weight(1f).clickable { rename = true }.padding(vertical = 6.dp)) {
                        Text(document.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 3.dp).clickable { vm.retrySave() }) {
                            Icon(if (vm.saveStatus.startsWith("Saved")) Icons.Outlined.CheckCircleOutline else Icons.Outlined.CloudOff, null, tint = if (vm.saveStatus.startsWith("Not")) MaterialTheme.colorScheme.error else Color(0xFF188038), modifier = Modifier.size(12.dp))
                            Text("  ${vm.saveStatus}", fontSize = 10.sp, color = DocsMuted, maxLines = 1)
                        }
                    }
                    IconButton(onClick = { notes = true }) { Icon(Icons.AutoMirrored.Outlined.Comment, "Private notes", tint = DocsMuted) }
                    IconButton(onClick = { onExport(vm.active ?: document) }) { Icon(Icons.Outlined.IosShare, "Share and export", tint = DocsBlue) }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Document options") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Outlined.Edit, null) }, onClick = { menu = false; rename = true })
                            if (document.ownedBy.isEmpty()) {
                                DropdownMenuItem(text = { Text("Share") }, leadingIcon = { Icon(Icons.Outlined.PersonAdd, null) }, onClick = { menu = false; share = true })
                            }
                            DropdownMenuItem(text = { Text(if (document.starred) "Remove star" else "Add to starred") }, leadingIcon = { Icon(Icons.Outlined.StarOutline, null) }, onClick = { menu = false; vm.star(document) })
                            DropdownMenuItem(text = { Text("Find and replace") }, leadingIcon = { Icon(Icons.Outlined.FindReplace, null) }, onClick = { menu = false; find = true })
                            DropdownMenuItem(text = { Text("Word count") }, leadingIcon = { Icon(Icons.Outlined.TextFields, null) }, onClick = { menu = false; wordCount = true })
                            DropdownMenuItem(text = { Text("Document outline") }, leadingIcon = { Icon(Icons.AutoMirrored.Outlined.List, null) }, onClick = { menu = false; outline = true })
                            DropdownMenuItem(text = { Text("Version history") }, leadingIcon = { Icon(Icons.Outlined.History, null) }, onClick = { menu = false; versions = true })
                            DropdownMenuItem(text = { Text("Save a version") }, leadingIcon = { Icon(Icons.Outlined.BookmarkAdd, null) }, onClick = { menu = false; vm.checkpoint() })
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text("Make a copy") }, leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) }, onClick = { menu = false; vm.duplicate(vm.active ?: document) })
                            DropdownMenuItem(text = { Text("Move to trash") }, leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) }, onClick = { menu = false; vm.trash(document) })
                        }
                    }
                }
                HorizontalDivider(color = DocsLine)
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    ToolIcon(Icons.AutoMirrored.Outlined.Undo, "Undo", enabled = controller.canUndo) { controller.undo() }
                    ToolIcon(Icons.AutoMirrored.Outlined.Redo, "Redo", enabled = controller.canRedo) { controller.redo() }
                    VerticalDivider(Modifier.height(22.dp).padding(horizontal = 7.dp))
                    TextButton(onClick = { format = true }) { Text("Format", color = DocsInk); Icon(Icons.Outlined.KeyboardArrowDown, null, tint = DocsMuted, modifier = Modifier.size(18.dp)) }
                    Spacer(Modifier.weight(1f))
                    Collaborators(vm, document.id)
                    ToolIcon(Icons.Outlined.Search, "Find text", selected = find) { find = !find }
                    Text("${controller.content.wordCount} words", color = DocsMuted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.clickable { wordCount = true }.padding(horizontal = 8.dp))
                }
                if (find) FindPanel(controller, onClose = { find = false })
            }
        },
        bottomBar = {
            Surface(color = Color.White, shadowElevation = 5.dp) {
                Row(Modifier.navigationBarsPadding().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    ToolIcon(Icons.Outlined.FormatBold, "Bold", controller.active("bold")) { controller.format("bold") }
                    ToolIcon(Icons.Outlined.FormatItalic, "Italic", controller.active("italic")) { controller.format("italic") }
                    ToolIcon(Icons.Outlined.FormatUnderlined, "Underline", controller.active("underline")) { controller.format("underline") }
                    ToolIcon(Icons.Outlined.StrikethroughS, "Strikethrough", controller.active("strike")) { controller.format("strike") }
                    VerticalDivider(Modifier.height(24.dp).padding(horizontal = 7.dp))
                    ToolIcon(Icons.AutoMirrored.Outlined.FormatAlignLeft, "Align left") { controller.align("left") }
                    ToolIcon(Icons.Outlined.FormatAlignCenter, "Align center") { controller.align("center") }
                    ToolIcon(Icons.AutoMirrored.Outlined.FormatListBulleted, "Bulleted list") { controller.list() }
                    ToolIcon(Icons.Outlined.FormatListNumbered, "Numbered list") { controller.list(numbered = true) }
                    ToolIcon(Icons.Outlined.Checklist, "Checklist") { controller.list(checklist = true) }
                    ToolIcon(Icons.Outlined.FormatColorText, "More formatting") { format = true }
                }
            }
        },
    ) { padding ->
        BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
            val wide = maxWidth > 650.dp
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = if (wide) 32.dp else 12.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(color = Color.White, shadowElevation = 2.dp, modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = if (wide) 56.dp else 26.dp, vertical = 38.dp)) {
                        AndroidView(factory = { context -> DocumentEditText(context).also(controller::attach) }, modifier = Modifier.fillMaxWidth(), onRelease = { controller.detach() })
                    }
                }
                Row(Modifier.padding(vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    val account by vm.account.collectAsStateWithLifecycle()
                    Icon(if (account == null) Icons.Outlined.Lock else Icons.Outlined.CloudDone, null, Modifier.size(12.dp), tint = DocsMuted)
                    Text(
                        "  " + when {
                            account == null -> "Your words, saved on your device"
                            document.readOnly -> "Shared with you as a viewer"
                            else -> "Synced with your account"
                        },
                        style = MaterialTheme.typography.labelSmall, color = DocsMuted,
                    )
                }
            }
        }
    }
    if (share) ShareSheet(vm, document) { share = false }
    if (rename) RenameDialog(document.title, { rename = false }) { vm.rename(vm.active ?: document, it); rename = false }
    if (format) FormatSheet(controller) { format = false }
    if (wordCount) AlertDialog(onDismissRequest = { wordCount = false }, title = { Text("A few words about your words") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            CountRow("Words", controller.content.wordCount)
            CountRow("Characters", controller.content.text.codePointCount(0, controller.content.text.length))
            CountRow("Without whitespace", controller.content.text.codePoints().filter { !Character.isWhitespace(it) }.count().toInt())
        }
    }, confirmButton = { TextButton(onClick = { wordCount = false }) { Text("Done") } })
    if (notes) NotesSheet(vm, document.id, controller.selectedText()) { notes = false }
    if (versions) VersionsSheet(vm, document.id, onRestore = { revision ->
        controller.restore(revision.content)
        vm.active?.let { vm.rename(it, revision.title) }
        versions = false
        vm.message("Version restored. You can undo this change.")
    }, onDismiss = { versions = false })
    if (outline) ModalBottomSheet(onDismissRequest = { outline = false }, containerColor = Color.White) {
        Text("Document outline", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(24.dp))
        val headings = controller.content.marks.filter { it.kind == "size" && (it.value.toIntOrNull() ?: 0) >= 20 }.distinctBy { it.start }
        if (headings.isEmpty()) Text("Apply a heading style from Format to add an outline.", color = DocsMuted, modifier = Modifier.padding(24.dp))
        headings.forEach { heading ->
            ListItem(headlineContent = { Text(controller.content.text.substring(heading.start, heading.end).trim(), maxLines = 2) }, leadingContent = { Icon(Icons.Outlined.Title, null) }, modifier = Modifier.clickable { outline = false; controller.select(heading.start until heading.end) })
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun CountRow(title: String, number: Int) { Row(Modifier.fillMaxWidth()) { Text(title, Modifier.weight(1f)); Text(number.toString(), fontWeight = FontWeight.Medium) } }

/** Shows who else has this document open, when live collaboration is available. */
@Composable
private fun Collaborators(vm: DocsViewModel, documentId: String) {
    val presence by vm.presence.collectAsStateWithLifecycle()
    val others = presence[documentId].orEmpty()
    if (others.isEmpty()) return
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 4.dp)) {
        others.take(3).forEach { person ->
            Box(
                Modifier.padding(start = 2.dp).size(24.dp).clip(CircleShape).background(DocsBlue),
                contentAlignment = Alignment.Center,
            ) { Text(person.initial(), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Medium) }
        }
        val extra = others.size - 3
        if (extra > 0) Text(" +$extra", color = DocsMuted, fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp))
        Text(
            "  ${if (others.size == 1) "1 person editing" else "${others.size} people editing"}",
            color = DocsMuted, fontSize = 11.sp,
        )
    }
}

private fun com.company.paperdocs.cloud.Presence.initial(): String =
    name.trim().take(1).uppercase().ifEmpty { "?" }

@Composable
private fun ToolIcon(icon: ImageVector, label: String, selected: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(if (selected) Color(0xFFD3E3FD) else Color.Transparent)) {
        Icon(icon, label, tint = if (!enabled) Color(0xFFB9BFC6) else if (selected) DocsBlue else DocsInk, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun FindPanel(controller: RichEditorController, onClose: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var replacement by rememberSaveable { mutableStateOf("") }
    var index by remember { mutableIntStateOf(-1) }
    var matchCase by rememberSaveable { mutableStateOf(false) }
    val matches = controller.matches(query, matchCase)
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(query, { query = it; index = -1 }, label = { Text("Find") }, singleLine = true, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "Close find and replace") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(replacement, { replacement = it }, label = { Text("Replace with") }, singleLine = true, modifier = Modifier.weight(1f))
            IconButton(enabled = matches.isNotEmpty(), onClick = { index = if (index < 0) matches.lastIndex else (index - 1 + matches.size) % matches.size; controller.select(matches[index]) }) { Icon(Icons.Outlined.KeyboardArrowUp, "Previous match") }
            IconButton(enabled = matches.isNotEmpty(), onClick = { index = (index + 1) % matches.size; controller.select(matches[index]) }) { Icon(Icons.Outlined.KeyboardArrowDown, "Next match") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (index in matches.indices) "${index + 1} of ${matches.size}" else "${matches.size} found", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
            FilterChip(selected = matchCase, onClick = { matchCase = !matchCase; index = -1 }, label = { Text("Aa") })
            TextButton(enabled = matches.isNotEmpty(), onClick = { controller.replace(query, replacement, false, matchCase) }) { Text("Replace", fontSize = 12.sp) }
            TextButton(enabled = matches.isNotEmpty(), onClick = { controller.replace(query, replacement, true, matchCase) }) { Text("All", fontSize = 12.sp) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FormatSheet(controller: RichEditorController, onDismiss: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color.White) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Make it your own", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Done") }
            }
            Text(if (controller.selectionStart == controller.selectionEnd) "Choose a style, then keep writing." else "Formatting your selected text", color = DocsMuted, style = MaterialTheme.typography.bodySmall)
            SecondaryTabRow(selectedTabIndex = tab, containerColor = Color.White, modifier = Modifier.padding(top = 16.dp)) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Text") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Paragraph") })
            }
            Spacer(Modifier.height(20.dp))
            if (tab == 0) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ToolIcon(Icons.Outlined.FormatBold, "Bold", controller.active("bold")) { controller.format("bold") }
                    ToolIcon(Icons.Outlined.FormatItalic, "Italic", controller.active("italic")) { controller.format("italic") }
                    ToolIcon(Icons.Outlined.FormatUnderlined, "Underline", controller.active("underline")) { controller.format("underline") }
                    ToolIcon(Icons.Outlined.StrikethroughS, "Strikethrough", controller.active("strike")) { controller.format("strike") }
                }
                SectionLabel("FONT")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Sans serif" to "sans-serif", "Serif" to "serif", "Monospace" to "monospace").forEach { (label, font) -> AssistChip(onClick = { controller.format("font", font, false) }, label = { Text(label) }) }
                }
                SectionLabel("SIZE")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(12, 14, 16, 18, 20, 24, 28, 32).forEach { size -> AssistChip(onClick = { controller.format("size", size.toString(), false) }, label = { Text(size.toString()) }) }
                }
                SectionLabel("TEXT COLOR")
                ColorRow(listOf(0xFF202124, 0xFF1A73E8, 0xFFD93025, 0xFF188038, 0xFF9334E6, 0xFFEA8600, 0xFF68717D)) { controller.format("color", it.toString(), false) }
                SectionLabel("HIGHLIGHT")
                ColorRow(listOf(0xFFFFF2A8, 0xFFD3E3FD, 0xFFCEEAD6, 0xFFFAD2CF, 0xFFE9D2FD, 0xFFFFFFFF)) { controller.format("highlight", it.toString(), false) }
                TextButton(onClick = { controller.clearFormatting() }, modifier = Modifier.padding(top = 12.dp)) { Icon(Icons.Outlined.FormatClear, null); Text("  Clear formatting") }
            } else {
                SectionLabel("PARAGRAPH STYLE")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Normal" to 16, "Title" to 32, "Heading 1" to 28, "Heading 2" to 24, "Heading 3" to 20).forEach { (label, size) -> AssistChip(onClick = { controller.heading(size) }, label = { Text(label) }) }
                }
                SectionLabel("ALIGNMENT")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ToolIcon(Icons.AutoMirrored.Outlined.FormatAlignLeft, "Align left") { controller.align("left") }
                    ToolIcon(Icons.Outlined.FormatAlignCenter, "Align center") { controller.align("center") }
                    ToolIcon(Icons.AutoMirrored.Outlined.FormatAlignRight, "Align right") { controller.align("right") }
                }
                SectionLabel("LISTS")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ToolIcon(Icons.AutoMirrored.Outlined.FormatListBulleted, "Bulleted list") { controller.list() }
                    ToolIcon(Icons.Outlined.FormatListNumbered, "Numbered list") { controller.list(numbered = true) }
                    ToolIcon(Icons.Outlined.Checklist, "Checklist") { controller.list(checklist = true) }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String) { Text(text, color = DocsMuted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 20.dp, bottom = 10.dp)) }

@Composable
private fun ColorRow(colors: List<Long>, onColor: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
        colors.forEach { color -> IconButton(onClick = { onColor(color.toInt()) }, modifier = Modifier.size(40.dp).clip(CircleShape).background(Color(color))) { Icon(Icons.Outlined.Circle, "Color ${color.toString(16)}", tint = Color.Black.copy(alpha = 0.15f), modifier = Modifier.size(38.dp)) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotesSheet(vm: DocsViewModel, documentId: String, quote: String, onDismiss: () -> Unit) {
    var notes by remember { mutableStateOf<List<Note>>(emptyList()) }
    var body by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(documentId) {
        try { notes = vm.notes(documentId) } catch (error: Exception) { vm.message("Could not load notes: ${error.message}") }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color.White) {
        Column(Modifier.padding(horizontal = 24.dp).imePadding()) {
            Text("A note to yourself", style = MaterialTheme.typography.headlineSmall)
            Text("Private notes · saved only on this device", color = DocsMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp, bottom = 20.dp))
            if (quote.isNotBlank()) Surface(color = Color(0xFFF0F4F9), shape = RoundedCornerShape(12.dp)) { Text("“$quote”", Modifier.padding(12.dp), maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) }
            OutlinedTextField(body, { body = it }, label = { Text("Write a note") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), minLines = 2, maxLines = 4)
            Button(enabled = body.isNotBlank() && !busy, onClick = {
                busy = true
                scope.launch {
                    try { vm.putNote(Note(documentId = documentId, quote = quote, body = body.trim())); notes = vm.notes(documentId); body = "" }
                    catch (error: Exception) { vm.message("Could not save note: ${error.message}") }
                    finally { busy = false }
                }
            }, modifier = Modifier.align(Alignment.End).padding(vertical = 8.dp)) { Text("Add note") }
            LazyColumn(Modifier.heightIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(notes, key = { it.id }) { note ->
                    Surface(shape = RoundedCornerShape(12.dp), color = if (note.resolved) Color(0xFFF5F5F5) else Color(0xFFF0F4F9)) {
                        Column(Modifier.padding(14.dp)) {
                            if (note.quote.isNotBlank()) Text("“${note.quote}”", style = MaterialTheme.typography.bodySmall, color = DocsMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(note.body, Modifier.padding(top = 6.dp))
                            TextButton(onClick = { scope.launch { try { vm.putNote(note.copy(resolved = !note.resolved)); notes = vm.notes(documentId) } catch (error: Exception) { vm.message("Could not update note") } } }) { Text(if (note.resolved) "Reopen" else "Mark resolved") }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VersionsSheet(vm: DocsViewModel, documentId: String, onRestore: (Revision) -> Unit, onDismiss: () -> Unit) {
    var revisions by remember { mutableStateOf<List<Revision>>(emptyList()) }
    var selected by remember { mutableStateOf<Revision?>(null) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(documentId) {
        try { revisions = vm.revisions(documentId) } catch (error: Exception) { vm.message("Could not load versions") }
        finally { loading = false }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color.White) {
        Text("Version history", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
        Text("Up to 30 local snapshots, including previous editing sessions.", color = DocsMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        if (loading) CircularProgressIndicator(Modifier.padding(24.dp))
        else if (revisions.isEmpty()) Text("Your earlier versions will appear here as you write.", modifier = Modifier.padding(24.dp))
        LazyColumn(Modifier.heightIn(max = 460.dp)) {
            items(revisions, key = { it.id }) { revision ->
                ListItem(headlineContent = { Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(revision.createdAt))) }, supportingContent = { Text("${revision.title} · ${revision.content.wordCount} words", maxLines = 1) }, leadingContent = { Icon(Icons.Outlined.History, null) }, trailingContent = { Icon(Icons.Outlined.Restore, "Restore version") }, modifier = Modifier.clickable { selected = revision })
            }
        }
        Spacer(Modifier.height(32.dp))
    }
    selected?.let { revision -> AlertDialog(onDismissRequest = { selected = null }, title = { Text("Restore this version?") }, text = { Text(revision.content.text.take(350).ifBlank { "Empty document" }) }, confirmButton = { TextButton(onClick = { onRestore(revision) }) { Text("Restore") } }, dismissButton = { TextButton(onClick = { selected = null }) { Text("Cancel") } }) }
}
