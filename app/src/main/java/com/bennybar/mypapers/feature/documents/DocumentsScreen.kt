package com.bennybar.mypapers.feature.documents

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bennybar.mypapers.nav.LocalNavAnimatedScope
import com.bennybar.mypapers.nav.LocalSharedTransitionScope
import com.bennybar.mypapers.nav.documentContainerKey

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentsScreen(onOpen: (String) -> Unit, onSettings: () -> Unit) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("My Papers") },
                actions = {
                    IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, contentDescription = "Settings") }
                },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(placeholderDocuments, key = { it.id }) { doc -> DocumentCard(doc, onClick = { onOpen(doc.id) }) }
        }
    }
}

/** The card is the container that grows into the detail page (container transform). */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun DocumentCard(doc: PlaceholderDocument, onClick: () -> Unit) {
    val shape = MaterialTheme.shapes.extraLarge
    with(LocalSharedTransitionScope.current) {
        Card(
            onClick = onClick,
            shape = shape,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            modifier = Modifier
                .fillMaxWidth()
                .sharedBounds(
                    rememberSharedContentState(documentContainerKey(doc.id)),
                    LocalNavAnimatedScope.current,
                    resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds,
                    clipInOverlayDuringTransition = OverlayClip(shape),
                ),
        ) {
            Column(Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        doc.vendor,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(doc.amount, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.padding(top = 4.dp))
                Text(
                    "${doc.type} · ${doc.date}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.padding(top = 12.dp))
                TagRow(doc.tags)
            }
        }
    }
}

@Composable
fun TagRow(tags: List<String>) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        tags.forEach { tag ->
            Text(
                tag,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}
