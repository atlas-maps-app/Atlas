package app.atlas.nav

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.navigation3.ui.NavDisplay
import app.atlas.Services
import app.atlas.mapview.LocalMapStage
import app.atlas.mapview.MapStage
import app.atlas.mapview.MapStageHost
import app.atlas.mapview.vk.ChromeFade
import app.atlas.mapview.vk.LocalChromeShapes
import app.atlas.ui.kit.Atlas
import app.atlas.ui.kit.AtlasTheme
import app.atlas.ui.kit.BoardScale
import app.atlas.ui.kit.LocalBackdrop
import app.atlas.ui.kit.LocalFrostedGlass
import app.atlas.ui.kit.rememberBackdrop

/** The app's services, for any screen: `LocalServices.current.weather`. */
val LocalServices = staticCompositionLocalOf<Services> { error("No Services here: the screen must run inside AtlasRoot or ProvideServices") }

/**
 * How much room the bottom bar takes over a tab's home, so content can end above it. Zero on screens without the bar.
 */
val LocalBottomBarInset = staticCompositionLocalOf<Dp> { 0.dp }

/**
 * What a tab's home may change about the frame around it: [barHidden] hides the bottom bar (the Map home does while
 * its results sheet is up). Reset when the screen leaves.
 */
@Stable
class Chrome {
    var barHidden by mutableStateOf(false)
}

val LocalChrome = staticCompositionLocalOf { Chrome() }

/** Provides [services] (and the theme) to [content]: what tests and previews wrap a single screen in. */
@Composable
fun ProvideServices(services: Services, dark: Boolean = false, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalServices provides services) {
        AtlasTheme(dark = dark, content = content)
    }
}

/**
 * The whole app: the screens of the back stack ([navigator]) with Navigation 3, and the bottom bar over the tab homes.
 * Every entry keeps its own saved state and view models while it is on the stack. Under every screen lies the app's one
 * map ([MapStage]): screens ask for it with `StageMap`, and it stays as they come and go.
 */
@Composable
fun AtlasRoot(services: Services, navigator: BackStackNavigator, dark: Boolean = false) = BoardScale {
    // the frosted glass of the bars and pills blurs what a screen puts behind it (the Map home's map)
    val backdrop = rememberBackdrop()
    CompositionLocalProvider(LocalBackdrop provides backdrop, LocalChromeShapes provides backdrop.shapes) {
        Root(services, navigator, dark)
    }
}

@Composable
private fun Root(services: Services, navigator: BackStackNavigator, dark: Boolean) {
    ProvideServices(services, dark) {
        val chrome = remember { Chrome() }
        val stage = remember { MapStage() }
        // the bar's 64 px and the margin under it: what a tab home ends above
        val barTop = 64.dp + bottomBarMargin()
        Box(Modifier.fillMaxSize().background(Atlas.colors.paper)) {
            MapStageHost(stage, cover = Atlas.colors.paper)
            val showBar = navigator.current.isTabRoot && !chrome.barHidden
            CompositionLocalProvider(
                LocalBottomBarInset provides if (navigator.current.isTabRoot) barTop else 0.dp,
                LocalChrome provides chrome,
                LocalMapStage provides stage,
            ) {
                NavDisplay(
                    backStack = navigator.stack,
                    onBack = { navigator.back() },
                    entryDecorators = listOf(
                        rememberSaveableStateHolderNavEntryDecorator(),
                        rememberViewModelStoreNavEntryDecorator(),
                    ),
                    transitionSpec = {
                        (slideInHorizontally(tween(260)) { it / 3 } + fadeIn(tween(200))) togetherWith fadeOut(tween(160))
                    },
                    popTransitionSpec = {
                        fadeIn(tween(200)) togetherWith (slideOutHorizontally(tween(240)) { it / 3 } + fadeOut(tween(200)))
                    },
                    predictivePopTransitionSpec = {
                        fadeIn(tween(200)) togetherWith (slideOutHorizontally(tween(240)) { it / 3 } + fadeOut(tween(200)))
                    },
                    // ChromeFade: an entry's frosted chrome tells the map's core the alpha its fade draws it at
                    entryProvider = { dest ->
                        NavEntry(dest, contentKey = dest.route) { d ->
                            ChromeFade(LocalNavAnimatedContentScope.current) { d.Content(navigator) }
                        }
                    },
                )
            }
            if (showBar) {
                // solid unless the Look settings' frosted glass is on (the map's core then blurs nothing under the bar)
                val glass by services.frostedGlass.collectAsState(initial = false)
                CompositionLocalProvider(LocalFrostedGlass provides glass) {
                    BottomBar(
                        selected = navigator.current.tab,
                        onSelect = { navigator.tab(it) },
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }
    }
}
