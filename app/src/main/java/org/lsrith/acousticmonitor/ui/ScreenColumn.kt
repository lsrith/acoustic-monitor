// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Copyright 2026 Samrith Ly

package org.lsrith.acousticmonitor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The standard scrolling body for a screen.
 *
 * Every screen here grows past a phone's height as soon as real data arrives —
 * ten model cards during setup, a dozen stat rows on a detail view — and a
 * plain Column silently clips whatever does not fit. Nothing below the fold is
 * reachable and there is no visual hint that anything is missing, which is the
 * worst kind of layout bug.
 *
 * Use this for any screen built from a fixed set of children.
 *
 * **Do not wrap a `LazyColumn` in this.** A lazy list is already its own scroll
 * container, and nesting one inside a scrolling Column gives it infinite height
 * — it will try to compose every item at once. Screens with long or unbounded
 * lists should use a `LazyColumn` directly as their root instead.
 */
@Composable
fun ScreenColumn(
    modifier: Modifier = Modifier,
    spacing: Dp = 12.dp,
    padding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}
