package app.pocketmonk.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pocketmonk.ui.theme.Accent
import app.pocketmonk.ui.theme.SurfaceRaised
import app.pocketmonk.ui.theme.TextPrimary
import app.pocketmonk.ui.theme.TextSecondary

private sealed class MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock()
    data class CodeBlock(val code: String) : MdBlock()
    data class BulletItem(val text: String) : MdBlock()
    data class NumberedItem(val number: Int, val text: String) : MdBlock()
    data class BlockQuote(val text: String) : MdBlock()
    data class Paragraph(val text: String) : MdBlock()
    object Gap : MdBlock()
}

@Composable
fun MarkdownContent(text: String, modifier: Modifier = Modifier) {
    val blocks = parseBlocks(text)
    Column(modifier = modifier) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> {
                    val (fontSize, weight) = when (block.level) {
                        1 -> 20.sp to FontWeight.Bold
                        2 -> 17.sp to FontWeight.Bold
                        else -> 15.sp to FontWeight.SemiBold
                    }
                    Text(
                        text = inlineSpans(block.text),
                        fontSize = fontSize,
                        fontWeight = weight,
                        color = TextPrimary,
                        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                    )
                }

                is MdBlock.CodeBlock -> {
                    val clipboard = LocalClipboardManager.current
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(SurfaceRaised)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 0.dp)
                        ) {
                            Text(
                                text = "code",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = TextSecondary,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { clipboard.setText(AnnotatedString(block.code)) },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    Icons.Filled.ContentCopy,
                                    contentDescription = "Copy code",
                                    tint = TextSecondary,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(start = 10.dp, end = 10.dp, bottom = 10.dp, top = 4.dp)
                        ) {
                            Text(
                                text = block.code,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = Accent,
                                lineHeight = 18.sp
                            )
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                }

                is MdBlock.BulletItem -> {
                    Row(modifier = Modifier.padding(bottom = 2.dp)) {
                        Text("• ", color = Accent, fontSize = 14.sp)
                        Text(
                            text = inlineSpans(block.text),
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary
                        )
                    }
                }

                is MdBlock.NumberedItem -> {
                    Row(modifier = Modifier.padding(bottom = 2.dp)) {
                        Text("${block.number}. ", color = Accent, fontSize = 14.sp)
                        Text(
                            text = inlineSpans(block.text),
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary
                        )
                    }
                }

                is MdBlock.BlockQuote -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min)
                            .padding(bottom = 2.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .fillMaxHeight()
                                .background(Accent.copy(alpha = 0.5f))
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = inlineSpans(block.text),
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                is MdBlock.Paragraph -> {
                    Text(
                        text = inlineSpans(block.text),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary
                    )
                }

                MdBlock.Gap -> Spacer(Modifier.height(6.dp))
            }
        }
    }
}

private fun parseBlocks(text: String): List<MdBlock> {
    val result = mutableListOf<MdBlock>()
    val lines = text.split('\n')
    var i = 0

    while (i < lines.size) {
        val line = lines[i]

        // Fenced code block
        if (line.trimStart().startsWith("```")) {
            val codeLines = mutableListOf<String>()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                codeLines.add(lines[i])
                i++
            }
            if (codeLines.isNotEmpty()) {
                result.add(MdBlock.CodeBlock(codeLines.joinToString("\n")))
            }
            i++ // skip closing ```
            continue
        }

        // Heading
        val headingMatch = Regex("^(#{1,6})\\s+(.+)").find(line)
        if (headingMatch != null) {
            result.add(MdBlock.Heading(headingMatch.groupValues[1].length, headingMatch.groupValues[2].trim()))
            i++
            continue
        }

        // Bullet list
        val bulletMatch = Regex("^\\s*[-*+]\\s+(.*)").find(line)
        if (bulletMatch != null) {
            result.add(MdBlock.BulletItem(bulletMatch.groupValues[1]))
            i++
            continue
        }

        // Numbered list
        val numberedMatch = Regex("^\\s*(\\d+)\\.\\s+(.*)").find(line)
        if (numberedMatch != null) {
            result.add(MdBlock.NumberedItem(numberedMatch.groupValues[1].toInt(), numberedMatch.groupValues[2]))
            i++
            continue
        }

        // Blockquote
        if (line.startsWith(">")) {
            result.add(MdBlock.BlockQuote(line.removePrefix(">").trimStart()))
            i++
            continue
        }

        // Blank line
        if (line.isBlank()) {
            if (result.lastOrNull() != MdBlock.Gap) result.add(MdBlock.Gap)
            i++
            continue
        }

        // Regular paragraph line
        result.add(MdBlock.Paragraph(line))
        i++
    }

    return result
}

private fun inlineSpans(text: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        // Bold **text** or __text__
        if (i + 1 < text.length && ((text[i] == '*' && text[i + 1] == '*') || (text[i] == '_' && text[i + 1] == '_'))) {
            val marker = text.substring(i, i + 2)
            val end = text.indexOf(marker, i + 2)
            if (end != -1) {
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                append(text.substring(i + 2, end))
                pop()
                i = end + 2
                continue
            }
        }
        // Italic *text* (single, not double)
        if (text[i] == '*' && (i + 1 >= text.length || text[i + 1] != '*')) {
            val end = text.indexOf('*', i + 1)
            if (end != -1 && (end + 1 >= text.length || text[end + 1] != '*')) {
                pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                append(text.substring(i + 1, end))
                pop()
                i = end + 1
                continue
            }
        }
        // Italic _text_ (single, not double)
        if (text[i] == '_' && (i + 1 >= text.length || text[i + 1] != '_')) {
            val end = text.indexOf('_', i + 1)
            if (end != -1 && (end + 1 >= text.length || text[end + 1] != '_')) {
                pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                append(text.substring(i + 1, end))
                pop()
                i = end + 1
                continue
            }
        }
        // Inline code `text`
        if (text[i] == '`') {
            val end = text.indexOf('`', i + 1)
            if (end != -1) {
                pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = SurfaceRaised, color = Accent))
                append(text.substring(i + 1, end))
                pop()
                i = end + 1
                continue
            }
        }
        append(text[i])
        i++
    }
}
