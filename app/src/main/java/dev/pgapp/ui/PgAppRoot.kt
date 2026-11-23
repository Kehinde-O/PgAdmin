package dev.pgapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Root layout skeleton, pgAdmin style:
 *   left  = object browser tree
 *   right = tabbed workspace (query editor on top, results below)
 * Wide screens (unfolded Fold / tablet, >= 840dp) show both panes side by side;
 * narrow screens show the workspace only (tree to be opened as a drawer).
 */
@Composable
fun PgAppRoot() {
    val dark = isSystemInDarkTheme()
    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
                if (maxWidth >= 840.dp) {
                    Row(Modifier.fillMaxSize()) {
                        Pane("Object Browser (TODO)", Modifier.width(300.dp).fillMaxHeight())
                        Column(Modifier.weight(1f).fillMaxHeight()) {
                            Pane("Query Editor (TODO)", Modifier.weight(1f).fillMaxWidth())
                            Pane("Data Output (TODO)", Modifier.weight(1f).fillMaxWidth())
                        }
                    }
                } else {
                    Pane("Workspace (TODO)", Modifier.fillMaxSize())
                }
            }
        }
    }
}

@Composable
private fun Pane(title: String, modifier: Modifier) {
    Box(
        modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
        contentAlignment = Alignment.Center,
    ) { Text(title, style = MaterialTheme.typography.titleMedium) }
}
