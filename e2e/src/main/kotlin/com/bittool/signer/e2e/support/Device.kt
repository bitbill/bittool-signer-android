package com.bittool.signer.e2e.support

import android.os.Bundle
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.rules.ExternalResource

/** Shell commands on the device (as the shell user, like `adb shell`; no quoting: arguments never hold spaces). */
object Shell {
    private val device: UiDevice get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    fun run(command: String): String = device.executeShellCommand(command)
}

/** Instrumentation arguments (`-Pandroid.testInstrumentationRunnerArguments.<name>=<value>`). */
object Args {
    private val arguments: Bundle get() = InstrumentationRegistry.getArguments()

    /** Where screenshots are written on the device (gaps.md G2 `shotDir`; iOS `BITTOOL_SIGNER_SHOT_DIR`). */
    val shotDir: String get() = arguments.getString("shotDir")?.takeIf { it.isNotBlank() } ?: "/data/local/tmp/bittool-e2e-shots"

    /**
     * gaps.md G2 `extraArgs` (iOS `BITTOOL_SIGNER_EXTRA_ARGS`): space-separated `key=value` items. `locale=<tag>` sets the
     * harness' app language (`bts.uitest.locale`), `fontScale=<scale>` the system font scale for the run (restored after).
     */
    val extra: Map<String, String>
        get() = arguments.getString("extraArgs").orEmpty().split(' ', ',').filter { it.contains('=') }
            .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }

    val locale: String? get() = extra["locale"]
    val fontScale: String? get() = extra["fontScale"]
}

/**
 * Device setup of gaps.md G1.0, once per test class: the three animation scales at 0 (and the system font scale of
 * `extraArgs`, when given). The previous values are restored afterwards, so the shared emulator is left as it was.
 */
class DeviceSetupRule : ExternalResource() {
    private val saved = mutableMapOf<String, String>()
    private var savedFontScale: String? = null

    override fun before() {
        for (key in ANIMATION_SCALES) {
            saved[key] = Shell.run("settings get global $key").trim()
            Shell.run("settings put global $key 0")
        }
        Args.fontScale?.let { scale ->
            savedFontScale = Shell.run("settings get system font_scale").trim()
            Shell.run("settings put system font_scale $scale")
        }
    }

    override fun after() {
        for ((key, value) in saved) {
            if (value.isEmpty() || value == "null") Shell.run("settings delete global $key") else Shell.run("settings put global $key $value")
        }
        savedFontScale?.let { value ->
            if (value.isEmpty() || value == "null") Shell.run("settings delete system font_scale") else Shell.run("settings put system font_scale $value")
        }
        Log.i("SignerE2E", "Device settings restored")
    }

    private companion object {
        val ANIMATION_SCALES = listOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale")
    }
}

/**
 * Screenshots written by the shell (`screencap`) to [Args.shotDir] on the device, a shell-owned folder that outlives
 * the test APK's uninstall after the run; `:e2e:pullE2eShots` pulls it to `build/e2e-shots`. They show the screen only
 * while the run allows capture (`bts.uitest.allowCapture`, which clears FLAG_SECURE in debug builds); otherwise they
 * are black. Sensitive shots (throwaway phrases) go to this folder only, never into test reports.
 */
object Shots {
    fun prepare(prefix: String) {
        Shell.run("mkdir -p ${Args.shotDir}")
        // Only this prefix: a light and a dark run share the folder.
        val listing = Shell.run("ls ${Args.shotDir}")
        listing.lines().map(String::trim).filter { it.startsWith("$prefix-") && it.endsWith(".png") }
            .forEach { Shell.run("rm -f ${Args.shotDir}/$it") }
    }

    fun take(name: String) {
        Shell.run("mkdir -p ${Args.shotDir}")
        Shell.run("screencap -p ${Args.shotDir}/$name.png")
    }
}
