package app.pocketmonk.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.pocketmonk.ui.theme.Accent
import app.pocketmonk.ui.theme.AccentDim
import app.pocketmonk.ui.theme.Border
import app.pocketmonk.ui.theme.TextSecondary

private val QUICK_PROMPTS = listOf(
    "Summarize" to "Summarize the following:\n\n",
    "Translate" to "Translate to English:\n\n",
    "Explain" to "Explain this in simple terms:\n\n",
    "Fix grammar" to "Fix grammar and spelling:\n\n",
    "Make shorter" to "Make this shorter:\n\n",
    "Brainstorm" to "Brainstorm ideas about:\n\n",
    "Pros & cons" to "List pros and cons of:\n\n",
    "Step by step" to "Explain step by step how to:\n\n",
)

private val DOCUMENT_PROMPTS = listOf(
    "Summarize this" to "Summarize this document.",
    "Key points" to "What are the key points of this document?",
    "Translate this" to "Translate this document to English.",
    "Explain this" to "Explain this document in simple terms.",
    "Quiz me" to "Quiz me on the content of this document.",
)

@Composable
fun QuickPromptRow(
    onPromptSelected: (String) -> Unit,
    hasDocument: Boolean = false,
    modifier: Modifier = Modifier
) {
    val prompts = if (hasDocument) DOCUMENT_PROMPTS else QUICK_PROMPTS
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        prompts.forEach { (label, prompt) ->
            FilterChip(
                selected = false,
                onClick = { onPromptSelected(prompt) },
                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                shape = RoundedCornerShape(16.dp),
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = AccentDim,
                    labelColor = TextSecondary,
                    selectedContainerColor = AccentDim,
                    selectedLabelColor = Accent
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = false,
                    borderColor = Border,
                    selectedBorderColor = Accent
                )
            )
        }
    }
}
