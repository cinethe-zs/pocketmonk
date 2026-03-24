package app.pocketmonk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.pocketmonk.ui.theme.Accent
import app.pocketmonk.ui.theme.Background
import app.pocketmonk.ui.theme.Border
import app.pocketmonk.ui.theme.SurfaceRaised
import app.pocketmonk.ui.theme.TextMuted
import app.pocketmonk.ui.theme.TextPrimary
import app.pocketmonk.ui.theme.TextSecondary

private val PRESETS = listOf(
    "15 min" to 15L * 60 * 1000,
    "30 min" to 30L * 60 * 1000,
    "1 hour" to 60L * 60 * 1000,
    "2 hours" to 2L * 60 * 60 * 1000,
    "Tomorrow" to 24L * 60 * 60 * 1000,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReminderDialog(
    onSchedule: (delayMs: Long, message: String) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedDelayMs by remember { mutableLongStateOf(30L * 60 * 1000) }
    var reminderNote by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceRaised,
        title = {
            Text("Set reminder", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
        },
        text = {
            Column {
                Text("Remind me in:", style = MaterialTheme.typography.labelMedium, color = TextMuted)
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    PRESETS.forEach { (label, delayMs) ->
                        val isSelected = selectedDelayMs == delayMs
                        val bg = if (isSelected) Accent.copy(alpha = 0.15f) else Background
                        val borderColor = if (isSelected) Accent else Border
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isSelected) Accent else TextSecondary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(bg)
                                .border(1.dp, borderColor, RoundedCornerShape(8.dp))
                                .clickable { selectedDelayMs = delayMs }
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = reminderNote,
                    onValueChange = { reminderNote = it },
                    placeholder = { Text("Note (optional)…", color = TextMuted) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent,
                        unfocusedBorderColor = Border,
                        cursorColor = Accent,
                        focusedContainerColor = Background,
                        unfocusedContainerColor = Background
                    )
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val message = reminderNote.ifBlank { "Time to follow up with PocketMonk!" }
                onSchedule(selectedDelayMs, message)
            }) {
                Text("Set reminder", color = Accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        }
    )
}
