package com.bittool.signer.core.data

import com.bittool.signer.core.common.time.WallClock
import com.bittool.signer.core.crypto.SecureRandomSource
import com.bittool.signer.core.crypto.SystemSecureRandomSource
import com.bittool.signer.core.crypto.testing.InMemoryDeviceKeyStore
import com.bittool.signer.core.crypto.vault.DefaultWalletVault
import com.bittool.signer.core.crypto.vault.DeviceKeyStore
import com.bittool.signer.core.crypto.vault.JournalCipher
import com.bittool.signer.core.crypto.vault.PublicDataKey
import com.bittool.signer.core.crypto.vault.SignerFiles
import com.bittool.signer.core.data.activity.EncryptedReceiptRepository
import com.bittool.signer.core.data.wallet.FilePublicWalletRepository
import com.bittool.signer.core.engine.EngineCalls
import com.bittool.signer.core.engine.SignerEngine
import com.bittool.signer.core.model.SecretText
import com.bittool.signer.core.model.VaultMetadata
import com.bittool.signer.core.model.WalletId
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope

/**
 * `DefaultSignerStore`s over one storage directory with the **production** implementations of every dependency — the
 * Android counterpart of iOS tests building `AppStore(vault: WalletVault(directory:deviceKeys:), storageDirectory:)`:
 * `DefaultWalletVault` (format 2, PBKDF2-HMAC-SHA256 600,000 iterations, attempts file), `KeystoreJournalCipher`,
 * `DefaultBip39`, `Pbkdf2Bip39Seed`, `SystemSecureRandomSource`, `ZxingQrCodeEncoder` ([DeviceCrypto]).
 *
 * [deviceKeys] is the outer vault layer: `InMemoryDeviceKeyStore` by default (iOS `SessionDeviceKeys`, the store tests'
 * choice), or the Android Keystore ([DeviceCrypto.keystoreDeviceKeys]) for the end-to-end test.
 *
 * [relaunch] models a process restart: a new vault, a new journal cipher and new repositories over the same files and
 * the same device keys (Keystore keys outlive the process; one `InMemoryDeviceKeyStore` instance stands for them).
 *
 * The probes read the files the iOS tests read: the vault file ([vaultBytes]: "repair never rewrites the encrypted
 * wallet") and the persistent attempts file ([attemptsBytes]: absent until a password was tried; every unlock rewrites
 * it under a fresh nonce, so unchanged bytes prove that nothing tried a password).
 */
class RealStoreFixture(
    val dataDir: File,
    val engine: SignerEngine,
    val main: CoroutineDispatcher,
    val io: CoroutineDispatcher,
    val default: CoroutineDispatcher,
    val scope: CoroutineScope,
    val deviceKeys: DeviceKeyStore = InMemoryDeviceKeyStore(),
    val random: SecureRandomSource = SystemSecureRandomSource(),
    val clock: WallClock = WallClock { System.currentTimeMillis() },
) {
    val bip39 get() = DeviceCrypto.bip39
    val seed get() = DeviceCrypto.seed
    val encoder get() = DeviceCrypto.encoder

    /** The first launch's vault (tests call it directly, as iOS tests call `store.vault`). */
    val vault: DefaultWalletVault = newVault()

    /** The first launch's journal cipher; any instance opens the journal (the Keystore key is shared). */
    val cipher: JournalCipher = DeviceCrypto.journalCipher()

    val publicRepository = FilePublicWalletRepository(dataDir, io)
    val receiptRepository = EncryptedReceiptRepository(dataDir, cipher, io)

    val store: DefaultSignerStore by lazy { build(vault, publicRepository, receiptRepository) }

    /** Every wallet id this fixture's vaults created (cleanup of Keystore keys after a failure). */
    val walletIds: List<WalletId>
        get() = File(dataDir, SignerFiles.VAULTS_DIR).listFiles().orEmpty()
            .filter { it.name.endsWith("." + SignerFiles.VAULT_EXTENSION) }
            .mapNotNull { WalletId.parse(it.name.substringBeforeLast('.')) }

    fun relaunch(): DefaultSignerStore = build(
        newVault(),
        FilePublicWalletRepository(dataDir, io),
        EncryptedReceiptRepository(dataDir, DeviceCrypto.journalCipher(), io),
    )

    suspend fun publicDataKey(): PublicDataKey = checkNotNull(vault.publicDataKey(createIfMissing = true))

    /** iOS `vault.create(name:mnemonic:password:)`: an encrypted wallet without a public sidecar (a pre-index wallet). */
    suspend fun createVaultOnly(name: String, phrase: String, password: String, passphrase: String = ""): VaultMetadata =
        SecretText.of(phrase).use { words ->
            SecretText.of(passphrase).use { pass ->
                SecretText.of(password).use { pw -> vault.create(name, words, pass, pw, WalletId.random()) }
            }
        }

    /** `Vaults/<lowercase-uuid>.bsvault`. */
    fun vaultFile(id: WalletId): File = File(dataDir, "${SignerFiles.VAULTS_DIR}/$id.${SignerFiles.VAULT_EXTENSION}")

    fun vaultBytes(id: WalletId): ByteArray = vaultFile(id).readBytes()

    /** `Vaults/.<lowercase-uuid>.attempts` (vault-crypto.md §8). */
    fun attemptsFile(id: WalletId): File = File(dataDir, "${SignerFiles.VAULTS_DIR}/.$id.attempts")

    /** The sealed attempts file, or null while no password was ever tried. */
    fun attemptsBytes(id: WalletId): ByteArray? = attemptsFile(id).takeIf { it.exists() }?.readBytes()

    /** The journal file the store writes (`receipts.bin`). */
    val journalFile: File get() = receiptRepository.file

    /** The journal's plaintext, opened with the Keystore journal key. */
    fun journalText(): String = cipher.open(journalFile.readBytes()).decodeToString()

    private fun newVault() = DefaultWalletVault(dataDir, deviceKeys, random, clock, default, io)

    private fun build(vault: DefaultWalletVault, publicWallets: FilePublicWalletRepository, receipts: EncryptedReceiptRepository) =
        DefaultSignerStore(
            vault = vault,
            engine = EngineCalls(engine),
            publicRepository = publicWallets,
            receiptRepository = receipts,
            bip39 = bip39,
            bip39Seed = seed,
            random = random,
            qrEncoder = encoder,
            clock = clock,
            main = main,
            default = default,
            scope = scope,
        )
}
