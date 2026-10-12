package com.bennybar.mypapers.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection

/**
 * The layout's start side as a fixed alignment. The theme reads each text's own direction
 * (Hebrew right to left), and with that TextAlign.Start follows the text, so a Hebrew title
 * in an English list would sit on the right. Rows of titles use this to line up.
 */
@Composable
@ReadOnlyComposable
fun layoutStart(): TextAlign = if (LocalLayoutDirection.current == LayoutDirection.Ltr) TextAlign.Left else TextAlign.Right
