package io.github.puvon.enetrend.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

/** Short content keeps actions at the bottom; long content scrolls together with its actions. */
@Composable
internal fun ScreenWithBottomActions(
    modifier: Modifier,
    padding: Dp,
    spacing: Dp,
    actions: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(modifier) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .heightIn(min = maxHeight).padding(padding), verticalArrangement = Arrangement.SpaceBetween) {
            Column(verticalArrangement = Arrangement.spacedBy(spacing), content = content)
            Column(Modifier.padding(top = spacing), verticalArrangement = Arrangement.spacedBy(spacing), content = actions)
        }
    }
}
