package dev.pgapp.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pgapp.data.QueryResult

/** Scrollable data grid: sticky header, row numbers, zebra rows, cell selection, long-press to copy. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ResultGrid(
    result: QueryResult,
    modifier: Modifier = Modifier,
    selectedRow: Int? = null,
    selectedCol: Int? = null,
    onSelect: (row: Int, col: Int) -> Unit = { _, _ -> },
    onCopy: (String) -> Unit = {},
) {
    val hs = rememberScrollState()
    val widths: List<Dp> = remember(result) {
        result.columns.indices.map { i ->
            val body = result.rows.take(60).maxOfOrNull { (it[i] ?: "NULL").length.coerceAtMost(60) } ?: 0
            (maxOf(result.columns[i].length, body) * 8 + 28).coerceIn(84, 380).dp
        }
    }
    val line = MaterialTheme.colorScheme.outlineVariant
    val rowNumWidth = 52.dp

    Column(modifier) {
        Row(
            Modifier.horizontalScroll(hs).background(MaterialTheme.colorScheme.surfaceVariant).height(46.dp),
        ) {
            Box(Modifier.width(rowNumWidth).fillMaxHeight().gridLine(line))
            result.columns.forEachIndexed { i, name ->
                Column(
                    Modifier.width(widths[i]).fillMaxHeight().gridLine(line).padding(horizontal = 8.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(name, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        result.columnTypes.getOrElse(i) { "" }, fontSize = 10.sp, maxLines = 1,
                        color = MaterialTheme.colorScheme.outline, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        LazyColumn(Modifier.weight(1f, fill = true).horizontalScroll(hs)) {
            itemsIndexed(result.rows) { r, row ->
                val zebra = if (r % 2 == 1) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f) else Color.Transparent
                Row(Modifier.height(32.dp).background(if (r == selectedRow) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else zebra)) {
                    Box(Modifier.width(rowNumWidth).fillMaxHeight().gridLine(line), contentAlignment = Alignment.Center) {
                        Text("${r + 1}", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                    }
                    row.forEachIndexed { c, value ->
                        val selected = r == selectedRow && c == selectedCol
                        Box(
                            Modifier.width(widths[c]).fillMaxHeight().gridLine(line)
                                .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent)
                                .combinedClickable(onClick = { onSelect(r, c) }, onLongClick = { onCopy(value ?: "") })
                                .padding(horizontal = 8.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            if (value == null) {
                                Text("[null]", fontSize = 12.sp, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.outline, maxLines = 1)
                            } else {
                                Text(value, fontFamily = Mono, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun Modifier.gridLine(color: Color) = drawBehind {
    drawLine(color, Offset(size.width, 0f), Offset(size.width, size.height), 1f)
    drawLine(color, Offset(0f, size.height), Offset(size.width, size.height), 1f)
}
