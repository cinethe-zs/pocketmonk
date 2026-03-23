package app.pocketmonk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.pocketmonk.service.ModelManager
import app.pocketmonk.ui.theme.Accent
import app.pocketmonk.ui.theme.Background
import app.pocketmonk.ui.theme.Border
import app.pocketmonk.ui.theme.SurfaceRaised
import app.pocketmonk.ui.theme.TextMuted
import app.pocketmonk.ui.theme.TextPrimary
import app.pocketmonk.ui.theme.TextSecondary
import java.io.File
import kotlin.math.roundToInt

private val CONTEXT_SIZES = listOf(512, 1024, 2048, 4096)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewConversationDialog(
    modelManager: ModelManager,
    currentModelPath: String?,
    currentContextSize: Int,
    currentTemperature: Float = 1.0f,
    onConfirm: (modelPath: String, contextSize: Int, temperature: Float) -> Unit,
    onDismiss: () -> Unit
) {
    val localFiles = remember { modelManager.listLocalFiles() }
    val catalog = modelManager.catalog

    val initialPath = currentModelPath ?: localFiles.firstOrNull()?.absolutePath ?: ""
    var selectedPath by remember { mutableStateOf(initialPath) }
    var selectedContextSize by remember { mutableIntStateOf(currentContextSize) }
    var selectedTemperature by remember { mutableFloatStateOf(currentTemperature) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceRaised,
        title = {
            Text(
                "New Conversation",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary
            )
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {

                // ── Model ────────────────────────────────────────────────────
                Text("Model", style = MaterialTheme.typography.labelMedium, color = TextMuted)
                Spacer(Modifier.height(8.dp))
                if (localFiles.isEmpty()) {
                    Text("No models found", style = MaterialTheme.typography.bodySmall, color = TextMuted)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        localFiles.forEach { file ->
                            val entry = catalog.find { it.filename == file.name }
                            val name = entry?.name ?: file.nameWithoutExtension
                            val isSelected = file.absolutePath == selectedPath
                            ModelRow(
                                name = name,
                                subtitle = entry?.sizeLabel ?: fileSizeLabel(file),
                                isSelected = isSelected,
                                onClick = { selectedPath = file.absolutePath }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // ── Context size ─────────────────────────────────────────────
                Text("Context Size", style = MaterialTheme.typography.labelMedium, color = TextMuted)
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CONTEXT_SIZES.forEach { size ->
                        ContextSizeChip(
                            label = if (size >= 1024) "${size / 1024}K" else "$size",
                            isSelected = size == selectedContextSize,
                            onClick = { selectedContextSize = size }
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // ── Temperature ──────────────────────────────────────────────
                val tempLabel = when {
                    selectedTemperature < 0.4f -> "Precise"
                    selectedTemperature < 0.8f -> "Balanced"
                    selectedTemperature < 1.3f -> "Creative"
                    else                       -> "Wild"
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Temperature", style = MaterialTheme.typography.labelMedium, color = TextMuted)
                    Text(
                        "${"%.1f".format(selectedTemperature)}  ·  $tempLabel",
                        style = MaterialTheme.typography.labelMedium,
                        color = Accent
                    )
                }
                Spacer(Modifier.height(4.dp))
                Slider(
                    value = selectedTemperature,
                    onValueChange = {
                        // Snap to nearest 0.1
                        selectedTemperature = ((it * 10).roundToInt() / 10f)
                    },
                    valueRange = 0.1f..2.0f,
                    steps = 18,
                    colors = SliderDefaults.colors(
                        thumbColor = Accent,
                        activeTrackColor = Accent,
                        inactiveTrackColor = Border
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("0.1", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                    Text("2.0", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (selectedPath.isNotBlank()) onConfirm(selectedPath, selectedContextSize, selectedTemperature)
                },
                enabled = selectedPath.isNotBlank()
            ) {
                Text("Create", color = Accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        }
    )
}

@Composable
private fun ModelRow(name: String, subtitle: String, isSelected: Boolean, onClick: () -> Unit) {
    val bg = if (isSelected) Accent.copy(alpha = 0.15f) else Background
    val borderColor = if (isSelected) Accent else Border
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextMuted)
        }
    }
}

@Composable
private fun ContextSizeChip(label: String, isSelected: Boolean, onClick: () -> Unit) {
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
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

private fun fileSizeLabel(file: File): String {
    val mb = file.length() / (1024.0 * 1024.0)
    return "%.0f MB".format(mb)
}
