package com.bittool.signer.feature.setup.flow

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import com.bittool.signer.core.crypto.bip39.Bip39Canonical
import com.bittool.signer.core.crypto.bip39.Bip39Candidates
import com.bittool.signer.core.crypto.bip39.Bip39Language
import com.bittool.signer.core.model.BackupVerification
import com.bittool.signer.core.model.PasswordPolicy
import com.bittool.signer.core.model.UiText
import com.bittool.signer.core.model.VaultMetadata
import com.bittool.signer.core.model.WalletId
import com.bittool.signer.core.model.util.isBlankSwift
import com.bittool.signer.core.model.util.trimWhitespacesAndNewlines
import com.bittool.signer.core.navigation.SetupIntent
import com.bittool.signer.core.ui.password.wipe

/** The pushed steps of a setup presentation (iOS `WalletSetupModel.Step`); the root (Create / Import form) is `[]`. */
enum class SetupStep { Backup, Verify, Password, Done }

/**
 * Recovery words for display (the words grid, the cards, the phrase QR). Real phrase words: [toString] is redacted, so
 * a `UiState` printed into a log or a crash report never carries them (DECISIONS §6).
 */
@Immutable
class RecoveryWords(val list: List<String>) {
    val size: Int get() = list.size
    fun isEmpty(): Boolean = list.isEmpty()

    /** The phrase QR payload (setup-backup §8.5): the words joined by single spaces, nothing else. */
    fun joined(): String = list.joinToString(" ")

    override fun equals(other: Any?): Boolean = other is RecoveryWords && other.list == list
    override fun hashCode(): Int = list.hashCode()
    override fun toString(): String = "RecoveryWords(${list.size}, <redacted>)"

    companion object {
        val Empty = RecoveryWords(emptyList())
    }
}

/**
 * The live analysis of the import editor (iOS `RecoveryPhraseDraft` + `RecoveryPhraseAssistant`, setup-backup §7.2,
 * §7.4), computed once per text change on `Dispatchers.Default` (§11.8 performance note). [unknownWords] (the first two)
 * and [candidates] come from typed words: [toString] is redacted.
 */
@Immutable
class PhraseAnalysis(
    val wordCount: Int,
    val unknownWords: List<String>,
    val language: Bip39Language?,
    val looksComplete: Boolean,
    val candidates: Bip39Candidates,
) {
    override fun equals(other: Any?): Boolean = other is PhraseAnalysis && other.wordCount == wordCount &&
        other.unknownWords == unknownWords && other.language == language && other.looksComplete == looksComplete &&
        other.candidates == candidates

    override fun hashCode(): Int = ((wordCount * 31 + unknownWords.hashCode()) * 31 + (language?.hashCode() ?: 0)) * 31 + candidates.hashCode()
    override fun toString(): String = "PhraseAnalysis(words=$wordCount, language=$language, unknown=${unknownWords.size})"

    companion object {
        val Empty = PhraseAnalysis(0, emptyList(), null, false, Bip39Candidates.TypeAWord)
    }
}

/** What `WalletIdentityHeader` shows for the wallet a reset targets or the setup created (iOS reads the store). */
@Immutable
data class WalletIdentity(val name: String, val fingerprint: String?, val avatarIdentity: String)

/**
 * Everything the setup screens draw (port of the published properties of iOS `WalletSetupModel`, setup-backup §2.1),
 * except the typed secrets, which live in [SetupFields]. Holds recovery words ([words], the [verification] chips, the
 * [languageChoices] readings): never saved, never logged ([toString] is redacted).
 */
@Immutable
data class WalletSetupUiState(
    val intent: SetupIntent,
    /** The model-owned step path (setup-backup §1.3, §15.4); transitions replace or append it. */
    val path: List<SetupStep> = emptyList(),
    /** Create only: 24 (default) or 12. */
    val wordCount: Int = 24,
    val usePassphrase: Boolean = false,
    /** The generated phrase's words (create); empty for imports, whose words are never displayed. */
    val words: RecoveryWords = RecoveryWords.Empty,
    /** The words were shown (grid or cards, never the QR alone); survives re-concealment. */
    val hasRevealedBackup: Boolean = false,
    val verification: BackupVerification? = null,
    /** Several valid readings of an import: the "Which language is your backup in?" choice is open. */
    val languageChoices: List<Bip39Canonical> = emptyList(),
    val busy: Boolean = false,
    /** Shown as a danger callout in the step's footer. */
    val error: UiText? = null,
    /** The created or reset wallet: the success boundary. */
    val created: VaultMetadata? = null,
    /** An existing wallet this phrase and passphrase derive: the password step resets that wallet's password. */
    val duplicateOf: WalletId? = null,
    val didResetPassword: Boolean = false,
    /** "Discard this wallet?" is open. */
    val confirmDiscard: Boolean = false,
    /** The recovery QR scanner is open (import). */
    val scanning: Boolean = false,
    val scanError: UiText? = null,
    /** `SignerState.securityEpoch`: the backup panel is rebuilt (concealed, Words, its sheets closed) when it changes. */
    val securityEpoch: Long = 0,
    /** Import editor status and suggestions. */
    val phrase: PhraseAnalysis = PhraseAnalysis.Empty,
    /** The wallet named on the reset password step ([duplicateOf]) or the Done step ([created]). */
    val identity: WalletIdentity? = null,
    /**
     * The password step asked whether this phrase already belongs to a wallet here and has no answer yet: its action
     * waits (QA setup-08), so a quick tap cannot reach the duplicate refusal before the page turns into Reset Password.
     */
    val checkingExisting: Boolean = false,
    /**
     * [duplicateOf]'s device key is provably gone (QA backlog B21): its password cannot be reset, so the password step
     * explains that and leads to the wallet's page, where Delete Wallet offers "Remove This Wallet" (B9).
     */
    val duplicateKeyLost: Boolean = false,
    /**
     * The device has a secure lock screen (`DeviceKeyStore.isDeviceSecure()`, QA wallets-04). Without one no wallet can
     * be saved: the first step says so before anything is typed and its action stays disabled.
     */
    val deviceSecure: Boolean = true,
) {
    /** The visible step; null is the root form. */
    val step: SetupStep? get() = path.lastOrNull()

    /** "Step n of total" of [step] (setup-backup §1.3): create 1–4, import 1–2; null on Done. */
    val stepNumber: Int?
        get() = when (step) {
            null -> 1
            SetupStep.Backup -> 2
            SetupStep.Verify -> 3
            SetupStep.Password -> if (intent == SetupIntent.Create) 4 else 2
            SetupStep.Done -> null
        }

    val stepTotal: Int get() = if (intent == SetupIntent.Create) 4 else 2

    override fun toString(): String = "WalletSetupUiState(path=$path, busy=$busy, words=${words.size}, created=${created != null})"
}

/**
 * The typed text of one setup presentation: the wallet name, the import editor and the four secret fields. Created by
 * the ViewModel (never `rememberTextFieldState()`, which saves its text into the instance state; ARCHITECTURE §4.4)
 * and cleared by its transitions. Validity is read straight from the fields, so the screens can derive their enabled
 * states without copying a secret into a `String`.
 */
@Stable
class SetupFields internal constructor() {
    val name = TextFieldState()
    val phrase = TextFieldState()
    val passphrase = TextFieldState()
    val passphraseConfirmation = TextFieldState()
    val password = TextFieldState()
    val confirmation = TextFieldState()

    /** iOS `trimmedName`. */
    fun trimmedName(): String = name.text.trimWhitespacesAndNewlines()

    /**
     * iOS `passphraseValid`: off, or non-empty and equal to its confirmation. Compared code unit by code unit (Swift's
     * `==` also accepts canonically equivalent spellings; an exact match is the stricter, safer reading).
     */
    fun passphraseValid(usePassphrase: Boolean): Boolean =
        !usePassphrase || (passphrase.text.isNotEmpty() && passphrase.text.contentEquals(passphraseConfirmation.text))

    /** iOS `passwordValid`: the policy holds and the confirmation matches. */
    fun passwordValid(): Boolean =
        PasswordPolicy.evaluate(password.text).isValid && PasswordPolicy.matches(password.text, confirmation.text)

    /** The import editor holds nothing but white space (iOS `trimmingCharacters(in: .whitespacesAndNewlines).isEmpty`). */
    fun phraseIsBlank(): Boolean = phrase.text.isBlankSwift()

    /** The passphrase drives the "not included" warnings of the QR code and the cards only while the option is on. */
    fun hasPassphrase(usePassphrase: Boolean): Boolean = usePassphrase && passphrase.text.isNotEmpty()

    /** Empties both password fields, undo history included (no Ctrl+Z brings a cleared password back). */
    internal fun clearPasswords() {
        password.wipe()
        confirmation.wipe()
    }

    /** Empties every secret field, undo history included. The name is not a secret and stays. */
    internal fun clearSecrets() {
        phrase.wipe()
        passphrase.wipe()
        passphraseConfirmation.wipe()
        clearPasswords()
    }

    override fun toString(): String = "SetupFields(<redacted>)"
}
