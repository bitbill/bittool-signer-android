package com.bittool.signer.core.scanner

import com.bittool.signer.core.scanner.CameraPermissionStatus.Denied
import com.bittool.signer.core.scanner.CameraPermissionStatus.Granted
import com.bittool.signer.core.scanner.CameraPermissionStatus.NotAsked
import com.bittool.signer.core.scanner.CameraPermissionStatus.PermanentlyDenied
import org.junit.Test
import kotlin.test.assertEquals

/** scan-sign.md §6.6, §23.5: ask on appearance, never stack prompts on resume, pick up a grant made in Settings. */
class CameraPermissionFlowTest {
    @Test
    fun theFirstAppearanceAsksUnlessGranted() {
        assertEquals(Granted, CameraPermissionFlow.initial(granted = true, requestedBefore = false, shouldShowRationale = false))
        assertEquals(NotAsked, CameraPermissionFlow.initial(granted = false, requestedBefore = false, shouldShowRationale = false))
        // Denied once on Android 11+: the system would ask again, and the scanner does on its next appearance.
        assertEquals(NotAsked, CameraPermissionFlow.initial(granted = false, requestedBefore = false, shouldShowRationale = true))
    }

    @Test
    fun aRestoredPresentationThatAlreadyAskedShowsTheDeniedPanel() {
        assertEquals(Denied, CameraPermissionFlow.initial(granted = false, requestedBefore = true, shouldShowRationale = true))
        assertEquals(PermanentlyDenied, CameraPermissionFlow.initial(granted = false, requestedBefore = true, shouldShowRationale = false))
    }

    @Test
    fun theSystemAnswerDecidesBetweenGrantedDeniedAndPermanentlyDenied() {
        assertEquals(Granted, CameraPermissionFlow.onResult(granted = true, shouldShowRationale = false))
        assertEquals(Denied, CameraPermissionFlow.onResult(granted = false, shouldShowRationale = true))
        assertEquals(PermanentlyDenied, CameraPermissionFlow.onResult(granted = false, shouldShowRationale = false))
    }

    @Test
    fun resumingPicksUpAGrantFromSettingsAndNeverPrompts() {
        assertEquals(Granted, CameraPermissionFlow.onResume(PermanentlyDenied, granted = true, requestedBefore = true, shouldShowRationale = false))
        assertEquals(Granted, CameraPermissionFlow.onResume(NotAsked, granted = true, requestedBefore = true, shouldShowRationale = false))
        // While the system dialog is up the activity pauses and resumes: the status stays as it is.
        assertEquals(NotAsked, CameraPermissionFlow.onResume(NotAsked, granted = false, requestedBefore = true, shouldShowRationale = false))
        assertEquals(Denied, CameraPermissionFlow.onResume(Denied, granted = false, requestedBefore = true, shouldShowRationale = true))
    }

    @Test
    fun aGrantThatExpiredWhileAwayIsReadAgain() {
        // A one-time grant can lapse in the background.
        assertEquals(NotAsked, CameraPermissionFlow.onResume(Granted, granted = false, requestedBefore = false, shouldShowRationale = true))
        assertEquals(Denied, CameraPermissionFlow.onResume(Granted, granted = false, requestedBefore = true, shouldShowRationale = true))
    }
}
