package com.bittool.signer.core.crypto.vault

import android.security.keystore.UserNotAuthenticatedException
import com.bittool.signer.core.l10n.R
import com.bittool.signer.core.model.SignerException
import com.bittool.signer.core.model.UiText

/**
 * Vault failures (vault-crypto.md §6.7, iOS `VaultError`). Messages "deliberately exclude passwords, phrases, key
 * material and JavaScript input". [text] is what the UI shows; every case except [DevicePasscodeRequired] is an iOS
 * catalogue string. Thrown by [WalletVault], `Bip39Seed`, `SecureRandomSource` and `SignerStore`.
 */
sealed class VaultError(text: UiText, cause: Throwable? = null) : SignerException(text, cause) {
    /** The new password breaks the policy; [policyMessage] is `PasswordPolicy.evaluate(...).message` or the passphrase cap. */
    class InvalidPassword(val policyMessage: UiText) : VaultError(policyMessage)

    /** "The recovery phrase is empty or too large." (phrase empty or > 1,024 UTF-8 bytes normalised, passphrase > 1,024). */
    class InvalidSecret : VaultError(UiText.Ios("The recovery phrase is empty or too large."))

    /** "Choose a wallet name of 1–40 characters." */
    class InvalidName : VaultError(UiText.Ios("Choose a wallet name of 1–40 characters."))

    /** "Secure randomness is unavailable. Try again after unlocking your device." */
    class RandomFailure(cause: Throwable? = null) : VaultError(UiText.Ios("Secure randomness is unavailable. Try again after unlocking your device."), cause)

    /** "The password key could not be created." (PBKDF2 or seed derivation failed). */
    class KeyDerivationFailure(cause: Throwable? = null) : VaultError(UiText.Ios("The password key could not be created."), cause)

    /** "The encrypted wallet could not be created." */
    class EncryptionFailure(cause: Throwable? = null) : VaultError(UiText.Ios("The encrypted wallet could not be created."), cause)

    /** "The password is incorrect." — also for any tampering, a wrong device key or a flipped byte (same wording on purpose). */
    class AuthenticationFailed : VaultError(UiText.Ios("The password is incorrect."))

    /**
     * "This wallet’s device key is unavailable. Restore your recovery phrase on this device." (Keystore key missing or
     * invalidated). Android-only: a key Keystore refuses because the phone counts as locked ([deviceLocked]) is not
     * gone. Android 12–13, and some manufacturers' builds after them, treat a phone unlocked by face recognition (or
     * another non-strong biometric) or Smart Lock as still locked for `setUnlockedDeviceRequired` keys until it is
     * unlocked with its PIN, pattern or password: that case says so instead.
     */
    class DeviceKeyUnavailable(cause: Throwable? = null) : VaultError(
        if (isDeviceLocked(cause)) {
            UiText.Resource(R.string.crypto_device_locked)
        } else {
            UiText.Ios("This wallet’s device key is unavailable. Restore your recovery phrase on this device.")
        },
        cause,
    ) {
        /** Keystore refused the key only because the phone counts as locked: unlocking it with its screen lock helps. */
        val deviceLocked: Boolean get() = isDeviceLocked(cause)
    }

    /** No secure lock screen (`KeyguardManager.isDeviceSecure() == false`): Android wording of the iOS passcode error. */
    class DevicePasscodeRequired : VaultError(UiText.Resource(R.string.crypto_device_lock_required))

    /**
     * Android-only: [WalletVault.removeWithLostDeviceKey] refused because the wallet's device key is not *provably* gone
     * (it is present, or Keystore did not answer well enough to prove its absence), so only the password can authorise
     * removing the wallet ([WalletVault.delete]). Nothing was changed.
     */
    class DeviceKeyLossUnconfirmed : VaultError(UiText.Resource(R.string.crypto_device_key_loss_unconfirmed))

    /** "The encrypted wallet could not be read or saved." (I/O, staging, rename). */
    class StorageFailure(cause: Throwable? = null) : VaultError(UiText.Ios("The encrypted wallet could not be read or saved."), cause)

    /** "The wallet file is damaged or has an invalid format." (bounds, JSON, header id, attempts file). */
    class MalformedVault(cause: Throwable? = null) : VaultError(UiText.Ios("The wallet file is damaged or has an invalid format."), cause)

    /** "This wallet format is not supported by this version of BitTool Signer." (`format`/`version` ≠ 2, unknown KDF). */
    class UnsupportedVersion : VaultError(UiText.Ios("This wallet format is not supported by this version of BitTool Signer."))

    /** "This wallet could not be found." */
    class WalletNotFound : VaultError(UiText.Ios("This wallet could not be found."))

    /** Throttled after repeated failures: "Too many unsuccessful attempts. Try again in %lld seconds." ([seconds] ≥ 1). */
    class TemporarilyLocked(val seconds: Long) :
        VaultError(UiText.Ios("Too many unsuccessful attempts. Try again in %lld seconds.", listOf(seconds)))

    private companion object {
        fun isDeviceLocked(cause: Throwable?): Boolean = cause.isKeystoreLockedRefusal()
    }
}

/**
 * Whether this failure (or one of its causes) is Keystore refusing a `setUnlockedDeviceRequired` key only because the
 * phone counts as locked (`UserNotAuthenticatedException`; see [VaultError.DeviceKeyUnavailable]): nothing is wrong
 * with the key or the data, and unlocking the phone with its PIN, pattern or password makes it usable again.
 */
fun Throwable?.isKeystoreLockedRefusal(): Boolean =
    generateSequence(this) { it.cause.takeIf { next -> next !== it } }.take(8).any { it is UserNotAuthenticatedException }
