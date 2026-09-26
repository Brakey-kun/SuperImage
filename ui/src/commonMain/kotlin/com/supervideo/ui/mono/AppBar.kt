package com.supervideo.ui.mono

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.supervideo.ui.theme.border
import com.supervideo.ui.theme.spacing
import com.supervideo.ui.utils.RowSpacer

@Composable
fun MonoAppBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    windowInsets: WindowInsets = MonoAppBarDefaults.windowInsets,
    leadingIcon: @Composable () -> Unit = { },
    trailingIcons: @Composable RowScope.() -> Unit = { }
) = Row(
    modifier = modifier
        .background(MaterialTheme.colorScheme.primaryContainer)
        .windowInsetsPadding(windowInsets)
        .fillMaxWidth()
        .drawBottomBorder(MaterialTheme.border.regular)
        .padding(MaterialTheme.spacing.level3),
    verticalAlignment = Alignment.CenterVertically
    ) {
    leadingIcon()
    Box(modifier = Modifier.padding(MaterialTheme.spacing.level3)) {
        ProvideTextStyle(
            value = MaterialTheme.typography.displayMedium,
            content = title
        )
    }
    RowSpacer()
    trailingIcons()
}

object MonoAppBarDefaults {

    val windowInsets: WindowInsets
        @Composable
        get() = WindowInsets.safeDrawing
            .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)
}

