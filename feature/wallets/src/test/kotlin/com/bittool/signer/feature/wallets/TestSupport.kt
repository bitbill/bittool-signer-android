package com.bittool.signer.feature.wallets

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/**
 * Keeps a `WhileSubscribed` [StateFlow] hot for the rest of the test (the screen collecting it) and returns it, so
 * `.value` reflects the ViewModel as the screen sees it.
 */
fun <T> TestScope.observe(flow: StateFlow<T>): StateFlow<T> {
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect {} }
    return flow
}
