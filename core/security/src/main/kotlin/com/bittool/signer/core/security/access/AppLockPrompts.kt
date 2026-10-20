package com.bittool.signer.core.security.access

import com.bittool.signer.core.l10n.R
import com.bittool.signer.core.model.UiText
import com.bittool.signer.core.security.BiometricPromptSpec

/**
 * The three biometric prompts of App Lock (app-shell.md §15.6, gaps.md G7.4). iOS passes a raw reason string to
 * `LAContext` ("Unlock BitTool Signer. Signing still requires the wallet password.", "Lock BitTool Signer with
 * \(biometry.name).", AppAccessController.swift:134, :144); `BiometricPrompt` has a title, a description and a
 * mandatory negative button instead, so the Android-only set of G7.4 replaces those reasons.
 */
object AppLockPrompts {
    /** `authenticateBiometrics()`: the negative button switches the lock screen to the password form. */
    val unlock: BiometricPromptSpec = BiometricPromptSpec(
        title = UiText.Resource(R.string.security_prompt_unlock_title),
        description = UiText.Resource(R.string.security_prompt_description),
        negativeButton = UiText.Ios("Use a Wallet Password"),
    )

    /** `setEnabled(true)`: "Lock BitTool Signer", negative "Cancel" leaves App Lock off. */
    val enable: BiometricPromptSpec = BiometricPromptSpec(
        title = UiText.Resource(R.string.security_prompt_lock_title),
        description = UiText.Resource(R.string.security_prompt_description),
        negativeButton = UiText.Ios("Cancel"),
    )

    /**
     * `confirmSensitiveAction(reason)`: the reason is the title (Delete Wallet passes "Confirm before deleting this
     * wallet.", WalletSecuritySheets.swift:271-283), negative "Cancel" aborts the action.
     */
    fun confirm(reason: UiText): BiometricPromptSpec =
        BiometricPromptSpec(title = reason, description = null, negativeButton = UiText.Ios("Cancel"))
}
