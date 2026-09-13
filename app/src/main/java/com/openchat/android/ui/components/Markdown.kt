package com.openchat.android.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Lightweight, deterministic Markdown renderer for assistant messages.
 * Supports: fenced code blocks (``` with optional language), headings (#/##/###),
 * unordered lists ("- "), inline **bold**, *italic* and `code`, blank-line spacing.
 * Implemented with a simple regex/line scanner — no external dependency.
 */

/** Inline token pattern: **bold**, *italic*, `code` (order matters: ** first). */
private val InlineToken = Regex("""(\*\*[^*]+\*\*|\*[^*\n]+?\*|`[^`]+`)""")

/** Renders [markdown] as a column of styled blocks. */
@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier) {
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    val body = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface)

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val lines = markdown.replace("\r\n", "\n").replace("\r", "\n").split("\n")
        var i = 0
        while (i < lines.size) {
            val raw = lines[i]
            val trimmed = raw.trim()
            if (trimmed.startsWith("```")) {
                val language = trimmed.removePrefix("```").trim().ifEmpty { null }
                val buf = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    buf.append(lines[i])
                    buf.append('\n')
                    i++
                }
                if (i < lines.size) i++ // skip closing fence
                CodeBlock(buf.toString().trimEnd('\n'), language)
            } else when {
                trimmed.isEmpty() -> Spacer(Modifier.height(8.dp))
                trimmed.startsWith("### ") -> Heading(
                    trimmed.removePrefix("### "),
                    MaterialTheme.typography.titleSmall,
                    codeBackground,
                )
                trimmed.startsWith("## ") -> Heading(
                    trimmed.removePrefix("## "),
                    MaterialTheme.typography.titleMedium,
                    codeBackground,
                )
                trimmed.startsWith("# ") -> Heading(
                    trimmed.removePrefix("# "),
                    MaterialTheme.typography.titleLarge,
                    codeBackground,
                )
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                    val content = trimmed.substring(2).trim()
                    Row(Modifier.fillMaxWidth()) {
                        Text("•", style = body, modifier = Modifier.padding(end = 6.dp))
                        Text(inlineMarkdown(content, codeBackground), style = body)
                    }
                }
                trimmed.startsWith("> ") -> Text(
                    inlineMarkdown(trimmed.removePrefix("> "), codeBackground),
                    style = body.copy(fontStyle = FontStyle.Italic),
                    modifier = Modifier.padding(start = 8.dp),
                )
                else -> Text(inlineMarkdown(trimmed, codeBackground), style = body)
            }
            i++
        }
    }
}

@Composable
private fun Heading(text: String, style: TextStyle, codeBackground: Color) {
    Text(
        inlineMarkdown(text, codeBackground),
        style = style.copy(color = MaterialTheme.colorScheme.onBackground),
        modifier = Modifier.padding(top = 6.dp),
    )
}

/**
 * Converts one line with **bold** / *italic* / `code` markers into an [AnnotatedString].
 * Unknown/malformed markers are rendered literally (robust behavior).
 */
fun inlineMarkdown(text: String, codeBackground: Color): AnnotatedString = buildAnnotatedString {
    var last = 0
    for (m in InlineToken.findAll(text)) {
        append(text.substring(last, m.range.first))
        val token = m.value
        when {
            token.startsWith("**") && token.length > 4 -> {
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                append(token.substring(2, token.length - 2))
                pop()
            }
            token.startsWith("`") && token.length > 2 -> {
                pushStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = codeBackground,
                    )
                )
                append(token.substring(1, token.length - 1))
                pop()
            }
            token.startsWith("*") && token.length > 2 -> {
                pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                append(token.substring(1, token.length - 1))
                pop()
            }
            else -> append(token)
        }
        last = m.range.last + 1
    }
    if (last < text.length) append(text.substring(last))
}

/** Dark monospace code block with horizontal scroll and a copy button. */
@Composable
private fun CodeBlock(code: String, language: String?) {
    Surface(
        color = Color(0xFF101216),
        contentColor = Color(0xFFE6E9EF),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Box {
            Column(Modifier.padding(10.dp)) {
                if (!language.isNullOrEmpty()) {
                    Text(
                        language,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF8FA3BF),
                    )
                    Spacer(Modifier.height(4.dp))
                }
                SelectionContainer {
                    Text(
                        code,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        softWrap = false,
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(end = 36.dp),
                    )
                }
            }
            CopyIconButton(
                code,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp),
                tint = Color(0xFF8FA3BF),
            )
        }
    }
}
