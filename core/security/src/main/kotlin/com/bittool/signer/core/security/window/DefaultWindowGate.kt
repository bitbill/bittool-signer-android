package com.bittool.signer.core.security.window

import androidx.annotation.MainThread
import com.bittool.signer.core.security.WindowGate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * The window gate (Ownbit `LockWindowGate.kt` + `WindowGate.kt`; app-shell.md §15.7): on iOS a sheet or alert
 * requested while App Lock is up is presented beneath the lock window; on Android a dialog, sheet or toast window
 * added later would open above it. `:app` drives [setOpen] with `!showsLock && !captured`.
 *
 * - Fail closed: closed until `:app` first opens it (the lock state resolves first).
 * - [setOpen]`(false)` closes at once, in the same frame the lock engages.
 * - [setOpen]`(true)` opens as soon as no lock window is on screen. A lock window counts until its unlock fade-out has
 *   finished and it is removed ([LockWindowPresence]), so what waited appears over the app, not over the fading lock
 *   screen (Ownbit `UNLOCK_SETTLE_MILLIS`); without a lock window it opens at once.
 *
 * Main thread only.
 */
@Singleton
internal class DefaultWindowGate(private val lockWindow: LockWindowPresence) : WindowGate {

    @Inject constructor(lockWindow: DefaultLockWindowController) : this(lockWindow as LockWindowPresence)

    private var requested = false
    private val _isOpen = MutableStateFlow(false)
    override val isOpen: StateFlow<Boolean> = _isOpen.asStateFlow()

    init {
        lockWindow.addPresenceListener(::update)
    }

    @MainThread
    override fun setOpen(open: Boolean) {
        requested = open
        update()
    }

    override suspend fun awaitOpen() {
        isOpen.first { it }
    }

    private fun update() {
        _isOpen.value = requested && !lockWindow.isPresent
    }
}
