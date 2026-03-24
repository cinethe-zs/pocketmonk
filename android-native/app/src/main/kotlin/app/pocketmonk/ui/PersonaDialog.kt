package app.pocketmonk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import app.pocketmonk.model.Persona
import app.pocketmonk.ui.theme.Accent
import app.pocketmonk.ui.theme.Background
import app.pocketmonk.ui.theme.Border
import app.pocketmonk.ui.theme.Error
import app.pocketmonk.ui.theme.SurfaceRaised
import app.pocketmonk.ui.theme.TextMuted
import app.pocketmonk.ui.theme.TextPrimary
import app.pocketmonk.ui.theme.TextSecondary

@Composable
fun PersonaDialog(
    personas: List<Persona>,
    onSave: (name: String, systemPrompt: String) -> Unit,
    onDelete: (id: String) -> Unit,
    onDismiss: () -> Unit
) {
    var newName by remember { mutableStateOf("") }
    var newPrompt by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceRaised,
        title = {
            Text("Personas", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {

                // Existing personas
                if (personas.isEmpty()) {
                    Text(
                        "No personas yet. Create one below.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted
                    )
                } else {
                    personas.forEach { persona ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Background)
                                .border(1.dp, Border, RoundedCornerShape(8.dp))
                                .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    persona.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextPrimary
                                )
                                Text(
                                    persona.systemPrompt,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextMuted,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(
                                onClick = { onDelete(persona.id) },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = "Delete persona",
                                    tint = Error,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text("New persona", style = MaterialTheme.typography.labelMedium, color = TextMuted)
                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    placeholder = { Text("Name (e.g. Coding assistant)", color = TextMuted) },
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
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = newPrompt,
                    onValueChange = { newPrompt = it },
                    placeholder = { Text("System prompt…", color = TextMuted) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 100.dp, max = 200.dp),
                    maxLines = 8,
                    textStyle = MaterialTheme.typography.bodySmall.copy(color = TextPrimary),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent,
                        unfocusedBorderColor = Border,
                        cursorColor = Accent,
                        focusedContainerColor = Background,
                        unfocusedContainerColor = Background
                    )
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = {
                            if (newName.isNotBlank() && newPrompt.isNotBlank()) {
                                onSave(newName.trim(), newPrompt.trim())
                                newName = ""
                                newPrompt = ""
                            }
                        },
                        enabled = newName.isNotBlank() && newPrompt.isNotBlank()
                    ) {
                        Text("Save persona", color = if (newName.isNotBlank() && newPrompt.isNotBlank()) Accent else TextMuted)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Done", color = TextSecondary)
            }
        }
    )
}
