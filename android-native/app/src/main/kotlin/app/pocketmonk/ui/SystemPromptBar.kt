package app.pocketmonk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.pocketmonk.ui.theme.Accent
import app.pocketmonk.ui.theme.Border
import app.pocketmonk.ui.theme.Error
import app.pocketmonk.ui.theme.Surface
import app.pocketmonk.ui.theme.TextMuted
import app.pocketmonk.ui.theme.TextPrimary
import app.pocketmonk.ui.theme.TextSecondary

private val PROMPT_TEMPLATES = listOf(
    "Coding" to "You are an expert software engineer. Write clean, correct code with brief explanations. Prefer concise answers.",
    "Creative" to "You are a creative writing assistant. Be imaginative, descriptive, and engaging. Feel free to take narrative risks.",
    "Concise" to "Reply in as few words as possible. Be direct and omit filler.",
    "Translator" to "You are a translation assistant. Translate every message to English unless the user specifies another language. Preserve tone and nuance.",
    "ELI5" to "Explain everything as if talking to a 5-year-old. Use simple words, short sentences, and relatable analogies.",
    "Socratic" to "Guide me to find answers myself by asking thoughtful questions. Do not give answers directly — help me reason through the problem step by step."
)

@Composable
fun SystemPromptBar(
    initialValue: String,
    hasCustomPrompt: Boolean,
    onSave: (String) -> Unit,
    onCancel: () -> Unit,
    onReset: () -> Unit,
    onRename: () -> Unit,
    modifier: Modifier = Modifier
) {
    var text by rememberSaveable(initialValue) { mutableStateOf(initialValue) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Surface)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = "System Prompt",
            style = MaterialTheme.typography.labelLarge,
            color = TextSecondary
        )
        Spacer(Modifier.height(6.dp))
        // Template chips
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
        ) {
            PROMPT_TEMPLATES.forEach { (label, prompt) ->
                val isActive = text == prompt
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isActive) Accent else TextMuted,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isActive) Accent.copy(alpha = 0.12f) else Surface)
                        .border(1.dp, if (isActive) Accent else Border, RoundedCornerShape(6.dp))
                        .clickable { text = if (isActive) "" else prompt }
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = {
                Text(
                    text = "Enter system prompt…",
                    color = TextMuted
                )
            },
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary),
            minLines = 2,
            maxLines = 5,
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
        Spacer(Modifier.height(6.dp))
        Row(
            horizontalArrangement = Arrangement.End,
            modifier = Modifier.fillMaxWidth()
        ) {
            TextButton(onClick = onRename) {
                Text("Rename", color = TextSecondary)
            }
            Spacer(Modifier.weight(1f))
            if (hasCustomPrompt) {
                TextButton(onClick = onReset) {
                    Text("Reset", color = Error)
                }
                Spacer(Modifier.width(4.dp))
            }
            TextButton(onClick = onCancel) {
                Text("Cancel", color = TextSecondary)
            }
            Spacer(Modifier.width(4.dp))
            TextButton(onClick = { onSave(text) }) {
                Text("Save", color = Accent)
            }
        }
    }
}
