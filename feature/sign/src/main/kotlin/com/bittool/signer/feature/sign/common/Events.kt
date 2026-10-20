package com.bittool.signer.feature.sign.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.Flow

/**
 * Collects one-off ViewModel [events] while the screen is at least started (ARCHITECTURE §4.1: a `Channel` received as
 * a flow, never a "consumed" flag in state). Events sent while stopped wait in the channel.
 */
@Composable
internal fun <T> CollectEvents(events: Flow<T>, onEvent: (T) -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val handler by rememberUpdatedState(onEvent)
    LaunchedEffect(events, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            events.collect { handler(it) }
        }
    }
}
