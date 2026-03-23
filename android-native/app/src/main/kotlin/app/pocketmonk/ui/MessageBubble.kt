package app.pocketmonk.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.pocketmonk.model.Message
import app.pocketmonk.model.MessageRole
import app.pocketmonk.model.MessageStatus
import app.pocketmonk.ui.theme.Accent
import app.pocketmonk.ui.theme.AccentDim
import app.pocketmonk.ui.theme.AiBubble
import app.pocketmonk.ui.theme.Border
import app.pocketmonk.ui.theme.Error
import app.pocketmonk.ui.theme.SurfaceRaised
import app.pocketmonk.ui.theme.TextMuted
import app.pocketmonk.ui.theme.TextPrimary
import app.pocketmonk.ui.theme.TextSecondary
import app.pocketmonk.ui.theme.UserBubble
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageBubble(
    message: Message,
    messageIndex: Int,
    isLastAssistant: Boolean,
    isGenerating: Boolean,
    streamingText: String,
    onStar: () -> Unit,
    onEdit: (String) -> Unit,
    onFork: () -> Unit,
    onRegenerate: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (message.isSummary) {
        SummaryCard(message = message, modifier = modifier)
        return
    }

    if (message.isSearchResult) {
        SearchResultCard(message = message, modifier = modifier)
        return
    }

    if (message.isSearchLog) {
        SearchLogCard(message = message, modifier = modifier)
        return
    }

    if (message.role == MessageRole.USER) {
        UserBubble(
            message = message,
            messageIndex = messageIndex,
            onStar = onStar,
            onEdit = onEdit,
            onFork = onFork,
            modifier = modifier
        )
    } else {
        AssistantBubble(
            message = message,
            isLastAssistant = isLastAssistant,
            isGenerating = isGenerating,
            streamingText = if (isLastAssistant && message.status == MessageStatus.STREAMING) streamingText else "",
            onStar = onStar,
            onRegenerate = onRegenerate,
            modifier = modifier
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UserBubble(
    message: Message,
    messageIndex: Int,
    onStar: () -> Unit,
    onEdit: (String) -> Unit,
    onFork: () -> Unit,
    modifier: Modifier = Modifier
) {
    val clipboard = LocalClipboardManager.current
    var showSheet by remember { mutableStateOf(false) }
    var isEditing by rememberSaveable { mutableStateOf(false) }
    var editText by rememberSaveable(message.content) { mutableStateOf(message.content) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    Column(
        horizontalAlignment = Alignment.End,
        modifier = modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp))
                .background(UserBubble)
                .border(1.dp, Border, RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp))
                .clickable { showSheet = true }
                .padding(12.dp)
        ) {
            if (isEditing) {
                Column {
                    OutlinedTextField(
                        value = editText,
                        onValueChange = { editText = it },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Accent,
                            unfocusedBorderColor = Border,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            cursorColor = Accent,
                            focusedContainerColor = UserBubble,
                            unfocusedContainerColor = UserBubble
                        )
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = {
                            isEditing = false
                            editText = message.content
                        }) {
                            Text("Cancel", color = TextSecondary)
                        }
                        Spacer(Modifier.width(4.dp))
                        TextButton(onClick = {
                            isEditing = false
                            onEdit(editText)
                        }) {
                            Text("Save", color = Accent)
                        }
                    }
                }
            } else {
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary
                )
            }
        }
        if (message.starred) {
            Icon(
                imageVector = Icons.Filled.Star,
                contentDescription = "Starred",
                tint = Color(0xFFFFC107),
                modifier = Modifier
                    .size(16.dp)
                    .padding(top = 4.dp)
            )
        }
    }

    // Bottom sheet — NO TextField inside
    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            sheetState = sheetState,
            containerColor = SurfaceRaised
        ) {
            Column(modifier = Modifier.padding(bottom = 24.dp)) {
                // Star
                TextButton(
                    onClick = {
                        scope.launch { sheetState.hide() }.invokeOnCompletion { showSheet = false }
                        onStar()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (message.starred) Icons.Filled.Star else Icons.Filled.StarBorder,
                            contentDescription = null,
                            tint = if (message.starred) Color(0xFFFFC107) else TextSecondary
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            if (message.starred) "Unstar" else "Star",
                            color = TextPrimary
                        )
                    }
                }
                // Edit — triggers inline edit mode, NOT a dialog
                TextButton(
                    onClick = {
                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                            showSheet = false
                            isEditing = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Edit, contentDescription = null, tint = TextSecondary)
                        Spacer(Modifier.width(12.dp))
                        Text("Edit", color = TextPrimary)
                    }
                }
                // Copy
                TextButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(message.content))
                        scope.launch { sheetState.hide() }.invokeOnCompletion { showSheet = false }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = null, tint = TextSecondary)
                        Spacer(Modifier.width(12.dp))
                        Text("Copy", color = TextPrimary)
                    }
                }
                // Fork
                TextButton(
                    onClick = {
                        scope.launch { sheetState.hide() }.invokeOnCompletion { showSheet = false }
                        onFork()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.AutoFixHigh, contentDescription = null, tint = TextSecondary)
                        Spacer(Modifier.width(12.dp))
                        Text("Fork from here", color = TextPrimary)
                    }
                }
            }
        }
    }
}

@Composable
private fun AssistantBubble(
    message: Message,
    isLastAssistant: Boolean,
    isGenerating: Boolean,
    streamingText: String,
    onStar: () -> Unit,
    onRegenerate: () -> Unit,
    modifier: Modifier = Modifier
) {
    val clipboard = LocalClipboardManager.current
    val isStreaming = message.status == MessageStatus.STREAMING
    val displayText = if (isStreaming && streamingText.isNotEmpty()) streamingText else message.content

    Column(
        horizontalAlignment = Alignment.Start,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                imageVector = Icons.Filled.SmartToy,
                contentDescription = "AI",
                tint = Accent,
                modifier = Modifier
                    .size(24.dp)
                    .padding(top = 4.dp)
            )
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .clip(RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp))
                    .background(AiBubble)
                    .border(1.dp, Border, RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp))
                    .padding(12.dp)
            ) {
                if (isStreaming && displayText.isEmpty()) {
                    StreamingDots()
                } else {
                    MarkdownContent(text = displayText)
                }
            }
        }

        if (!isStreaming) {
            Row(
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 32.dp, top = 2.dp)
            ) {
                IconButton(onClick = onStar, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = if (message.starred) Icons.Filled.Star else Icons.Filled.StarBorder,
                        contentDescription = if (message.starred) "Unstar" else "Star",
                        tint = if (message.starred) Color(0xFFFFC107) else TextMuted,
                        modifier = Modifier.size(16.dp)
                    )
                }
                IconButton(
                    onClick = { clipboard.setText(AnnotatedString(message.content)) },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.ContentCopy,
                        contentDescription = "Copy",
                        tint = TextMuted,
                        modifier = Modifier.size(16.dp)
                    )
                }
                if (isLastAssistant && !isGenerating) {
                    IconButton(onClick = onRegenerate, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Regenerate",
                            tint = TextMuted,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StreamingDots() {
    val transition = rememberInfiniteTransition(label = "dots")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )
    val dot1Alpha = if (phase < 0.33f) 1f else if (phase < 0.66f) 0.3f else 0.6f
    val dot2Alpha = if (phase < 0.33f) 0.3f else if (phase < 0.66f) 1f else 0.3f
    val dot3Alpha = if (phase < 0.33f) 0.6f else if (phase < 0.66f) 0.3f else 1f

    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(50))
                .background(Accent.copy(alpha = dot1Alpha))
        )
        Box(
            Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(50))
                .background(Accent.copy(alpha = dot2Alpha))
        )
        Box(
            Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(50))
                .background(Accent.copy(alpha = dot3Alpha))
        )
    }
}

@Composable
private fun SearchResultCard(message: Message, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }

    // Extract the query from first line "[Web search results for "query":]"
    val firstLine = message.content.lines().firstOrNull() ?: ""
    val queryLabel = firstLine.removePrefix("[").removeSuffix(":]").trim()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(SurfaceRaised)
            .border(1.dp, Border, RoundedCornerShape(8.dp))
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Icon(
                Icons.Filled.Search,
                contentDescription = null,
                tint = TextMuted,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                queryLabel,
                color = TextMuted,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(1f)
            )
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = TextMuted,
                modifier = Modifier.size(16.dp)
            )
        }
        if (expanded) {
            Text(
                text = message.content.lines().drop(1).joinToString("\n").trim(),
                color = TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
            )
        }
    }
}

@Composable
private fun SearchLogCard(message: Message, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(true) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF0D1117))
            .border(1.dp, Color(0xFF30363D), RoundedCornerShape(8.dp))
    ) {
        // Header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Icon(
                Icons.Filled.Search,
                contentDescription = null,
                tint = Color(0xFF58A6FF),
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "Mega Deep — research log",
                color = Color(0xFF58A6FF),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = Color(0xFF8B949E),
                modifier = Modifier.size(16.dp)
            )
        }
        // Log content
        if (expanded) {
            Text(
                text = message.content.lines().drop(1).joinToString("\n").trimStart('\n'),
                color = Color(0xFFE6EDF3),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
            )
        }
    }
}

@Composable
private fun SummaryCard(
    message: Message,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(AccentDim)
            .border(1.dp, Accent, RoundedCornerShape(8.dp))
            .clickable { expanded = !expanded }
            .padding(12.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Summarize,
                    contentDescription = null,
                    tint = Accent,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Context compressed",
                    style = MaterialTheme.typography.labelMedium,
                    color = Accent
                )
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }
    }
}

