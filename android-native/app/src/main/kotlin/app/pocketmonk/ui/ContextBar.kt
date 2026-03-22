package app.pocketmonk.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.pocketmonk.ui.theme.Accent
import app.pocketmonk.ui.theme.Error
import app.pocketmonk.ui.theme.Success
import app.pocketmonk.ui.theme.TextMuted

@Composable
fun ContextBar(
    used: Int,
    total: Int,
    isCompressing: Boolean,
    canCompress: Boolean,
    onCompress: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ratio = if (total > 0) used.toFloat() / total else 0f
    val show = ratio >= 0.1f || isCompressing

    AnimatedVisibility(
        visible = show,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Context: $used / $total tokens",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isCompressing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = Accent,
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Compressing…",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted
                        )
                    } else if (canCompress && ratio > 0.5f) {
                        FilterChip(
                            selected = false,
                            onClick = onCompress,
                            label = {
                                Text(
                                    text = "Compress",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                labelColor = Accent
                            )
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            val barColor = when {
                ratio > 0.85f -> Error
                ratio > 0.6f -> androidx.compose.ui.graphics.Color(0xFFFFC107)
                else -> Success
            }
            LinearProgressIndicator(
                progress = { ratio.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp),
                color = barColor,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }
    }
}
