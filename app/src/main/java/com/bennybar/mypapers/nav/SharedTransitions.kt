package com.bennybar.mypapers.nav

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.compositionLocalOf

/**
 * The scopes a container transform needs: the app-wide shared-transition scope
 * and the current destination's enter/exit scope (set per screen by the NavHost).
 */
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope> { error("No SharedTransitionScope") }
val LocalNavAnimatedScope = compositionLocalOf<AnimatedVisibilityScope> { error("No AnimatedVisibilityScope") }

/** The shared-bounds key linking a document's card to its detail page. */
fun documentContainerKey(id: String) = "document-container-$id"
