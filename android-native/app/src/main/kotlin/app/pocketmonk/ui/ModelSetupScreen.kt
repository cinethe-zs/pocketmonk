package app.pocketmonk.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import app.pocketmonk.PocketMonkApp
import app.pocketmonk.service.DownloadState
import app.pocketmonk.service.ModelEntry
import app.pocketmonk.service.WhisperService
import app.pocketmonk.ui.theme.*
import app.pocketmonk.viewmodel.ChatViewModel
import java.io.File

private val WHISPER_LANGUAGES = listOf(
    "auto" to "Auto-detect",
    "en"   to "English",
    "fr"   to "French",
    "es"   to "Spanish",
    "de"   to "German",
    "it"   to "Italian",
    "pt"   to "Portuguese",
    "nl"   to "Dutch",
    "pl"   to "Polish",
    "ru"   to "Russian",
    "uk"   to "Ukrainian",
    "ar"   to "Arabic",
    "zh"   to "Chinese",
    "ja"   to "Japanese",
    "ko"   to "Korean",
)

private val WHISPER_MODEL_PREFS = listOf(
    "auto"    to "Auto",
    "tiny"    to "Tiny",
    "tiny_en" to "Tiny EN",
    "base_en" to "Base EN",
    "base"    to "Base",
)

@Composable
fun ModelSetupScreen(
    viewModel: ChatViewModel,
    onModelReady: (String) -> Unit,
) {
    val downloadState by viewModel.downloadState.collectAsState()
    val whisperTinyDownloadState   by viewModel.whisperTinyDownloadState.collectAsState()
    val whisperTinyEnDownloadState by viewModel.whisperTinyEnDownloadState.collectAsState()
    val whisperBaseEnDownloadState by viewModel.whisperBaseEnDownloadState.collectAsState()
    val whisperDownloadState       by viewModel.whisperDownloadState.collectAsState()
    val whisperLanguage   by viewModel.whisperLanguage.collectAsState()
    val whisperModelPref  by viewModel.whisperModelPref.collectAsState()
    val manager = viewModel.modelManager

    // Reset Done state so re-opening this screen doesn't instantly navigate away
    LaunchedEffect(Unit) {
        viewModel.dismissDownloadError()
        if (downloadState is DownloadState.Done) viewModel.resetDownloadState()
    }

    // Navigate away only when a new download/use completes
    LaunchedEffect(downloadState) {
        if (downloadState is DownloadState.Done) {
            onModelReady((downloadState as DownloadState.Done).modelPath)
        }
    }

    val context = LocalContext.current
    var lastCrash by remember { mutableStateOf(PocketMonkApp.getLastCrash(context)) }

    var hfToken    by remember { mutableStateOf(manager.getHfToken() ?: "") }
    var tokenVisible by remember { mutableStateOf(false) }
    var tokenSaved by remember { mutableStateOf(manager.hasHfToken()) }
    var localFiles by remember { mutableStateOf(manager.listLocalFiles()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(48.dp))

        // ── Crash banner (shown after a native MediaPipe crash) ──────────────
        if (lastCrash != null) {
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Error.copy(alpha = 0.15f))
                    .border(1.dp, Error.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                    .padding(12.dp)
            ) {
                Text(
                    text = lastCrash!!,
                    color = Error,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = {
                        PocketMonkApp.clearLastCrash(context)
                        lastCrash = null
                    },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "Dismiss", tint = Error, modifier = Modifier.size(16.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        // ── Header ──────────────────────────────────────────────────────────

        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(SurfaceRaised)
                .border(1.dp, Border, RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.SelfImprovement, null, tint = Accent, modifier = Modifier.size(34.dp))
        }

        Spacer(Modifier.height(16.dp))

        Text("PocketMonk", color = TextPrimary, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text("Choose a model to get started", color = TextMuted, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))

        Spacer(Modifier.height(28.dp))

        // ── HuggingFace token ────────────────────────────────────────────────

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Surface)
                .border(1.dp, Border, RoundedCornerShape(12.dp))
                .padding(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Key, null, tint = if (tokenSaved) Success else TextMuted, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(8.dp))
                Text("HuggingFace Token", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                if (tokenSaved) Text("Saved ✓", color = Success, fontSize = 11.sp)
            }

            Spacer(Modifier.height(4.dp))

            Text(
                text = "Needed to download Gemma. Get a token at huggingface.co/settings/tokens, " +
                       "then accept the Gemma license on the model page.",
                color = TextMuted,
                fontSize = 11.sp,
                lineHeight = 15.sp,
            )

            Spacer(Modifier.height(10.dp))

            // Inline text field — no dialog or bottom sheet
            OutlinedTextField(
                value = hfToken,
                onValueChange = { hfToken = it; tokenSaved = false },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("hf_...", color = TextMuted, fontSize = 13.sp) },
                singleLine = true,
                visualTransformation = if (tokenVisible) VisualTransformation.None
                                       else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { tokenVisible = !tokenVisible }) {
                        Icon(
                            if (tokenVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            null, tint = TextMuted, modifier = Modifier.size(18.dp),
                        )
                    }
                },
                textStyle = LocalTextStyle.current.copy(color = TextPrimary, fontSize = 13.sp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor      = Accent,
                    unfocusedBorderColor    = Border,
                    focusedContainerColor   = SurfaceRaised,
                    unfocusedContainerColor = SurfaceRaised,
                    cursorColor             = Accent,
                ),
                shape = RoundedCornerShape(8.dp),
            )

            Spacer(Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                if (tokenSaved) {
                    TextButton(
                        onClick = { manager.clearHfToken(); hfToken = ""; tokenSaved = false },
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) {
                        Text("Clear", color = TextMuted, fontSize = 12.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Button(
                    onClick = { manager.saveHfToken(hfToken); tokenSaved = true },
                    enabled = hfToken.isNotBlank() && !tokenSaved,
                    colors = ButtonDefaults.buttonColors(
                        containerColor         = Accent,
                        disabledContainerColor = Border,
                    ),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text("Save token", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── Error banner ─────────────────────────────────────────────────────

        if (downloadState is DownloadState.Error) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Error.copy(alpha = 0.12f))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.ErrorOutline, null, tint = Error, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = (downloadState as DownloadState.Error).message,
                    color = Error,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f),
                    lineHeight = 16.sp,
                )
                IconButton(onClick = { viewModel.dismissDownloadError() }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Rounded.Close, null, tint = Error, modifier = Modifier.size(16.dp))
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        // ── Section header ───────────────────────────────────────────────────

        Text(
            text = "Available models",
            color = TextMuted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp),
        )

        // ── Model list ───────────────────────────────────────────────────────

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(manager.catalog) { entry ->
                ModelCard(
                    entry       = entry,
                    isDownloaded = manager.isDownloaded(entry),
                    downloadState = downloadState,
                    tokenSaved  = tokenSaved,
                    onDownload  = { viewModel.downloadModel(entry) },
                    onCancel    = { viewModel.cancelDownload() },
                    onUse       = { viewModel.useLocalModel(manager.modelFile(entry).absolutePath) },
                    onDelete    = { viewModel.deleteModel(entry) },
                )
            }

            // Extra local files not in catalog
            val catalogNames = manager.catalog.map { it.filename }.toSet()
            val extras = localFiles.filter { it.name !in catalogNames }
            if (extras.isNotEmpty()) {
                item {
                    Text(
                        "Other local files",
                        color = TextMuted,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                items(extras) { f ->
                    LocalFileCard(
                        file = f,
                        onUse = { viewModel.useLocalModel(f.absolutePath) },
                        onDelete = { f.delete(); localFiles = manager.listLocalFiles() },
                    )
                }
            }

            // ── Speech Recognition section ───────────────────────────────
            item {
                Text(
                    text = "Speech Recognition",
                    color = TextMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp, bottom = 6.dp),
                )
            }
            item {
                WhisperModelCard(
                    modelName      = "Whisper Tiny (Q5) — Fast",
                    modelDesc      = "4× faster than Base. Recommended for most uses.",
                    modelSize      = "~31 MB",
                    isDownloaded   = viewModel.whisperService.isTinyModelDownloaded(),
                    downloadState  = whisperTinyDownloadState,
                    onDownload     = { viewModel.downloadWhisperTinyModel() },
                    onCancel       = { viewModel.cancelWhisperTinyDownload() },
                    onDelete       = { viewModel.deleteWhisperTinyModel() },
                    onDismissError = { viewModel.dismissWhisperTinyDownloadError() },
                )
            }
            item {
                Spacer(Modifier.height(6.dp))
                WhisperModelCard(
                    modelName      = "Whisper Tiny EN (Q5) — English fast",
                    modelDesc      = "English-only. Smaller vocab → faster + more accurate for English audio.",
                    modelSize      = "~25 MB",
                    isDownloaded   = viewModel.whisperService.isTinyEnModelDownloaded(),
                    downloadState  = whisperTinyEnDownloadState,
                    onDownload     = { viewModel.downloadWhisperTinyEnModel() },
                    onCancel       = { viewModel.cancelWhisperTinyEnDownload() },
                    onDelete       = { viewModel.deleteWhisperTinyEnModel() },
                    onDismissError = { viewModel.dismissWhisperTinyEnDownloadError() },
                )
            }
            item {
                Spacer(Modifier.height(6.dp))
                WhisperModelCard(
                    modelName      = "Whisper Base EN (Q5) — English quality",
                    modelDesc      = "English-only. Best accuracy for English at moderate speed.",
                    modelSize      = "~42 MB",
                    isDownloaded   = viewModel.whisperService.isBaseEnModelDownloaded(),
                    downloadState  = whisperBaseEnDownloadState,
                    onDownload     = { viewModel.downloadWhisperBaseEnModel() },
                    onCancel       = { viewModel.cancelWhisperBaseEnDownload() },
                    onDelete       = { viewModel.deleteWhisperBaseEnModel() },
                    onDismissError = { viewModel.dismissWhisperBaseEnDownloadError() },
                )
            }
            item {
                Spacer(Modifier.height(6.dp))
                WhisperModelCard(
                    modelName      = "Whisper Base (Q5) — Quality",
                    modelDesc      = "More accurate but ~4× slower than Tiny.",
                    modelSize      = "~57 MB",
                    isDownloaded   = viewModel.whisperService.isBaseModelDownloaded(),
                    downloadState  = whisperDownloadState,
                    onDownload     = { viewModel.downloadWhisperModel() },
                    onCancel       = { viewModel.cancelWhisperDownload() },
                    onDelete       = { viewModel.deleteWhisperModel() },
                    onDismissError = { viewModel.dismissWhisperDownloadError() },
                )
            }

            // ── Transcription preferences ─────────────────────────────────
            if (viewModel.whisperService.isModelDownloaded()) {
                item {
                    Spacer(Modifier.height(12.dp))
                    WhisperPrefsCard(
                        language     = whisperLanguage,
                        modelPref    = whisperModelPref,
                        hasTiny      = viewModel.whisperService.isTinyModelDownloaded(),
                        hasTinyEn    = viewModel.whisperService.isTinyEnModelDownloaded(),
                        hasBaseEn    = viewModel.whisperService.isBaseEnModelDownloaded(),
                        hasBase      = viewModel.whisperService.isBaseModelDownloaded(),
                        onLanguage   = { viewModel.setWhisperLanguage(it) },
                        onModelPref  = { viewModel.setWhisperModelPref(it) },
                    )
                }
            }

            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

// ── Model card ────────────────────────────────────────────────────────────────

@Composable
private fun ModelCard(
    entry: ModelEntry,
    isDownloaded: Boolean,
    downloadState: DownloadState,
    tokenSaved: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onUse: () -> Unit,
    onDelete: () -> Unit,
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete model?", color = TextPrimary) },
            text = { Text("${entry.name} will be removed from the device.", color = TextMuted, fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = { showDeleteConfirm = false; onDelete() }) {
                    Text("Delete", color = Error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = Surface,
            titleContentColor = TextPrimary,
        )
    }

    val isThisDownloading = downloadState is DownloadState.Downloading &&
            (downloadState as DownloadState.Downloading).modelId == entry.id
    val isAnyDownloading  = downloadState is DownloadState.Downloading
    val progress = if (isThisDownloading) (downloadState as DownloadState.Downloading).progress else 0f

    val borderColor = when {
        entry.recommendedForPixel7a -> Accent.copy(alpha = 0.5f)
        isDownloaded                -> Success.copy(alpha = 0.4f)
        else                        -> Border
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Surface)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .padding(14.dp),
    ) {
        // Title row
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(entry.name, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    if (entry.recommendedForPixel7a) {
                        Spacer(Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(AccentDim)
                                .padding(horizontal = 5.dp, vertical = 2.dp),
                        ) {
                            Text("Recommended", color = Accent, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Text(entry.description, color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(entry.sizeLabel, color = TextMuted, fontSize = 11.sp)
        }

        // Progress bar
        AnimatedVisibility(visible = isThisDownloading) {
            Column(modifier = Modifier.padding(top = 10.dp)) {
                if (progress >= 0f) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                        color = Accent,
                        trackColor = Border,
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                        color = Accent,
                        trackColor = Border,
                    )
                }
                Text(
                    text = if (progress < 0f) "Connecting…" else "${"%.0f".format(progress * 100)}%",
                    color = TextMuted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // Action button
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            when {
                isThisDownloading -> OutlinedButton(
                    onClick = onCancel,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Error),
                    border = ButtonDefaults.outlinedButtonBorder.copy(brush = SolidColor(Error.copy(alpha = 0.5f))),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    Text("Cancel", fontSize = 12.sp)
                }

                isDownloaded -> {
                    IconButton(
                        onClick = { showDeleteConfirm = true },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete", tint = Error, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(4.dp))
                    Button(
                        onClick = onUse,
                        colors = ButtonDefaults.buttonColors(containerColor = Success),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                    ) {
                        Icon(Icons.Rounded.CheckCircle, null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Use model", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                else -> Button(
                    onClick = onDownload,
                    enabled = !isAnyDownloading && tokenSaved,
                    colors = ButtonDefaults.buttonColors(
                        containerColor         = Accent,
                        disabledContainerColor = Border,
                    ),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                ) {
                    Icon(Icons.Rounded.Download, null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (!tokenSaved) "Save token first" else "Download",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

// ── Local file card ───────────────────────────────────────────────────────────

@Composable
private fun LocalFileCard(file: File, onUse: () -> Unit, onDelete: () -> Unit) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete file?", color = TextPrimary) },
            text = { Text(file.name, color = TextMuted, fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = { showDeleteConfirm = false; onDelete() }) {
                    Text("Delete", color = Error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = Surface,
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Surface)
            .border(1.dp, Border, RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.FolderOpen, null, tint = TextMuted, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(file.name, color = TextPrimary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${"%.1f".format(file.length() / 1_048_576.0)} MB", color = TextMuted, fontSize = 11.sp)
        }
        Spacer(Modifier.width(4.dp))
        IconButton(onClick = { showDeleteConfirm = true }, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete", tint = Error, modifier = Modifier.size(18.dp))
        }
        TextButton(
            onClick = onUse,
            colors = ButtonDefaults.textButtonColors(contentColor = Accent),
        ) {
            Text("Use", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ── Whisper model card ─────────────────────────────────────────────────────────

@Composable
private fun WhisperModelCard(
    modelName: String,
    modelDesc: String,
    modelSize: String,
    isDownloaded: Boolean,
    downloadState: DownloadState,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onDismissError: () -> Unit,
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete model?", color = TextPrimary) },
            text = {
                Text(
                    "$modelName will be removed from the device.",
                    color = TextMuted, fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(onClick = { showDeleteConfirm = false; onDelete() }) {
                    Text("Delete", color = Error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = Surface,
        )
    }

    val isDownloading = downloadState is DownloadState.Downloading
    val progress = if (isDownloading) (downloadState as DownloadState.Downloading).progress else 0f
    val isError = downloadState is DownloadState.Error

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Surface)
            .border(
                1.dp,
                if (isDownloaded) Success.copy(alpha = 0.4f) else Border,
                RoundedCornerShape(12.dp)
            )
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    modelName,
                    color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold
                )
                Text(
                    modelDesc,
                    color = TextMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp),
                    lineHeight = 16.sp,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(modelSize, color = TextMuted, fontSize = 11.sp)
        }

        if (isError) {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Error.copy(alpha = 0.12f))
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.ErrorOutline, null, tint = Error, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    (downloadState as DownloadState.Error).message,
                    color = Error, fontSize = 11.sp, modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismissError, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Rounded.Close, null, tint = Error, modifier = Modifier.size(14.dp))
                }
            }
        }

        AnimatedVisibility(visible = isDownloading) {
            Column(modifier = Modifier.padding(top = 10.dp)) {
                if (progress >= 0f) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                        color = Accent, trackColor = Border,
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                        color = Accent, trackColor = Border,
                    )
                }
                Text(
                    text = if (progress < 0f) "Connecting…" else "${"%.0f".format(progress * 100)}%",
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
                    border = ButtonDefaults.outlinedButtonBorder.copy(
                        brush = SolidColor(Error.copy(alpha = 0.5f))
                    ),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    Text("Cancel", fontSize = 12.sp)
                }

                isDownloaded -> {
                    IconButton(onClick = { showDeleteConfirm = true }, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Rounded.DeleteOutline, contentDescription = "Delete",
                            tint = Error, modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
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
                    enabled = !isDownloading,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Accent,
                        disabledContainerColor = Border,
                    ),
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

// ── Whisper preferences card ───────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WhisperPrefsCard(
    language: String,
    modelPref: String,
    hasTiny: Boolean,
    hasTinyEn: Boolean,
    hasBaseEn: Boolean,
    hasBase: Boolean,
    onLanguage: (String) -> Unit,
    onModelPref: (String) -> Unit,
) {
    val downloadedModels = buildList {
        add("auto" to "Auto")
        if (hasTiny)   add("tiny"    to "Tiny")
        if (hasTinyEn) add("tiny_en" to "Tiny EN")
        if (hasBaseEn) add("base_en" to "Base EN")
        if (hasBase)   add("base"    to "Base")
    }
    val showModelPref = downloadedModels.size > 2  // more than Auto + one model

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

        // Language picker
        var langExpanded by remember { mutableStateOf(false) }
        val langLabel = WHISPER_LANGUAGES.firstOrNull { it.first == language }?.second ?: "Auto-detect"
        Column {
            Text("Language", color = TextMuted, fontSize = 11.sp)
            Spacer(Modifier.height(4.dp))
            ExposedDropdownMenuBox(expanded = langExpanded, onExpandedChange = { langExpanded = it }) {
                OutlinedTextField(
                    value = langLabel,
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = langExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent,
                        unfocusedBorderColor = Border,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedContainerColor = Background,
                        unfocusedContainerColor = Background,
                        focusedTrailingIconColor = TextMuted,
                        unfocusedTrailingIconColor = TextMuted,
                    ),
                    textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
                    singleLine = true,
                )
                ExposedDropdownMenu(
                    expanded = langExpanded,
                    onDismissRequest = { langExpanded = false },
                    modifier = Modifier.background(Surface),
                ) {
                    WHISPER_LANGUAGES.forEach { (code, label) ->
                        DropdownMenuItem(
                            text = { Text(label, color = TextPrimary, fontSize = 13.sp) },
                            onClick = { onLanguage(code); langExpanded = false },
                            modifier = if (code == language)
                                Modifier.background(Accent.copy(alpha = 0.12f)) else Modifier,
                        )
                    }
                }
            }
            if (language != "auto") {
                Text(
                    "Skips language detection — faster and more accurate for $langLabel.",
                    color = TextMuted, fontSize = 11.sp,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }

        // Model preference (only when multiple models available)
        if (showModelPref) {
            Column {
                Text("Preferred model", color = TextMuted, fontSize = 11.sp)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    downloadedModels.forEach { (pref, label) ->
                        val selected = modelPref == pref
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (selected) Accent else Surface)
                                .border(1.dp, if (selected) Accent else Border, RoundedCornerShape(8.dp))
                                .clickable { onModelPref(pref) }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Text(label, color = if (selected) Background else TextPrimary, fontSize = 12.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                        }
                    }
                }
                if (language == "en" && (hasTinyEn || hasBaseEn)) {
                    Text(
                        "English-only models (EN) are recommended when language is set to English.",
                        color = TextMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp),
                        lineHeight = 15.sp,
                    )
                }
            }
        }
    }
}
