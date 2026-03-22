package app.pocketmonk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.unit.dp
import app.pocketmonk.ui.theme.Accent
import app.pocketmonk.ui.theme.Border
import app.pocketmonk.ui.theme.Error
import app.pocketmonk.ui.theme.Surface
import app.pocketmonk.ui.theme.TextMuted
import app.pocketmonk.ui.theme.TextPrimary
import app.pocketmonk.ui.theme.TextSecondary

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
