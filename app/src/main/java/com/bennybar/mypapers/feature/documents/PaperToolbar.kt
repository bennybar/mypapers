package com.bennybar.mypapers.feature.documents

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.TextSnippet
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.SplitButtonDefaults
import androidx.compose.material3.SplitButtonLayout
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.bennybar.mypapers.data.Document

/**
 * A paper's everyday actions, floating at the bottom: Share (▾ Open with…, Export), Edit,
 * Extract all text, and Ask AI as the toolbar's own button.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PaperToolbar(
    doc: Document,
    canAsk: Boolean,
    canExtract: Boolean,
    onShare: () -> Unit,
    onOpenWith: () -> Unit,
    onExport: () -> Unit,
    onEdit: () -> Unit,
    onAsk: () -> Unit,
    onExtract: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var shareMenu by remember { mutableStateOf(false) }
    Box(modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
        HorizontalFloatingToolbar(
            expanded = true,
            floatingActionButton = {
                FloatingToolbarDefaults.VibrantFloatingActionButton(onClick = { if (canAsk) onAsk() }) {
                    Icon(Icons.Outlined.AutoAwesome, contentDescription = "Ask AI")
                }
            },
        ) {
            // Share, with its variants attached.
            Box {
                SplitButtonLayout(
                    leadingButton = {
                        SplitButtonDefaults.TonalLeadingButton(onClick = onShare, enabled = doc.fileLocal) {
                            Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Share")
                        }
                    },
                    trailingButton = {
                        SplitButtonDefaults.TonalTrailingButton(checked = shareMenu, onCheckedChange = { shareMenu = it }) {
                            Icon(
                                Icons.Filled.KeyboardArrowDown,
                                contentDescription = "More ways to share",
                                modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = if (shareMenu) 180f else 0f },
                            )
                        }
                    },
                )
                DropdownMenu(expanded = shareMenu, onDismissRequest = { shareMenu = false }) {
                    DropdownMenuItem(text = { Text("Open with…") }, enabled = doc.fileLocal, onClick = { shareMenu = false; onOpenWith() })
                    DropdownMenuItem(text = { Text("Export (spreadsheet + file)") }, onClick = { shareMenu = false; onExport() })
                }
            }
            IconButton(onClick = onEdit, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Outlined.Edit, contentDescription = "Edit") }
            IconButton(onClick = onExtract, enabled = canExtract, shapes = IconButtonDefaults.shapes()) {
                Icon(Icons.AutoMirrored.Outlined.TextSnippet, contentDescription = if (doc.transcript.isBlank()) "Extract all text" else "Extract all text again")
            }
        }
    }
}
