package app.pocketmonk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.pocketmonk.service.ModelInfo
import app.pocketmonk.service.ModelManager
import app.pocketmonk.ui.theme.Accent
import app.pocketmonk.ui.theme.AccentDim
import app.pocketmonk.ui.theme.Background
import app.pocketmonk.ui.theme.Border
import app.pocketmonk.ui.theme.Error
import app.pocketmonk.ui.theme.Success
import app.pocketmonk.ui.theme.Surface
import app.pocketmonk.ui.theme.SurfaceRaised
import app.pocketmonk.ui.theme.TextMuted
import app.pocketmonk.ui.theme.TextPrimary
import app.pocketmonk.ui.theme.TextSecondary
import java.io.File

@Composable
fun ModelSetupScreen(
    onModelSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val modelManager = remember { ModelManager(context) }
    var availableModels by remember { mutableStateOf(modelManager.listAvailableModels()) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Background)
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Spacer(Modifier.height(48.dp))
                // Header
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Filled.SmartToy,
                        contentDescription = null,
                        tint = Accent,
                        modifier = Modifier.size(56.dp)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "PocketMonk",
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary
                    )
                    Text(
                        text = "Private AI — 100% on-device",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                }
                Spacer(Modifier.height(24.dp))
            }

            item {
                Text(
                    text = "Model Setup",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Download a model from Google AI Edge or Kaggle and place the .bin file in:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(SurfaceRaised)
                        .border(1.dp, Border, RoundedCornerShape(8.dp))
                        .padding(12.dp)
                ) {
                    Text(
                        text = modelManager.modelsDirectory().absolutePath,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = Accent
                    )
                }
            }

            item {
                // Refresh button + found count
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Available Models",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary
                    )
                    IconButton(onClick = {
                        availableModels = modelManager.listAvailableModels()
                    }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = Accent)
                    }
                }
            }

            // Available models found locally
            if (availableModels.isNotEmpty()) {
                items(availableModels) { file ->
                    AvailableModelCard(
                        file = file,
                        isLoading = isLoading,
                        onSelect = {
                            isLoading = true
                            errorMessage = null
                            onModelSelected(file.absolutePath)
                        }
                    )
                }
            } else {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Surface)
                            .border(1.dp, Border, RoundedCornerShape(8.dp))
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No models found. Place a .bin model file in the directory above, then tap Refresh.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextMuted
                        )
                    }
                }
            }

            item {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Supported Models",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "These MediaPipe-compatible models are known to work with PocketMonk:",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }

            items(modelManager.catalog) { info ->
                CatalogModelCard(
                    info = info,
                    isAvailable = modelManager.modelExists(info.filename)
                )
            }

            item { Spacer(Modifier.height(32.dp)) }
        }

        if (errorMessage != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Error)
                    .padding(16.dp)
            ) {
                Text(text = errorMessage ?: "", color = TextPrimary)
            }
        }

        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Background.copy(alpha = 0.7f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Accent)
                    Spacer(Modifier.height(16.dp))
                    Text("Loading model…", color = TextPrimary)
                }
            }
        }
    }
}

@Composable
private fun AvailableModelCard(
    file: File,
    isLoading: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(AccentDim)
            .border(1.dp, Accent, RoundedCornerShape(8.dp))
            .padding(12.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = Success,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = file.name,
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary
            )
            Text(
                text = "%.1f MB".format(file.length() / 1_000_000.0),
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted
            )
        }
        Button(
            onClick = onSelect,
            enabled = !isLoading,
            colors = ButtonDefaults.buttonColors(containerColor = Accent)
        ) {
            Text("Use", color = TextPrimary)
        }
    }
}

@Composable
private fun CatalogModelCard(
    info: ModelInfo,
    isAvailable: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Surface)
            .border(
                width = 1.dp,
                color = if (isAvailable) Success else Border,
                shape = RoundedCornerShape(8.dp)
            )
            .padding(12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = info.name,
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary
            )
            Text(
                text = info.size,
                style = MaterialTheme.typography.labelSmall,
                color = if (isAvailable) Success else TextMuted
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = info.description,
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = info.filename,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = TextMuted
        )
        if (isAvailable) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = "Available locally",
                style = MaterialTheme.typography.labelSmall,
                color = Success
            )
        }
    }
}
