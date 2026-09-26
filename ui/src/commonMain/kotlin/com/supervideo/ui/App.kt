package com.supervideo.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.supervideo.core.settings.ThemeMode
import com.supervideo.ui.mono.MonoAppBar
import com.supervideo.ui.screens.HomeScreen
import com.supervideo.ui.screens.JobsScreen
import com.supervideo.ui.screens.PreviewScreen
import com.supervideo.ui.screens.SettingsScreen
import com.supervideo.ui.theme.MonoTheme

@Composable
fun App(graph: AppGraph) {
    val settings by graph.settingsStore.settings.collectAsState()
    val lightMode = when (settings.theme) {
        ThemeMode.SYSTEM -> !isSystemInDarkTheme()
        ThemeMode.LIGHT -> true
        ThemeMode.DARK -> false
    }
    val scope = rememberCoroutineScope()
    val state = remember { AppState(graph, scope) }

    MonoTheme(lightMode = lightMode, systemBars = { graph.platformUi.SystemBars(it) }) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize()) {
                BoxWithConstraints {
                    // Phones: smaller title so the three destinations fit next to it.
                    val compact = maxWidth < 600.dp
                    MonoAppBar(
                        title = {
                            Text(
                                "SuperVideo",
                                style = if (compact) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.displayMedium,
                                maxLines = 1,
                            )
                        },
                    ) {
                        Row {
                            for (screen in listOf(Screen.Home, Screen.Jobs, Screen.Settings)) {
                                val selected = state.screen == screen || (screen == Screen.Home && state.screen == Screen.Preview)
                                TextButton(
                                    onClick = { state.screen = screen },
                                    contentPadding = PaddingValues(horizontal = if (compact) 6.dp else 12.dp),
                                ) {
                                    Text(
                                        screen.title,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                        style = if (compact) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.labelLarge,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                ) {
                    when (state.screen) {
                        Screen.Home -> HomeScreen(graph, state)
                        Screen.Preview -> PreviewScreen(graph, state)
                        Screen.Jobs -> JobsScreen(graph)
                        Screen.Settings -> SettingsScreen(graph)
                    }
                }
            }
        }
    }
    graph.platformUi.BackHandler(enabled = state.screen != Screen.Home) { state.screen = Screen.Home }
}
