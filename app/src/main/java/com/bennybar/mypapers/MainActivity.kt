package com.bennybar.mypapers

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.bennybar.mypapers.feature.documents.DocumentDetailScreen
import com.bennybar.mypapers.feature.documents.DocumentsScreen
import com.bennybar.mypapers.feature.documents.EditDocumentScreen
import com.bennybar.mypapers.feature.settings.SettingsScreen
import com.bennybar.mypapers.nav.LocalNavAnimatedScope
import com.bennybar.mypapers.nav.LocalSharedTransitionScope
import com.bennybar.mypapers.nav.Route
import com.bennybar.mypapers.ui.theme.AppTheme.Emphasized
import com.bennybar.mypapers.ui.theme.PapersTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        setContent { PapersTheme { AppRoot() } }
    }

    override fun onResume() {
        super.onResume()
        // Renew the server token well before it expires (it lasts 30 days), silently
        // with the Google account already signed in. Needs an activity, so it's done here.
        val session = app.server.session.value ?: return
        if (session.expiresAt - System.currentTimeMillis() > RENEW_WITHIN_MS) return
        lifecycleScope.launch {
            runCatching { app.account.renew(this@MainActivity) }.getOrNull()?.let { app.onSignedIn(it) }
        }
    }

    private companion object {
        const val RENEW_WITHIN_MS = 15L * 24 * 60 * 60 * 1000
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun AppRoot() {
    val controller = rememberNavController()
    SharedTransitionLayout(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        CompositionLocalProvider(LocalSharedTransitionScope provides this) {
            NavHost(
                navController = controller,
                startDestination = Route.Documents,
                // Ilay's fast full-width slide: the new page slides in from the
                // right over a slight parallax of the old one. The back gesture
                // scrubs the reverse (linear, so the page stays under the finger).
                // Opening a document is a container transform instead (its card
                // grows into the page), so both pages only fade under the shared bounds.
                enterTransition = { if (opensDocument()) fadeIn(tween(300)) else slideInHorizontally(tween(300, easing = Emphasized)) { it } },
                exitTransition = { if (opensDocument()) fadeOut(tween(300)) else slideOutHorizontally(tween(300, easing = Emphasized)) { -it / 4 } },
                popEnterTransition = { if (closesDocument()) fadeIn(tween(300)) else slideInHorizontally(tween(300, easing = Emphasized)) { -it / 4 } },
                popExitTransition = { if (closesDocument()) fadeOut(tween(300)) else slideOutHorizontally(tween(300, easing = Emphasized)) { it } },
                predictivePopEnterTransition = { if (closesDocument()) fadeIn(tween(300, easing = LinearEasing)) else slideInHorizontally(tween(300, easing = LinearEasing)) { -it / 4 } },
                predictivePopExitTransition = { if (closesDocument()) fadeOut(tween(300, easing = LinearEasing)) else slideOutHorizontally(tween(300, easing = LinearEasing)) { it } },
            ) {
                screen<Route.Documents> {
                    DocumentsScreen(
                        onOpen = { controller.navigate(Route.Document(it)) },
                        onSettings = { controller.navigate(Route.Settings) },
                    )
                }
                // Not opaque: the page itself is the container growing out of the card.
                screen<Route.Document>(opaque = false) {
                    val id = it.toRoute<Route.Document>().id
                    DocumentDetailScreen(id, onBack = controller::popBackStack, onEdit = { controller.navigate(Route.EditDocument(id)) })
                }
                screen<Route.EditDocument> { EditDocumentScreen(it.toRoute<Route.EditDocument>().id, onDone = controller::popBackStack) }
                screen<Route.Settings> { SettingsScreen(onBack = controller::popBackStack) }
            }
        }
    }
}

private fun AnimatedContentTransitionScope<NavBackStackEntry>.opensDocument() =
    targetState.destination.hasRoute<Route.Document>()

private fun AnimatedContentTransitionScope<NavBackStackEntry>.closesDocument() =
    initialState.destination.hasRoute<Route.Document>()

/**
 * A destination drawn on an opaque surface, so sliding pages never show through;
 * it also hands the screen its enter/exit scope for shared bounds.
 */
private inline fun <reified T : Any> NavGraphBuilder.screen(
    opaque: Boolean = true,
    noinline content: @Composable AnimatedContentScope.(NavBackStackEntry) -> Unit,
) = composable<T> { entry ->
    CompositionLocalProvider(LocalNavAnimatedScope provides this) {
        Box(if (opaque) Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface) else Modifier.fillMaxSize()) {
            content(entry)
        }
    }
}
