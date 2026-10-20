package com.bittool.signer.core.data.doubles

import com.bittool.signer.core.common.time.WallClock
import com.bittool.signer.core.crypto.Bip39Seed
import com.bittool.signer.core.crypto.SecureRandomSource
import com.bittool.signer.core.crypto.bip39.Bip39
import com.bittool.signer.core.crypto.vault.JournalCipher
import com.bittool.signer.core.crypto.vault.PublicDataKey
import com.bittool.signer.core.data.DefaultSignerStore
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
 * Builds `DefaultSignerStore`s over one storage directory, the way iOS tests build `AppStore(vault:engine:
 * storageDirectory:)`. **Integration swap point:** the Wave-1 doubles ([InMemoryWalletVault], [EnglishBip39],
 * [HmacBip39Seed], [JvmSecureRandomSource], [AesGcmJournalCipher], [FakeQrCodeEncoder]) are constructor defaults; the
 * orchestrator's rerun with the real W1-crypto / W1-scanner bindings passes those instead (the vault double's probes
 * then need the vault files instead).
 *
 * [store] is the first store; [relaunch] creates another one over the same files, vault and Keystore stand-ins (an app
 * relaunch).
 */
class StoreFixture(
    val dataDir: File,
    val engine: SignerEngine,
    val main: CoroutineDispatcher,
    val io: CoroutineDispatcher,
    val default: CoroutineDispatcher,
    val scope: CoroutineScope,
    val vault: InMemoryWalletVault = InMemoryWalletVault(),
    val cipher: JournalCipher = AesGcmJournalCipher(),
    val encoder: FakeQrCodeEncoder = FakeQrCodeEncoder(),
    val bip39: Bip39 = EnglishBip39(),
    val seed: Bip39Seed = HmacBip39Seed(),
    val random: SecureRandomSource = JvmSecureRandomSource(),
    val clock: WallClock = WallClock { System.currentTimeMillis() },
) {
    val publicRepository = FilePublicWalletRepository(dataDir, io)
    val receiptRepository = EncryptedReceiptRepository(dataDir, cipher, io)

    val store: DefaultSignerStore by lazy { build(publicRepository, receiptRepository) }

    fun relaunch(): DefaultSignerStore =
        build(FilePublicWalletRepository(dataDir, io), EncryptedReceiptRepository(dataDir, cipher, io))

    suspend fun publicDataKey(): PublicDataKey = checkNotNull(vault.publicDataKey(createIfMissing = true))

    /** iOS `vault.create(name:mnemonic:password:)`: an encrypted wallet without a public sidecar (a pre-index wallet). */
    suspend fun createVaultOnly(name: String, phrase: String, password: String, passphrase: String = ""): VaultMetadata =
        SecretText.of(phrase).use { words ->
            SecretText.of(passphrase).use { pass ->
                SecretText.of(password).use { pw -> vault.create(name, words, pass, pw, WalletId.random()) }
            }
        }

    private fun build(publicWallets: FilePublicWalletRepository, receipts: EncryptedReceiptRepository) = DefaultSignerStore(
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

    /** The journal file the store writes. */
    val journalFile: File get() = receiptRepository.file
}

/** Runs [block] with a copy of [this] as a secret and wipes it afterwards. */
inline fun <R> String.asSecret(block: (SecretText) -> R): R = SecretText.of(this).use(block)
