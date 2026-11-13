/*
 * Adaptive Preference List for Jetpack Compose
 * Provides generic rendering for lists of PreferenceItems
 */

package app.aaps.core.ui.compose.preference

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.aaps.core.keys.interfaces.PreferenceItem
import app.aaps.core.keys.interfaces.PreferenceKey
import app.aaps.core.keys.interfaces.VisibilityContext

/**
 * Renders a list of preference items (keys, subscreens, custom items).
 * This is the enhanced version that handles all PreferenceItem types.
 *
 * Supported types:
 * - PreferenceKey: Rendered with AdaptivePreferenceItem
 * - PreferenceSubScreenDef: Rendered as navigation item
 *
 * @param items List of PreferenceItems to render
 * @param visibilityContext Optional context for evaluating runtime visibility conditions
 * @param onNavigateToSubScreen Callback for navigating to subscreens
 */
@Composable
fun AdaptivePreferenceList(
    items: List<PreferenceItem>,
    onShowMessage: (String) -> Unit = { },
    visibilityContext: VisibilityContext? = null,
    onNavigateToSubScreen: ((PreferenceSubScreenDef) -> Unit)? = null
) {
    items.forEach { item ->
        when (item) {
            is PreferenceKey          -> {
                // AdaptivePreferenceItem handles all visibility checks
                // (simpleMode, mode flags, dependencies, engineeringMode, runtime visibility)
                AdaptivePreferenceItem(
                    key = item,
                    onShowMessage = onShowMessage,
                    visibilityContext = visibilityContext
                )
            }

            is PreferenceSubScreenDef -> {
                // PERSONAL PATCH: a subscreen in this list (for example inside a group of the search
                // sheet) was not drawn at all. Show it as a collapsible section, like the settings
                // screen does.
                if (shouldShowSubScreenInline(subScreen = item, visibilityContext = visibilityContext)) {
                    var expanded by remember(item.key) { mutableStateOf(false) }
                    ClickablePreferenceCategoryHeader(
                        title = item.title,
                        summaryItems = item.effectiveSummaryItems(),
                        expanded = expanded,
                        onToggle = { expanded = !expanded },
                        insideCard = true
                    )
                    if (expanded && item.items.isNotEmpty()) {
                        Column(modifier = Modifier.padding(start = LocalPreferenceTheme.current.nestedContentIndent)) {
                            AdaptivePreferenceList(
                                items = item.items,
                                onShowMessage = onShowMessage,
                                visibilityContext = visibilityContext
                            )
                        }
                    }
                }
            }
        }
    }
}
