package com.bennybar.mypapers.feature.documents

import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateDp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bennybar.mypapers.nav.LocalNavAnimatedScope
import com.bennybar.mypapers.nav.LocalSharedTransitionScope
import com.bennybar.mypapers.nav.documentContainerKey

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun DocumentDetailScreen(id: String, onBack: () -> Unit) {
    val doc = placeholderDocuments.first { it.id == id }
    val animatedScope = LocalNavAnimatedScope.current
    // The page's corners round off as it shrinks back into the card (28dp, the card's radius).
    val corner by animatedScope.transition.animateDp(label = "corner") {
        if (it == EnterExitState.Visible) 0.dp else 28.dp
    }
    val shape = RoundedCornerShape(corner)
    with(LocalSharedTransitionScope.current) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.sharedBounds(
                rememberSharedContentState(documentContainerKey(id)),
                animatedScope,
                resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
                clipInOverlayDuringTransition = OverlayClip(shape),
            ),
            topBar = {
                TopAppBar(
                    title = {},
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                )
            },
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(padding)
                    .padding(horizontal = 24.dp),
            ) {
                Text(doc.vendor, style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.padding(top = 4.dp))
                Text(
                    "${doc.type} · ${doc.date}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.padding(top = 16.dp))
                Text(doc.amount, style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.padding(top = 16.dp))
                TagRow(doc.tags)
                Spacer(Modifier.padding(top = 24.dp))
                // Where the document preview goes once files exist.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(0.71f)
                        .background(MaterialTheme.colorScheme.surfaceContainerLowest, MaterialTheme.shapes.large),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.Description,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(24.dp),
                    )
                }
                Spacer(Modifier.padding(top = 24.dp))
            }
        }
    }
}
