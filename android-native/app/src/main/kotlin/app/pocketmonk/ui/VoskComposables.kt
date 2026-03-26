package app.pocketmonk.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.filled.Close
import app.pocketmonk.service.DownloadState
import app.pocketmonk.service.VoskModelEntry
import app.pocketmonk.service.VoskService
import app.pocketmonk.ui.theme.*

@Composable
internal fun VoskModelCard(
    entry: VoskModelEntry,
    isDownloaded: Boolean,
    downloadState: DownloadState,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onDismissError: () -> Unit,
) {
    val title = "${entry.langLabel} — ${entry.sizeLabel}"
    var showDeleteConfirm by remember { mutableStateOf(false) }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete model?", color = TextPrimary) },
            text = { Text("$title will be removed from the device.", color = TextMuted, fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = { showDeleteConfirm = false; onDelete() }) { Text("Delete", color = Error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel", color = TextSecondary) }
            },
            containerColor = Surface,
        )
    }

    val isDownloading = downloadState is DownloadState.Downloading
    val progress = if (isDownloading) (downloadState as DownloadState.Downloading).progress else 0f
    val isError = downloadState is DownloadState.Error
    val isExtracting = isDownloading && progress >= 0.9f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Surface)
            .border(1.dp, if (isDownloaded) Success.copy(alpha = 0.4f) else Border, RoundedCornerShape(12.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(entry.desc, color = TextMuted, fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp), lineHeight = 16.sp)
            }
            Spacer(Modifier.width(8.dp))
            Text(entry.diskSize, color = TextMuted, fontSize = 11.sp)
        }

        if (isError) {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(Error.copy(alpha = 0.12f)).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.ErrorOutline, null, tint = Error, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text((downloadState as DownloadState.Error).message, color = Error, fontSize = 11.sp,
                    modifier = Modifier.weight(1f))
                IconButton(onClick = onDismissError, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Filled.Close, null, tint = Error, modifier = Modifier.size(14.dp))
                }
            }
        }

        AnimatedVisibility(visible = isDownloading) {
            Column(modifier = Modifier.padding(top = 10.dp)) {
                if (progress < 0f) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                        color = Accent, trackColor = Border)
                } else {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                        color = Accent, trackColor = Border)
                }
                Text(
                    text = when {
                        progress < 0f -> "Connecting…"
                        isExtracting  -> "Extracting…"
                        else          -> "${"%.0f".format(progress * 100)}%"
                    },
                    color = TextMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            when {
                isDownloading -> OutlinedButton(
                    onClick = onCancel,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Error),
                    border = ButtonDefaults.outlinedButtonBorder.copy(brush = SolidColor(Error.copy(alpha = 0.5f))),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                ) { Text("Cancel", fontSize = 12.sp) }

                isDownloaded -> {
                    IconButton(onClick = { showDeleteConfirm = true }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Rounded.DeleteOutline, "Delete", tint = Error, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(4.dp))
                    Box(
                        modifier = Modifier.clip(RoundedCornerShape(8.dp))
                            .background(Success.copy(alpha = 0.15f))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.CheckCircle, null, tint = Success, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Ready", color = Success, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                else -> Button(
                    onClick = onDownload,
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, disabledContainerColor = Border),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    Icon(Icons.Rounded.Download, null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Download", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VoskPrefsCard(
    language: String,
    sizePref: String,
    downloadedLangs: Set<String>,
    downloadedKeys: Set<String>,
    onLanguage: (String) -> Unit,
    onSizePref: (String) -> Unit,
) {
    val langOptions = VoskService.catalog
        .filter { it.language in downloadedLangs }
        .distinctBy { it.language }
        .map { it.language to it.langLabel }

    val sizesForLang = VoskService.catalog
        .filter { it.language == language && it.key in downloadedKeys }
        .map { it.size to it.sizeLabel }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Surface)
            .border(1.dp, Border, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Transcription settings", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)

        if (langOptions.size > 1) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Language", color = TextMuted, fontSize = 11.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()) {
                    langOptions.forEach { (code, label) ->
                        val sel = language == code
                        Box(
                            modifier = Modifier
                                .padding(bottom = 6.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (sel) Accent else Surface)
                                .border(1.dp, if (sel) Accent else Border, RoundedCornerShape(8.dp))
                                .clickable { onLanguage(code) }
                                .padding(horizontal = 14.dp, vertical = 7.dp),
                        ) {
                            Text(label, color = if (sel) Background else TextPrimary, fontSize = 13.sp,
                                fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal)
                        }
                    }
                }
            }
        }

        if (sizesForLang.size > 1) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Model size", color = TextMuted, fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    sizesForLang.forEach { (size, label) ->
                        val sel = sizePref == size
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (sel) Accent else Surface)
                                .border(1.dp, if (sel) Accent else Border, RoundedCornerShape(8.dp))
                                .clickable { onSizePref(size) }
                                .padding(horizontal = 14.dp, vertical = 7.dp),
                        ) {
                            Text(label, color = if (sel) Background else TextPrimary, fontSize = 13.sp,
                                fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal)
                        }
                    }
                }
            }
        }
    }
}
