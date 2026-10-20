package com.bittool.signer.core.scanner.camera

import androidx.annotation.MainThread
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry

/**
 * A lifecycle that is RESUMED only while [host] is RESUMED and CREATED otherwise, for CameraX's `bindToLifecycle`.
 *
 * iOS shows the camera only while the scene is **active** (`QRScannerView.swift:29`): the session is torn down as soon
 * as the app is inactive (app switcher, notification shade, a system dialog). A navigation entry or activity
 * lifecycle stays STARTED in those cases, which would keep the camera open; this owner drops to CREATED on
 * `ON_PAUSE`, so CameraX closes and releases the camera device (and its torch), and reopens it on `ON_RESUME`
 * (scan-sign.md §23.3 "Lifecycle"). [destroy] moves it to DESTROYED, which also unbinds every use case bound to it.
 */
@MainThread
internal class ResumedLifecycleOwner(private val host: Lifecycle) : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    private val observer = LifecycleEventObserver { _, _ -> sync() }

    override val lifecycle: Lifecycle get() = registry

    init {
        registry.currentState = Lifecycle.State.CREATED
        host.addObserver(observer)
    }

    private fun sync() {
        if (registry.currentState == Lifecycle.State.DESTROYED) return
        registry.currentState = when {
            host.currentState == Lifecycle.State.DESTROYED -> Lifecycle.State.DESTROYED
            host.currentState.isAtLeast(Lifecycle.State.RESUMED) -> Lifecycle.State.RESUMED
            else -> Lifecycle.State.CREATED
        }
        if (registry.currentState == Lifecycle.State.DESTROYED) host.removeObserver(observer)
    }

    fun destroy() {
        host.removeObserver(observer)
        if (registry.currentState != Lifecycle.State.DESTROYED) registry.currentState = Lifecycle.State.DESTROYED
    }
}
