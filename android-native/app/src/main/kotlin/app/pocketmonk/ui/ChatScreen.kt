package app.pocketmonk.ui

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    modifier: Modifier = Modifier
) {
    val conversations by viewModel.conversations.collectAsState()
    val currentConversation by viewModel.currentConversation.collectAsState()
    val isGenerating by viewModel.isGenerating.collectAsState()
    val isCompressing by viewModel.isCompressing.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val modelReady by viewModel.modelReady.collectAsState()

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var inputText by rememberSaveable { mutableStateOf("") }
    var showSystemPromptBar by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val messages = currentConversation?.messages ?: emptyList()

    // Auto-scroll to bottom on new messages
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    // Also scroll when streaming content updates (last message changes)
    val lastMsgContent = messages.lastOrNull()?.content
    LaunchedEffect(lastMsgContent) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ConversationDrawer(
                conversations = conversations,
                currentConversationId = currentConversation?.id,
                onSelect = { viewModel.loadConversation(it) },
                onNew = { viewModel.newConversation() },
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
                },
                navigationIcon = {
                    IconButton(onClick = { scope.launch { drawerState.open() } }) {
                        Icon(Icons.Filled.Menu, contentDescription = "Menu", tint = TextSecondary)
                    }
                },
                actions = {
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
                    IconButton(onClick = { viewModel.newConversation() }) {
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
                    }
                )
            }

            // Message list
            val lastAssistantIndex = messages.indexOfLast { it.role == app.pocketmonk.model.MessageRole.ASSISTANT && !it.isSummary }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                itemsIndexed(messages, key = { _, msg -> msg.id }) { index, message ->
                    MessageBubble(
                        message = message,
                        messageIndex = index,
                        isLastAssistant = index == lastAssistantIndex,
                        isGenerating = isGenerating,
                        onStar = { viewModel.toggleStarMessage(message.id) },
                        onEdit = { newContent -> viewModel.editMessageAt(index, newContent) },
                        onFork = { viewModel.forkConversationAt(index) },
                        onRegenerate = { viewModel.regenerateLastResponse() },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
                item { Spacer(Modifier.height(8.dp)) }
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

            // Chat input bar
            Surface(
                color = SurfaceRaised,
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
            ) {
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
                        enabled = modelReady && !isCompressing,
                        maxLines = 5,
                        shape = RoundedCornerShape(16.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Accent,
                            unfocusedBorderColor = Border,
                            disabledBorderColor = Border,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            cursorColor = Accent,
                            focusedContainerColor = Background,
                            unfocusedContainerColor = Background,
                            disabledContainerColor = Background
                        )
                    )
                    Spacer(Modifier.width(8.dp))
                    // Send / Stop button
                    IconButton(
                        onClick = {
                            if (isGenerating) {
                                viewModel.stopGeneration()
                            } else if (inputText.isNotBlank() && modelReady) {
                                val text = inputText.trim()
                                inputText = ""
                                viewModel.sendMessage(text)
                            }
                        },
                        enabled = modelReady && !isCompressing && (isGenerating || inputText.isNotBlank()),
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                when {
                                    !modelReady || isCompressing -> TextMuted
                                    isGenerating -> Error
                                    inputText.isBlank() -> TextMuted
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
