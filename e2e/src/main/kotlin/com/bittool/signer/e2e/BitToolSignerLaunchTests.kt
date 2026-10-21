package com.bittool.signer.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bittool.signer.e2e.support.DeviceSetupRule
import com.bittool.signer.e2e.support.Shots
import com.bittool.signer.e2e.support.SignerApp
import com.bittool.signer.e2e.support.Ui
import org.junit.ClassRule
import org.junit.Test
import org.junit.runner.RunWith

/** Port of `BitToolSignerUITestsLaunchTests.swift:4-12` (gaps.md G1.19). */
@RunWith(AndroidJUnit4::class)
class BitToolSignerLaunchTests {
    private val ui = Ui()
    private val app = SignerApp(ui)

    @Test
    fun testLaunchScreenshot() {
        // Cold launch with a reset and no fixtures; capture allowed so the one screenshot shows the screen.
        app.launch(allowCapture = true, waitUntilReady = false)
        ui.waitFor(ui.labeled("BitTool Signer"), 10_000)
        Shots.take("launch")
    }

    companion object {
        @get:ClassRule
        @JvmStatic
        val deviceSetup = DeviceSetupRule()
    }
}
