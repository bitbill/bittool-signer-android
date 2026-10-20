package com.bittool.signer.core.security.testing

import android.app.Activity
import androidx.compose.runtime.Composable
import com.bittool.signer.core.security.LockWindowController
import com.bittool.signer.core.security.SecureWindowPolicy
import com.bittool.signer.core.security.WindowGate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * A [LockWindowController] that records instead of adding a window (shell tests). [content] holds the last lock screen
 * passed to [show], so a Compose test can render it. Not used by production code.
 */
class FakeLockWindowController : LockWindowController {
    private val _isShowing = MutableStateFlow(false)
    override val isShowing: StateFlow<Boolean> = _isShowing.asStateFlow()

    var showCalls: Int = 0
        private set
    var hideCalls: Int = 0
        private set
    var content: (@Composable () -> Unit)? = null
        private set

    override fun show(activity: Activity, content: @Composable () -> Unit) {
        showCalls += 1
        this.content = content
        _isShowing.value = true
    }

    override fun hide() {
        hideCalls += 1
        _isShowing.value = false
    }
}

/** A [WindowGate] without a lock window: [setOpen] takes effect at once. Starts [open]. Not used by production code. */
class FakeWindowGate(open: Boolean = true) : WindowGate {
    private val _isOpen = MutableStateFlow(open)
    override val isOpen: StateFlow<Boolean> = _isOpen.asStateFlow()

    override fun setOpen(open: Boolean) {
        _isOpen.value = open
    }

    override suspend fun awaitOpen() {
        isOpen.first { it }
    }
}

/** A [SecureWindowPolicy] that records its calls (shell tests). Not used by production code. */
class FakeSecureWindowPolicy : SecureWindowPolicy {
    /** `allowCapture` of every [apply] call, in order. */
    val applied: MutableList<Boolean> = mutableListOf()

    override fun apply(activity: Activity, allowCapture: Boolean) {
        applied += allowCapture
    }
}
