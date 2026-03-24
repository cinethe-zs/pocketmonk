package app.pocketmonk.ui

import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pocketmonk.ui.theme.Accent
import app.pocketmonk.ui.theme.Background
import app.pocketmonk.ui.theme.Border
import app.pocketmonk.ui.theme.Error
import app.pocketmonk.ui.theme.Success
import app.pocketmonk.ui.theme.SurfaceRaised
import app.pocketmonk.ui.theme.TextMuted
import app.pocketmonk.ui.theme.TextPrimary
import app.pocketmonk.ui.theme.TextSecondary
import app.pocketmonk.viewmodel.ChatViewModel
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onNavigateToDownload: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val conversations by viewModel.conversations.collectAsState()
    val currentConversation by viewModel.currentConversation.collectAsState()
    val isGenerating by viewModel.isGenerating.collectAsState()
    val isCompressing by viewModel.isCompressing.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val searchStatus by viewModel.searchStatus.collectAsState()
    val searchLog by viewModel.searchLog.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val modelReady by viewModel.modelReady.collectAsState()
    val streamingText by viewModel.streamingText.collectAsState()
    val documentName by viewModel.documentName.collectAsState()
    val pendingImageUri by viewModel.pendingImageUri.collectAsState()

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Show last crash from previous session if any (clears on dismiss)
    var lastCrash by remember {
        mutableStateOf(app.pocketmonk.PocketMonkApp.getLastCrash(context))
    }

    var inputText by rememberSaveable { mutableStateOf("") }
    var showSystemPromptBar by remember { mutableStateOf(false) }
    var showNewConversationDialog by remember { mutableStateOf(false) }
    var showDocumentDialog by remember { mutableStateOf(false) }
    // 0 = off, 1 = Normal, 2 = Deep, 3 = Super Deep, 4 = 5-Forced, 5 = 10-Forced
    var searchLevel by rememberSaveable { mutableStateOf(0) }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { viewModel.loadDocumentFromUri(it) } }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { viewModel.loadImageFromUri(it) } }

    // Check whether the active model supports vision
    val supportsVision = remember(currentConversation?.modelPath) {
        val filename = currentConversation?.modelPath
            ?.let { java.io.File(it).name } ?: return@remember false
        viewModel.modelManager.catalog.any { it.filename == filename && it.supportsVision }
    }

    val speechLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
        if (!matches.isNullOrEmpty()) {
            inputText = if (inputText.isBlank()) matches[0]
                        else "${inputText.trimEnd()} ${matches[0]}"
        }
    }

    val listState = rememberLazyListState()
    val messages = currentConversation?.messages ?: emptyList()
    val showScrollToBottom by remember { derivedStateOf { listState.canScrollForward } }

    val modelName = remember(currentConversation?.modelPath) {
        val path = currentConversation?.modelPath ?: return@remember ""
        val filename = java.io.File(path).name
        viewModel.modelManager.catalog.find { it.filename == filename }?.name
            ?: filename.substringBeforeLast('.')
    }

    // If no conversation yet and model is still loading, show a loading screen
    if (currentConversation == null && !modelReady) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Background),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(24.dp)
            ) {
                when {
                    lastCrash != null -> {
                        Text(
                            "Previous crash detected:",
                            color = Error,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = lastCrash!!,
                            color = Error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    errorMessage != null -> {
                        Text(
                            text = errorMessage!!,
                            color = Error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = {
                            viewModel.dismissError()
                            onNavigateToDownload()
                        }) {
                            Text("Choose another model")
                        }
                    }
                    else -> {
                        androidx.compose.material3.CircularProgressIndicator(color = Accent)
                        Spacer(Modifier.height(16.dp))
                        Text("Loading model…", color = TextMuted, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        return
    }

    // Clear crash log once we're past loading
    if (lastCrash != null) {
        app.pocketmonk.PocketMonkApp.clearLastCrash(context)
        lastCrash = null
    }

    // Auto-scroll to bottom on new messages
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size)
        }
    }

    // Also scroll when streaming content updates (last message changes)
    val lastMsgContent = messages.lastOrNull()?.content
    LaunchedEffect(lastMsgContent) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ConversationDrawer(
                conversations = conversations,
                currentConversationId = currentConversation?.id,
                onSelect = { viewModel.loadConversation(it) },
                onNew = { showNewConversationDialog = true; scope.launch { drawerState.close() } },
                onDelete = { viewModel.deleteConversation(it) },
                onAddTag = { conv, tag -> viewModel.addTag(conv, tag) },
                onRemoveTag = { conv, tag -> viewModel.removeTag(conv, tag) },
                onClose = { scope.launch { drawerState.close() } }
            )
        },
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Background)
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // Top App Bar
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = currentConversation?.title ?: "PocketMonk",
                                style = MaterialTheme.typography.titleMedium,
                                color = TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Spacer(Modifier.width(8.dp))
                            // Status dot
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(
                                        when {
                                            isGenerating -> Color(0xFFFFC107)
                                            modelReady -> Success
                                            else -> TextMuted
                                        }
                                    )
                            )
                        }
                        if (modelName.isNotBlank()) {
                            Text(
                                text = modelName,
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { scope.launch { drawerState.open() } }) {
                        Icon(Icons.Filled.Menu, contentDescription = "Menu", tint = TextSecondary)
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToDownload) {
                        Icon(Icons.Filled.Download, contentDescription = "Models", tint = TextSecondary)
                    }
                    IconButton(onClick = { showSystemPromptBar = !showSystemPromptBar }) {
                        Icon(Icons.Filled.Tune, contentDescription = "System Prompt", tint = if (showSystemPromptBar) Accent else TextSecondary)
                    }
                    IconButton(onClick = {
                        val exported = viewModel.exportConversation()
                        if (exported.isNotBlank()) {
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, exported)
                                putExtra(Intent.EXTRA_SUBJECT, currentConversation?.title ?: "PocketMonk Conversation")
                            }
                            context.startActivity(Intent.createChooser(shareIntent, "Export conversation"))
                        }
                    }) {
                        Icon(Icons.Filled.Share, contentDescription = "Export", tint = TextSecondary)
                    }
                    IconButton(onClick = { showNewConversationDialog = true }) {
                        Icon(Icons.Filled.Add, contentDescription = "New Chat", tint = TextSecondary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SurfaceRaised
                )
            )

            // System prompt bar (inline, collapsible)
            AnimatedVisibility(
                visible = showSystemPromptBar,
                enter = slideInVertically() + fadeIn(),
                exit = slideOutVertically() + fadeOut()
            ) {
                SystemPromptBar(
                    initialValue = currentConversation?.systemPrompt ?: "",
                    hasCustomPrompt = !currentConversation?.systemPrompt.isNullOrBlank(),
                    onSave = { prompt ->
                        viewModel.setSystemPrompt(prompt.ifBlank { null })
                        showSystemPromptBar = false
                    },
                    onCancel = { showSystemPromptBar = false },
                    onReset = {
                        viewModel.setSystemPrompt(null)
                        showSystemPromptBar = false
                    },
                    onRename = {
                        viewModel.renameConversation()
                        showSystemPromptBar = false
                    }
                )
            }

            // Message list
            val lastAssistantIndex = messages.indexOfLast { it.role == app.pocketmonk.model.MessageRole.ASSISTANT && !it.isSummary }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(messages, key = { _, msg -> msg.id }) { index, message ->
                        if (message.isSummary) {
                            SummaryBanner(
                                summary = message.content,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        } else {
                            MessageBubble(
                                message = message,
                                messageIndex = index,
                                isLastAssistant = index == lastAssistantIndex,
                                isGenerating = isGenerating,
                                streamingText = streamingText,
                                onStar = { viewModel.toggleStarMessage(message.id) },
                                onEdit = { newContent -> viewModel.editMessageAt(index, newContent) },
                                onFork = { viewModel.forkConversationAt(index) },
                                onRegenerate = { viewModel.regenerateLastResponse() },
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                            )
                        }
                    }
                    if (isSearching && searchLog != null) {
                        item(key = "live_search_log") {
                            LiveSearchLogCard(
                                log = searchLog!!,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                            )
                        }
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }

                if (showScrollToBottom) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp)
                    ) {
                        IconButton(
                            onClick = { scope.launch { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size) } },
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(50))
                                .background(SurfaceRaised)
                        ) {
                            Icon(
                                Icons.Filled.KeyboardArrowDown,
                                contentDescription = "Scroll to bottom",
                                tint = Accent,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            // Error banner
            AnimatedVisibility(
                visible = errorMessage != null,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Error)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = errorMessage ?: "",
                        color = TextPrimary,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { viewModel.dismissError() }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = "Dismiss", tint = TextPrimary, modifier = Modifier.size(16.dp))
                    }
                }
            }

            // Context bar
            ContextBar(
                used = viewModel.estimatedTokenCount,
                total = viewModel.contextLength,
                isCompressing = isCompressing,
                canCompress = !isGenerating && (currentConversation?.messages?.count { !it.isSummary } ?: 0) > 4,
                onCompress = { viewModel.compressContext() }
            )

            // Document Q&A dialog
            if (showDocumentDialog) {
                DocumentDialog(
                    onConfirmPaste = { name, text ->
                        viewModel.loadDocument(name, text)
                        showDocumentDialog = false
                    },
                    onPickFile = {
                        filePicker.launch("text/*")
                        showDocumentDialog = false
                    },
                    onDismiss = { showDocumentDialog = false }
                )
            }

            // New conversation dialog
            if (showNewConversationDialog) {
                NewConversationDialog(
                    modelManager = viewModel.modelManager,
                    currentModelPath = viewModel.modelManager.getActiveModelPath(),
                    currentContextSize = currentConversation?.contextSize ?: 2048,
                    currentTemperature = currentConversation?.temperature?.takeIf { it >= 0.1f } ?: 1.0f,
                    onConfirm = { modelPath, contextSize, temperature ->
                        showNewConversationDialog = false
                        viewModel.newConversation(modelPath, contextSize, temperature)
                    },
                    onDismiss = { showNewConversationDialog = false }
                )
            }

            // Chat input bar
            Surface(
                color = SurfaceRaised,
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
            ) {
                Column {
                    // Search level pill — visible when a level is selected or search is running
                    AnimatedVisibility(
                        visible = searchLevel > 0 || isSearching,
                        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                Icons.Filled.Search,
                                contentDescription = null,
                                tint = Accent,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            if (isSearching && searchStatus != null) {
                                Text(
                                    searchStatus!!,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Accent
                                )
                            } else {
                                val (levelLabel, levelDesc) = when (searchLevel) {
                                    1 -> "Normal" to "6 results · reads top 4 pages"
                                    2 -> "Deep" to "model plans queries · sufficiency check"
                                    3 -> "Super Deep" to "reads 5 pages before sufficiency check"
                                    4 -> "5-Forced" to "reads top 5 pages · no sufficiency check"
                                    else -> "10-Forced" to "reads top 10 pages · no sufficiency check"
                                }
                                Text(
                                    "$levelLabel search · $levelDesc · Send to run",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Accent
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            if (!isSearching) {
                                Text(
                                    "✕",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextMuted,
                                    modifier = Modifier.clickable { searchLevel = 0 }
                                )
                            }
                        }
                    }

                    // Image preview pill — visible when an image is attached
                    AnimatedVisibility(
                        visible = pendingImageUri != null,
                        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                        ) {
                            val thumbBitmap = remember(pendingImageUri) {
                                pendingImageUri?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() }
                            }
                            if (thumbBitmap != null) {
                                Image(
                                    bitmap = thumbBitmap.asImageBitmap(),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                )
                                Spacer(Modifier.width(8.dp))
                            } else {
                                Icon(
                                    Icons.Filled.Image,
                                    contentDescription = null,
                                    tint = Accent,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(
                                text = pendingImageUri?.let { java.io.File(it).name } ?: "Image attached",
                                style = MaterialTheme.typography.labelSmall,
                                color = Accent,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "✕",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted,
                                modifier = Modifier.clickable { viewModel.clearPendingImage() }
                            )
                        }
                    }

                    // Document pill — visible when a document is loaded
                    AnimatedVisibility(
                        visible = documentName != null,
                        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                Icons.Filled.AttachFile,
                                contentDescription = null,
                                tint = Accent,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = documentName ?: "",
                                style = MaterialTheme.typography.labelSmall,
                                color = Accent,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "✕",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted,
                                modifier = Modifier.clickable { viewModel.clearDocument() }
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.Bottom,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        OutlinedTextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            placeholder = { Text("Message…", color = TextMuted) },
                            modifier = Modifier.weight(1f),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary),
                            enabled = modelReady && !isCompressing && !isSearching,
                            maxLines = 5,
                            shape = RoundedCornerShape(16.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = if (searchLevel > 0) Accent else Accent,
                                unfocusedBorderColor = if (searchLevel > 0) Accent.copy(alpha = 0.5f) else Border,
                                disabledBorderColor = Border,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary,
                                cursorColor = Accent,
                                focusedContainerColor = Background,
                                unfocusedContainerColor = Background,
                                disabledContainerColor = Background
                            )
                        )
                        Spacer(Modifier.width(4.dp))
                        // Document attach button
                        IconButton(
                            onClick = { if (!isSearching) showDocumentDialog = true },
                            enabled = modelReady && !isGenerating && !isCompressing && !isSearching,
                            modifier = Modifier.size(44.dp)
                        ) {
                            Icon(
                                Icons.Filled.AttachFile,
                                contentDescription = "Load document",
                                tint = if (documentName != null) Accent else TextMuted,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        // Image attach button — only shown when active model supports vision
                        if (supportsVision) {
                            Spacer(Modifier.width(4.dp))
                            IconButton(
                                onClick = { imagePicker.launch("image/*") },
                                enabled = modelReady && !isGenerating && !isCompressing && !isSearching,
                                modifier = Modifier.size(44.dp)
                            ) {
                                Icon(
                                    Icons.Filled.Image,
                                    contentDescription = "Attach image",
                                    tint = if (pendingImageUri != null) Accent else TextMuted,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(Modifier.width(4.dp))
                        // Search level button — cycles 0→1→2→3→0
                        IconButton(
                            onClick = { if (!isGenerating && !isSearching) searchLevel = (searchLevel + 1) % 6 },
                            enabled = modelReady && !isGenerating && !isCompressing && !isSearching,
                            modifier = Modifier.size(44.dp)
                        ) {
                            if (isSearching) {
                                androidx.compose.material3.CircularProgressIndicator(
                                    color = Accent,
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Filled.Search,
                                        contentDescription = "Web search depth",
                                        tint = if (searchLevel > 0) Accent else TextMuted,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    if (searchLevel > 0) {
                                        Text(
                                            "$searchLevel",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontSize = androidx.compose.ui.unit.TextUnit(8f, androidx.compose.ui.unit.TextUnitType.Sp)
                                            ),
                                            color = Accent,
                                            modifier = Modifier
                                                .align(Alignment.BottomEnd)
                                                .padding(bottom = 1.dp, end = 1.dp)
                                        )
                                    }
                                }
                            }
                        }
                        // Mic button
                        IconButton(
                            onClick = {
                                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                                }
                                speechLauncher.launch(intent)
                            },
                            enabled = modelReady && !isGenerating && !isCompressing && !isSearching,
                            modifier = Modifier.size(44.dp)
                        ) {
                            Icon(
                                Icons.Filled.Mic,
                                contentDescription = "Voice input",
                                tint = if (modelReady && !isGenerating && !isCompressing && !isSearching) TextSecondary else TextMuted,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                        // Send / Stop button
                        IconButton(
                            onClick = {
                                if (isGenerating) {
                                    viewModel.stopGeneration()
                                } else if (inputText.isNotBlank() && modelReady) {
                                    val text = inputText.trim()
                                    inputText = ""
                                    val level = searchLevel
                                    searchLevel = 0
                                    if (level > 0) {
                                        viewModel.searchAndSend(text, level)
                                    } else {
                                        viewModel.sendMessage(text)
                                    }
                                }
                            },
                            enabled = modelReady && !isCompressing && !isSearching && (isGenerating || inputText.isNotBlank()),
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    when {
                                        !modelReady || isCompressing || isSearching -> TextMuted
                                        isGenerating -> Error
                                        inputText.isBlank() -> TextMuted
                                        searchLevel > 0 -> Accent
                                        else -> Accent
                                    }
                                )
                        ) {
                            Icon(
                                imageVector = if (isGenerating) Icons.Filled.Stop else Icons.Filled.Send,
                                contentDescription = if (isGenerating) "Stop" else "Send",
                                tint = TextPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}


@Composable
private fun DocumentDialog(
    onConfirmPaste: (name: String, text: String) -> Unit,
    onPickFile: () -> Unit,
    onDismiss: () -> Unit
) {
    var pastedText by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Load document", color = TextPrimary) },
        text = {
            Column {
                Text(
                    "Paste text below or load a .txt file. Up to 8 000 characters will be used as context for your questions.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = pastedText,
                    onValueChange = { pastedText = it },
                    placeholder = { Text("Paste document text here…", color = TextMuted) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 220.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(color = TextPrimary),
                    maxLines = 12,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent,
                        unfocusedBorderColor = Border,
                        focusedContainerColor = Background,
                        unfocusedContainerColor = Background,
                        cursorColor = Accent
                    )
                )
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.TextButton(
                    onClick = onPickFile,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.AttachFile, contentDescription = null, tint = Accent, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Load .txt file instead", color = Accent, style = MaterialTheme.typography.labelMedium)
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = {
                    if (pastedText.isNotBlank()) onConfirmPaste("Pasted text", pastedText.trim())
                    else onDismiss()
                }
            ) { Text("Load", color = Accent) }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextMuted)
            }
        },
        containerColor = SurfaceRaised
    )
}

@Composable
private fun LiveSearchLogCard(log: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .background(Color(0xFF0D1117))
            .border(1.dp, Color(0xFF30363D), androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            androidx.compose.material3.CircularProgressIndicator(
                color = Color(0xFF58A6FF),
                modifier = Modifier.size(10.dp),
                strokeWidth = 1.5.dp
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "Deep search — live research log",
                color = Color(0xFF58A6FF),
                style = MaterialTheme.typography.labelSmall
            )
        }
        Text(
            text = log.lines().drop(1).joinToString("\n").trimStart('\n'),
            color = Color(0xFFE6EDF3),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
        )
    }
}

@Composable
private fun SummaryBanner(summary: String, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .background(app.pocketmonk.ui.theme.SurfaceRaised)
            .border(1.dp, app.pocketmonk.ui.theme.Border, androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Icon(
                Icons.Filled.Summarize,
                contentDescription = null,
                tint = app.pocketmonk.ui.theme.TextMuted,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "Conversation summarized",
                color = app.pocketmonk.ui.theme.TextMuted,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(1f)
            )
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = app.pocketmonk.ui.theme.TextMuted,
                modifier = Modifier.size(16.dp)
            )
        }
        AnimatedVisibility(visible = expanded) {
            Text(
                text = summary,
                color = app.pocketmonk.ui.theme.TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
            )
        }
    }
}
