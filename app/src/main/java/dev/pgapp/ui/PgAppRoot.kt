package dev.pgapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Root layout, pgAdmin style:
 *   left  = server list / object browser
 *   right = tabbed workspace (query editor on top, results below)
 * Wide screens (unfolded Fold / tablet, >= 840dp) show both panes side by side;
 * narrow screens show the server list only (workspace arrives with the query tool).
 */
@Composable
fun PgAppRoot(authenticate: suspend (String) -> Boolean) {
    val vm: ServersViewModel = viewModel()
    val dark = isSystemInDarkTheme()
    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
                if (maxWidth >= 840.dp) {
                    Row(Modifier.fillMaxSize()) {
                        ServerListPane(vm, authenticate, Modifier.width(360.dp).fillMaxHeight())
                        Column(Modifier.weight(1f).fillMaxHeight()) {
                            Pane("Query Editor (M3)", Modifier.weight(1f).fillMaxWidth())
                            Pane("Data Output (M3)", Modifier.weight(1f).fillMaxWidth())
                        }
                    }
                } else {
                    ServerListPane(vm, authenticate, Modifier.fillMaxSize())
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
