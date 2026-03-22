package app.pocketmonk.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pocketmonk.model.Conversation
import app.pocketmonk.ui.theme.Accent
import app.pocketmonk.ui.theme.AccentDim
import app.pocketmonk.ui.theme.Background
import app.pocketmonk.ui.theme.Border
import app.pocketmonk.ui.theme.Error
import app.pocketmonk.ui.theme.Surface
import app.pocketmonk.ui.theme.SurfaceRaised
import app.pocketmonk.ui.theme.TextMuted
import app.pocketmonk.ui.theme.TextPrimary
import app.pocketmonk.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConversationDrawer(
    conversations: List<Conversation>,
    currentConversationId: String?,
    onSelect: (Conversation) -> Unit,
    onNew: () -> Unit,
    onDelete: (String) -> Unit,
    onAddTag: (Conversation, String) -> Unit,
    onRemoveTag: (Conversation, String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedTag by remember { mutableStateOf<String?>(null) }
    var expandedTagEditorId by remember { mutableStateOf<String?>(null) }

    val allTags = conversations.flatMap { it.tags }.distinct().sorted()

    val filtered = conversations.filter { conv ->
        val matchesSearch = searchQuery.isBlank() ||
            conv.title.contains(searchQuery, ignoreCase = true)
        val matchesTag = selectedTag == null || conv.tags.contains(selectedTag)
        matchesSearch && matchesTag
    }

    Column(
        modifier = modifier
            .widthIn(max = 320.dp)
            .fillMaxHeight()
            .background(Surface)
    ) {
        // Header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceRaised)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.SmartToy,
                contentDescription = null,
                tint = Accent,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "Conversations",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "Close", tint = TextSecondary)
            }
        }

        // New conversation button
        TextButton(
            onClick = {
                onNew()
                onClose()
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = Accent)
                Spacer(Modifier.width(8.dp))
                Text("New conversation", color = Accent)
            }
        }

        // Search
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search conversations…", color = TextMuted) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Accent,
                unfocusedBorderColor = Border,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                cursorColor = Accent,
                focusedContainerColor = SurfaceRaised,
                unfocusedContainerColor = SurfaceRaised
            )
        )

        // Tag filter chips
        if (allTags.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(
                    selected = selectedTag == null,
                    onClick = { selectedTag = null },
                    label = { Text("All", style = MaterialTheme.typography.labelSmall) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = AccentDim,
                        selectedLabelColor = Accent
                    )
                )
                allTags.forEach { tag ->
                    FilterChip(
                        selected = selectedTag == tag,
                        onClick = { selectedTag = if (selectedTag == tag) null else tag },
                        label = { Text(tag, style = MaterialTheme.typography.labelSmall) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AccentDim,
                            selectedLabelColor = Accent
                        )
                    )
                }
            }
        }

        // Conversation list
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(filtered, key = { it.id }) { conv ->
                ConversationItem(
                    conversation = conv,
                    isSelected = conv.id == currentConversationId,
                    isTagEditorExpanded = expandedTagEditorId == conv.id,
                    onSelect = {
                        onSelect(conv)
                        onClose()
                    },
                    onLongPress = {
                        expandedTagEditorId = if (expandedTagEditorId == conv.id) null else conv.id
                    },
                    onDelete = { onDelete(conv.id) },
                    onAddTag = { tag -> onAddTag(conv, tag) },
                    onRemoveTag = { tag -> onRemoveTag(conv, tag) }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationItem(
    conversation: Conversation,
    isSelected: Boolean,
    isTagEditorExpanded: Boolean,
    onSelect: () -> Unit,
    onLongPress: () -> Unit,
    onDelete: () -> Unit,
    onAddTag: (String) -> Unit,
    onRemoveTag: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var newTagText by remember { mutableStateOf("") }
    val dateFormat = remember { SimpleDateFormat("MMM d", Locale.getDefault()) }

    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (isSelected) AccentDim else Background)
                .border(
                    width = if (isSelected) 1.dp else 0.dp,
                    color = if (isSelected) Accent else Border,
                )
                .combinedClickable(
                    onClick = onSelect,
                    onLongClick = onLongPress
                )
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = conversation.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isSelected) Accent else TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = dateFormat.format(Date(conversation.updatedAt)),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                    if (conversation.tags.isNotEmpty()) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.padding(top = 4.dp)
                        ) {
                            conversation.tags.take(3).forEach { tag ->
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(AccentDim)
                                        .padding(horizontal = 4.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = tag,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Accent
                                    )
                                }
                            }
                        }
                    }
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Delete",
                        tint = TextMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // Inline tag editor — no bottom sheet, no dialog
        AnimatedVisibility(visible = isTagEditorExpanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceRaised)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "Tags",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary
                )
                Spacer(Modifier.height(6.dp))

                // Existing tags as removable chips
                if (conversation.tags.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .fillMaxWidth()
                    ) {
                        conversation.tags.forEach { tag ->
                            InputChip(
                                selected = false,
                                onClick = {},
                                label = { Text(tag, style = MaterialTheme.typography.labelSmall) },
                                trailingIcon = {
                                    IconButton(
                                        onClick = { onRemoveTag(tag) },
                                        modifier = Modifier.size(16.dp)
                                    ) {
                                        Icon(
                                            Icons.Filled.Close,
                                            contentDescription = "Remove tag",
                                            modifier = Modifier.size(12.dp)
                                        )
                                    }
                                },
                                colors = InputChipDefaults.inputChipColors(
                                    containerColor = AccentDim,
                                    labelColor = Accent
                                )
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }

                // Add tag inline
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    OutlinedTextField(
                        value = newTagText,
                        onValueChange = { newTagText = it },
                        placeholder = { Text("Add tag…", color = TextMuted, style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.labelSmall.copy(color = TextPrimary),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Accent,
                            unfocusedBorderColor = Border,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            cursorColor = Accent,
                            focusedContainerColor = Surface,
                            unfocusedContainerColor = Surface
                        )
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = {
                            if (newTagText.isNotBlank()) {
                                onAddTag(newTagText.trim())
                                newTagText = ""
                            }
                        }
                    ) {
                        Icon(Icons.Filled.Tag, contentDescription = null, tint = Accent, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add", color = Accent, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}
