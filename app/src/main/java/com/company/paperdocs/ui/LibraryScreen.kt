package com.company.paperdocs.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.company.paperdocs.DocsViewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.company.paperdocs.data.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(vm: DocsViewModel, documents: List<Document>, onImport: () -> Unit, onExport: (Document) -> Unit, snackbar: SnackbarHostState) {
    var query by rememberSaveable { mutableStateOf("") }
    var section by rememberSaveable { mutableStateOf(LibrarySection.ALL) }
    var sort by rememberSaveable { mutableStateOf(SortOrder.MODIFIED) }
    var grid by rememberSaveable { mutableStateOf(true) }
    var sortMenu by remember { mutableStateOf(false) }
    var createSheet by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf<Document?>(null) }
    var delete by remember { mutableStateOf<Document?>(null) }
    var about by remember { mutableStateOf(false) }
    var accountSheet by remember { mutableStateOf(false) }
    var sharedSheet by remember { mutableStateOf(false) }
    var driveSheet by remember { mutableStateOf(false) }
    val shares by vm.sharesInbox.collectAsStateWithLifecycle()
    val account by vm.account.collectAsStateWithLifecycle()
    LaunchedEffect(account?.uid) { if (account != null) vm.refreshShares() }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val filtered = remember(documents, query, section, sort) {
        documents.filter { doc ->
            (if (section == LibrarySection.TRASH) doc.trashed else !doc.trashed) &&
                (section != LibrarySection.STARRED || doc.starred) &&
                (doc.title.contains(query, true) || doc.content.text.contains(query, true))
        }.let { docs -> when (sort) {
            SortOrder.MODIFIED -> docs.sortedByDescending { it.updatedAt }
            SortOrder.CREATED -> docs.sortedByDescending { it.createdAt }
            SortOrder.NAME -> docs.sortedBy { it.title.lowercase(Locale.ROOT) }
        } }
    }

    ModalNavigationDrawer(drawerState = drawer, drawerContent = {
        ModalDrawerSheet(drawerContainerColor = Color.White) {
            Row(Modifier.padding(28.dp), verticalAlignment = Alignment.CenterVertically) {
                DocumentIcon(); Spacer(Modifier.width(12.dp)); Text("Paper Docs", style = MaterialTheme.typography.headlineSmall)
            }
            Text("YOUR WORKSPACE", Modifier.padding(28.dp, 16.dp), style = MaterialTheme.typography.labelSmall, color = DocsMuted)
            LibrarySection.entries.forEach { item ->
                NavigationDrawerItem(
                    label = { Text(item.label) }, selected = section == item,
                    icon = { Icon(sectionIcon(item), null) }, modifier = Modifier.padding(horizontal = 12.dp),
                    onClick = { section = item; scope.launch { drawer.close() } },
                )
            }
            HorizontalDivider(Modifier.padding(24.dp))
            NavigationDrawerItem(label = { Text("Import a document") }, selected = false, icon = { Icon(Icons.Outlined.FileOpen, null) }, onClick = { scope.launch { drawer.close() }; onImport() }, modifier = Modifier.padding(horizontal = 12.dp))
            NavigationDrawerItem(
                label = { Text("Import from Google Drive") },
                selected = false,
                icon = { Icon(Icons.Outlined.CloudDownload, null) },
                onClick = { scope.launch { drawer.close() }; if (account == null) accountSheet = true else driveSheet = true },
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            NavigationDrawerItem(label = { Text(account?.let { it.name.ifEmpty { it.email } } ?: "Sign in or sync") }, selected = false, icon = { Icon(if (account == null) Icons.Outlined.CloudOff else Icons.Outlined.CloudDone, null) }, onClick = { scope.launch { drawer.close() }; accountSheet = true }, modifier = Modifier.padding(horizontal = 12.dp))
            Spacer(Modifier.weight(1f))
            Row(Modifier.padding(28.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.OfflinePin, null, tint = Color(0xFF188038), modifier = Modifier.size(20.dp))
                Text("  ${if (account == null) "Everything stays on your device" else "Working offline, changes sync later"}", style = MaterialTheme.typography.bodySmall, color = DocsMuted)
            }
        }
    }) {
        Scaffold(
            containerColor = DocsBackground,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                Surface(color = DocsBackground) {
                    Row(Modifier.statusBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp).fillMaxWidth().clip(RoundedCornerShape(32.dp)).background(Color(0xFFEDF2FA)).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Outlined.Menu, "Open navigation") }
                        TextField(
                            value = query, onValueChange = { query = it }, placeholder = { Text("Search your documents", fontSize = 16.sp) },
                            modifier = Modifier.weight(1f), singleLine = true,
                            colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                        )
                        if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, "Clear search") }
                        else AccountAvatar(account, onClick = { if (account == null) about = true else accountSheet = true })
                    }
                }
            },
            floatingActionButton = {
                if (section != LibrarySection.TRASH) ExtendedFloatingActionButton(
                    onClick = { createSheet = true }, icon = { Icon(Icons.Outlined.Add, null) }, text = { Text("New document") },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                )
            },
            bottomBar = {
                NavigationBar(containerColor = Color(0xFFEDF2FA)) {
                    listOf(LibrarySection.ALL, LibrarySection.STARRED, LibrarySection.OFFLINE).forEach { item ->
                        NavigationBarItem(selected = section == item, onClick = { section = item }, icon = { Icon(sectionIcon(item), null) }, label = { Text(if (item == LibrarySection.ALL) "Home" else if (item == LibrarySection.OFFLINE) "Offline" else item.label) })
                    }
                }
            },
        ) { padding ->
            PullToRefreshBox(
                isRefreshing = vm.refreshing,
                onRefresh = vm::syncNow,
                modifier = Modifier.padding(padding).fillMaxSize(),
            ) {
                LazyVerticalGrid(
                    columns = if (grid) GridCells.Adaptive(164.dp) else GridCells.Fixed(1),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 100.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    if (section == LibrarySection.ALL && query.isEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Column(Modifier.padding(top = 16.dp, bottom = 4.dp)) {
                                Text("Your ideas, all together.", style = MaterialTheme.typography.headlineMedium, letterSpacing = (-0.7).sp)
                                Text("A fresh page. A little possibility.", color = DocsMuted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                            }
                        }
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Column {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text("Start something new", fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                    TextButton(onClick = { createSheet = true }) { Text("See all"); Icon(Icons.Outlined.ChevronRight, null, modifier = Modifier.size(18.dp)) }
                                }
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    items(Templates.all.take(4)) { template -> TemplateCard(template) { vm.create(template) } }
                                }
                            }
                        }
                    }
                    if (section == LibrarySection.ALL && shares.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            SharedBanner(shares) { sharedSheet = true }
                        }
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column {
                            Row(Modifier.padding(top = 10.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(if (query.isNotEmpty()) "Search results" else if (section == LibrarySection.ALL) "Recent documents" else section.label, style = MaterialTheme.typography.titleMedium)
                                    Text("${filtered.size} ${if (filtered.size == 1) "document" else "documents"}", color = DocsMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 3.dp))
                                }
                                Box {
                                    IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Outlined.Sort, "Sort documents") }
                                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                        SortOrder.entries.forEach { order -> DropdownMenuItem(text = { Text(order.label) }, trailingIcon = { if (sort == order) Icon(Icons.Outlined.Check, null) }, onClick = { sort = order; sortMenu = false }) }
                                    }
                                }
                                IconButton(onClick = { grid = !grid }) { Icon(if (grid) Icons.AutoMirrored.Outlined.ViewList else Icons.Outlined.GridView, if (grid) "List view" else "Grid view") }
                            }
                            if (section == LibrarySection.OFFLINE) Text("All your documents are available without internet.", color = DocsMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
                            if (section == LibrarySection.TRASH) Text("Documents stay here until you restore or delete them.", color = DocsMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
                        }
                    }
                    if (vm.loading) item(span = { GridItemSpan(maxLineSpan) }) { Box(Modifier.fillMaxWidth().padding(60.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                    else if (filtered.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(Modifier.fillMaxWidth().padding(vertical = 60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(if (query.isNotEmpty()) Icons.Outlined.SearchOff else sectionIcon(section), null, Modifier.size(52.dp), tint = Color(0xFF9CB7DC))
                            Text(if (query.isNotEmpty()) "No matching documents" else if (section == LibrarySection.STARRED) "Keep your favorites close" else if (section == LibrarySection.TRASH) "Your trash is empty" else "Your next idea starts here", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 18.dp))
                            Text(if (query.isNotEmpty()) "Try a different title or phrase." else if (section == LibrarySection.STARRED) "Star a document to find it here." else "Make a little room for something new.", color = DocsMuted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                    items(filtered, key = { it.id }) { document ->
                        DocumentCard(document, grid, cloudEnabled = account != null, onResolveConflict = { vm.promptConflict(document.id) }, onOpen = { if (!document.trashed) vm.open(document) }, onRename = { rename = document }, onStar = { vm.star(document) }, onCopy = { vm.duplicate(document) }, onTrash = { vm.trash(document) }, onRestore = { vm.restore(document) }, onDelete = { delete = document }, onExport = { onExport(document) })
                    }
                }
            }
        }
    }
    if (createSheet) ModalBottomSheet(onDismissRequest = { createSheet = false }, containerColor = Color.White) {
        Text("Make room for an idea", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
        Templates.all.forEach { template ->
            ListItem(headlineContent = { Text(template.name) }, supportingContent = { Text(template.description) }, leadingContent = { DocumentIcon() }, modifier = Modifier.clickable { createSheet = false; vm.create(template) }.padding(horizontal = 8.dp))
        }
        HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
        ListItem(headlineContent = { Text("Import a document") }, supportingContent = { Text("Text, HTML, or Word (.docx)") }, leadingContent = { Icon(Icons.Outlined.FileOpen, null) }, modifier = Modifier.clickable { createSheet = false; onImport() }.padding(horizontal = 8.dp))
        Spacer(Modifier.height(24.dp))
    }
    rename?.let { document -> RenameDialog(document.title, onDismiss = { rename = null }) { vm.rename(document, it); rename = null } }
    delete?.let { document ->
        AlertDialog(onDismissRequest = { delete = null }, title = { Text("Delete forever?") }, text = { Text("“${document.title}”, its versions, its notes, and its cloud copy will be permanently deleted.") }, confirmButton = { TextButton(onClick = { vm.delete(document); delete = null }) { Text("Delete", color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton(onClick = { delete = null }) { Text("Cancel") } })
    }
    if (about) AlertDialog(onDismissRequest = { about = false }, icon = { DocumentIcon() }, title = { Text("Your personal workspace") }, text = { Text("Paper Docs stores your documents on this device. Write offline, organize your ideas, and share exported copies.\n\nVersion 1.0 · Made with Kotlin") }, confirmButton = { TextButton(onClick = { about = false }) { Text("Got it") } })
    if (accountSheet) AccountSheet(vm) { accountSheet = false }
    if (sharedSheet) SharedWithMeSheet(vm, onDismiss = { sharedSheet = false })
    if (driveSheet) DriveBrowserSheet(vm, onDismiss = { driveSheet = false })
}

@Composable
fun DocumentIcon(modifier: Modifier = Modifier) {
    Box(modifier.size(36.dp).clip(RoundedCornerShape(9.dp)).background(Color(0xFFE8F0FE)), contentAlignment = Alignment.Center) {
        Icon(Icons.Outlined.Description, null, tint = DocsBlue, modifier = Modifier.size(24.dp))
    }
}

/**
 * Top-right avatar. Shows the signed-in user's Google photo, their initial when Google has no
 * picture, and the app mark when nobody is signed in.
 */
@Composable
private fun AccountAvatar(account: com.company.paperdocs.cloud.Account?, onClick: () -> Unit) {
    val description = account?.let { "Account: ${it.name}" } ?: "About Paper Docs"
    Box(
        Modifier.padding(end = 10.dp)
            .size(36.dp)
            .clip(CircleShape)
            .background(if (account == null) Color(0xFFD3E3FD) else Color(0xFFE8F0FE))
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        val photo = account?.photoUrl
        if (account != null && !photo.isNullOrEmpty()) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(photo)
                    .crossfade(true)
                    .build(),
                contentDescription = description,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                account?.initial ?: "P",
                color = Color(0xFF174EA6),
                fontWeight = FontWeight.Medium,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun SharedBanner(shares: List<SharedDocument>, onOpen: () -> Unit) {
    Surface(
        color = Color(0xFFE8F0FE), shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp).clickable(onClick = onOpen),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Group, null, tint = DocsBlue)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    if (shares.size == 1) "1 document was shared with you" else "${shares.size} documents were shared with you",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text("Tap to review", style = MaterialTheme.typography.bodySmall, color = DocsMuted)
            }
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = DocsMuted)
        }
    }
}

/** Lists documents other people shared with the signed-in account. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SharedWithMeSheet(vm: DocsViewModel, onDismiss: () -> Unit) {
    val shares by vm.sharesInbox.collectAsStateWithLifecycle()
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Color.White) {
        Column(Modifier.padding(horizontal = 24.dp).navigationBarsPadding()) {
            Text("Shared with you", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Open a copy, or keep it out of your library.",
                style = MaterialTheme.typography.bodySmall, color = DocsMuted, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            if (shares.isEmpty()) {
                Text(
                    "Nothing has been shared with you yet.",
                    style = MaterialTheme.typography.bodyMedium, color = DocsMuted, modifier = Modifier.padding(vertical = 24.dp),
                )
            } else {
                LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(shares, key = { it.ownerUid + it.remoteId }) { shared ->
                        Surface(color = Color(0xFFF7F9FC), shape = RoundedCornerShape(12.dp)) {
                            Column(Modifier.padding(14.dp)) {
                                Text(
                                    shared.title.ifBlank { "Shared document" },
                                    style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                )
                                Text("Shared as ${shared.role.label.lowercase()}", style = MaterialTheme.typography.bodySmall, color = DocsMuted)
                                Row(Modifier.padding(top = 8.dp)) {
                                    Button(onClick = { vm.openShared(shared); onDismiss() }, modifier = Modifier.weight(1f)) { Text("Open") }
                                    Spacer(Modifier.width(8.dp))
                                    TextButton(onClick = { vm.dismissShared(shared) }) { Text("Hide") }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

private fun sectionIcon(section: LibrarySection): ImageVector = when (section) {
    LibrarySection.ALL -> Icons.Outlined.Home
    LibrarySection.STARRED -> Icons.Outlined.StarOutline
    LibrarySection.OFFLINE -> Icons.Outlined.OfflinePin
    LibrarySection.TRASH -> Icons.Outlined.DeleteOutline
}

private fun syncIcon(state: SyncState): ImageVector = when (state) {
    SyncState.SYNCED -> Icons.Outlined.CloudDone
    SyncState.SYNCING -> Icons.Outlined.CloudSync
    SyncState.CONFLICT -> Icons.Outlined.WarningAmber
    SyncState.FAILED -> Icons.Outlined.CloudOff
    SyncState.LOCAL -> Icons.Outlined.OfflinePin
}

private fun syncTint(state: SyncState): Color = when (state) {
    SyncState.SYNCED -> Color(0xFF188038)
    SyncState.CONFLICT, SyncState.FAILED -> Color(0xFFD93025)
    else -> DocsMuted
}

@Composable
private fun TemplateCard(template: DocumentTemplate, onClick: () -> Unit) {
    Column(Modifier.width(132.dp).clickable(onClick = onClick)) {
        Surface(shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, DocsLine), color = Color.White, modifier = Modifier.fillMaxWidth().height(106.dp)) {
            if (template.content.text.isEmpty()) Box(contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Add, null, Modifier.size(36.dp), tint = DocsBlue) }
            else Column(Modifier.padding(16.dp)) {
                Text(template.name, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = DocsBlue)
                Spacer(Modifier.height(9.dp))
                repeat(5) { index -> Box(Modifier.padding(bottom = 5.dp).height(2.dp).fillMaxWidth(if (index % 3 == 2) 0.55f else 1f).background(Color(0xFFE2E6EC))) }
            }
        }
        Text(if (template.content.text.isEmpty()) "Blank" else template.name, Modifier.padding(top = 9.dp), style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

@Composable
private fun DocumentCard(document: Document, grid: Boolean, cloudEnabled: Boolean, onOpen: () -> Unit, onResolveConflict: () -> Unit, onRename: () -> Unit, onStar: () -> Unit, onCopy: () -> Unit, onTrash: () -> Unit, onRestore: () -> Unit, onDelete: () -> Unit, onExport: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Surface(onClick = onOpen, shape = RoundedCornerShape(16.dp), color = Color.White, border = BorderStroke(1.dp, DocsLine)) {
        Column {
            if (grid) Box(Modifier.fillMaxWidth().height(156.dp).background(Color(0xFFEDF2FA)).padding(15.dp, 12.dp, 15.dp, 0.dp)) {
                Surface(color = Color.White, shadowElevation = 1.dp, modifier = Modifier.fillMaxSize(), shape = RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)) {
                    Column(Modifier.padding(13.dp)) {
                        val preview = document.content.text.lines().filter { it.isNotBlank() }
                        Text(preview.firstOrNull() ?: "Untitled", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = DocsInk, maxLines = 2, lineHeight = 14.sp)
                        Spacer(Modifier.height(8.dp))
                        Text(preview.drop(1).joinToString("\n\n"), fontSize = 7.sp, lineHeight = 11.sp, color = DocsMuted, maxLines = 8, overflow = TextOverflow.Clip)
                    }
                }
                if (document.starred) Icon(Icons.Outlined.Star, "Starred", tint = Color(0xFFF9AB00), modifier = Modifier.align(Alignment.TopEnd).size(20.dp))
            }
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!grid) { DocumentIcon(); Spacer(Modifier.width(12.dp)) }
                Column(Modifier.weight(1f)) {
                    Text(document.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(Modifier.padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(syncIcon(document.syncState), "Sync status: ${document.syncState.label}", tint = syncTint(document.syncState), modifier = Modifier.size(12.dp))
                        Text("  ${if (cloudEnabled) document.syncState.label else friendlyDate(document.updatedAt)}", color = syncTint(document.syncState), fontSize = 11.sp)
                        if (document.syncState == SyncState.CONFLICT) {
                            Text(
                                "  Resolve",
                                color = DocsBlue, fontSize = 11.sp,
                                modifier = Modifier.clickable(onClick = onResolveConflict).padding(horizontal = 4.dp),
                            )
                        }
                    }
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More options for ${document.title}", Modifier.size(22.dp)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        fun run(action: () -> Unit) { menu = false; action() }
                        if (document.trashed) {
                            DropdownMenuItem(text = { Text("Restore") }, leadingIcon = { Icon(Icons.Outlined.Restore, null) }, onClick = { run(onRestore) })
                            DropdownMenuItem(text = { Text("Delete forever") }, leadingIcon = { Icon(Icons.Outlined.DeleteForever, null) }, onClick = { run(onDelete) })
                        } else {
                            DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Outlined.Edit, null) }, onClick = { run(onRename) })
                            DropdownMenuItem(text = { Text(if (document.starred) "Remove star" else "Add to starred") }, leadingIcon = { Icon(Icons.Outlined.StarOutline, null) }, onClick = { run(onStar) })
                            DropdownMenuItem(text = { Text("Make a copy") }, leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) }, onClick = { run(onCopy) })
                            DropdownMenuItem(text = { Text("Share & export") }, leadingIcon = { Icon(Icons.Outlined.IosShare, null) }, onClick = { run(onExport) })
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text("Move to trash") }, leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) }, onClick = { run(onTrash) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RenameDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var title by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Rename document") }, text = { OutlinedTextField(value = title, onValueChange = { title = it.take(200) }, label = { Text("Document name") }, singleLine = true) }, confirmButton = { TextButton(onClick = { onConfirm(title) }, enabled = title.isNotBlank()) { Text("Save") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

fun friendlyDate(time: Long): String {
    val days = (System.currentTimeMillis() - time) / 86_400_000
    return when {
        days < 1 -> "Edited today"
        days == 1L -> "Edited yesterday"
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(time))
    }
}
