package dev.pgapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pgapp.data.Completion
import dev.pgapp.data.SqlKeywords

private val tokenRegex = Regex(
    """(--[^\n]*)|(/\*.*?\*/)|('(?:[^']|'')*')|("(?:[^"]|"")*")|(\b\d+(?:\.\d+)?\b)|(\b[A-Za-z_][A-Za-z0-9_]*\b)""",
    RegexOption.DOT_MATCHES_ALL,
)
private val keywordSet = SqlKeywords.all.toSet()

data class SqlColors(val keyword: Color, val string: Color, val comment: Color, val number: Color, val ident: Color)

fun highlightSql(src: String, c: SqlColors): AnnotatedString = buildAnnotatedString {
    append(src)
    for (m in tokenRegex.findAll(src)) {
        val style = when {
            m.groups[1] != null || m.groups[2] != null -> SpanStyle(color = c.comment, fontStyle = FontStyle.Italic)
            m.groups[3] != null -> SpanStyle(color = c.string)
            m.groups[4] != null -> SpanStyle(color = c.ident)
            m.groups[5] != null -> SpanStyle(color = c.number)
            m.groups[6] != null && m.value.uppercase() in keywordSet -> SpanStyle(color = c.keyword)
            else -> null
        }
        if (style != null) addStyle(style, m.range.first, m.range.last + 1)
    }
}

private class SqlTransformation(private val colors: SqlColors) : VisualTransformation {
    override fun filter(text: AnnotatedString) = TransformedText(highlightSql(text.text, colors), OffsetMapping.Identity)
}

/** Monospace SQL editor with line numbers, syntax highlighting, touch autocomplete chips and Ctrl/Cmd+Enter to run. */
@Composable
fun SqlEditor(
    value: TextFieldValue,
    onChange: (TextFieldValue) -> Unit,
    names: Set<String>,
    modifier: Modifier = Modifier,
    onRun: () -> Unit = {},
) {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val scheme = MaterialTheme.colorScheme
    val colors = remember(dark) {
        if (dark) SqlColors(Color(0xFF7FB2E5), Color(0xFFA5D6A7), Color(0xFF7C8996), Color(0xFFFFCC80), Color(0xFFCE93D8))
        else SqlColors(Color(0xFF1B5E9B), Color(0xFF2E7D32), Color(0xFF8A97A5), Color(0xFFB45F06), Color(0xFF7B1FA2))
    }
    val transformation = remember(colors) { SqlTransformation(colors) }
    val textStyle = TextStyle(fontFamily = Mono, fontSize = 14.sp, lineHeight = 21.sp, color = scheme.onSurface)
    val lines = value.text.count { it == '\n' } + 1
    val suggestion = remember(value.text, value.selection) {
        if (value.selection.collapsed) Completion.suggest(value.text, value.selection.end, names) else null
    }

    Column(modifier.background(scheme.surface)) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val minH = maxHeight
            Row(Modifier.verticalScroll(rememberScrollState()).heightIn(min = minH)) {
                Text(
                    (1..lines).joinToString("\n"),
                    style = textStyle.copy(color = scheme.outline, textAlign = TextAlign.End),
                    modifier = Modifier.width(44.dp).background(scheme.surfaceVariant.copy(alpha = 0.5f)).padding(end = 8.dp, top = 8.dp, bottom = 8.dp),
                )
                Box(Modifier.horizontalScroll(rememberScrollState()).heightIn(min = minH)) {
                    BasicTextField(
                        value = value,
                        onValueChange = onChange,
                        textStyle = textStyle,
                        cursorBrush = SolidColor(scheme.primary),
                        visualTransformation = transformation,
                        softWrap = false,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                        modifier = Modifier.padding(8.dp).widthIn(min = 600.dp).onPreviewKeyEvent { e ->
                            if (e.type == KeyEventType.KeyDown && (e.isCtrlPressed || e.isMetaPressed) && e.key == Key.Enter) {
                                onRun(); true
                            } else false
                        },
                    )
                }
            }
        }
        if (suggestion != null) {
            LazyRow(Modifier.fillMaxWidth().background(scheme.surfaceVariant).padding(horizontal = 6.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(suggestion.options) { opt ->
                    SuggestionChip(
                        onClick = {
                            val text = value.text.replaceRange(suggestion.start, suggestion.end, opt)
                            onChange(TextFieldValue(text, TextRange(suggestion.start + opt.length)))
                        },
                        label = { Text(opt, fontFamily = Mono, fontSize = 12.sp) },
                    )
                }
            }
        }
    }
}
