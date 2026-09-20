package com.wearos.ancsbridge.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester

/**
 * Give a scrolling screen input focus so the Digital Crown / rotating bezel scrolls it.
 * ScalingLazyColumn handles rotary input, but only once it's focused — without an
 * AppScaffold/HierarchicalFocusCoordinator nothing requests that focus.
 */
@Composable
fun Modifier.rotaryFocus(): Modifier {
    val focusRequester = remember { FocusRequester() }
    // Throws if the node isn't attached yet (screen swapped out mid-composition), which
    // is not worth crashing a screen over: the crown simply won't scroll it.
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    return this.focusRequester(focusRequester)
}
