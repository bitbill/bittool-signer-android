package com.bittool.signer.core.crypto

import com.bittool.signer.core.common.testing.FakeWallClock
import com.bittool.signer.core.crypto.bip39.Bip39WordlistSource
import com.bittool.signer.core.crypto.bip39.DefaultBip39
import com.bittool.signer.core.crypto.testing.InMemoryDeviceKeyStore
import com.bittool.signer.core.crypto.vault.DefaultWalletVault
import com.bittool.signer.core.model.SecretText
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers

/** The public BIP39 test phrase (never a funded wallet). */
const val ABANDON_PHRASE = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

/** A [SecretText] for a literal test value. */
fun secret(text: String): SecretText = SecretText.of(text)

/** The shipped wordlists, read from the module's asset folder (unit tests run with the module directory as cwd). */
val assetWordlists: Bip39WordlistSource = Bip39WordlistSource.directory(File("src/main/assets"))

/** One BIP39 instance per test JVM: loading ten lists is the expensive part. */
val sharedBip39: DefaultBip39 by lazy { DefaultBip39(assetWordlists) }

/** A fresh temporary signer data directory. */
fun tempDataDir(prefix: String = "bittool-signer-test-"): File = Files.createTempDirectory(prefix).toFile()

/**
 * A vault over [dataDir] (iOS tests: `WalletVault(directory:deviceKeys:)`). Pass the same [keys] to a second instance
 * to model a process restart: Keystore keys survive, in-memory state does not.
 */
fun vault(
    dataDir: File,
    keys: InMemoryDeviceKeyStore = InMemoryDeviceKeyStore(),
    clock: FakeWallClock = FakeWallClock(),
): DefaultWalletVault = DefaultWalletVault(dataDir, keys, SystemSecureRandomSource(), clock, Dispatchers.Default, Dispatchers.IO)
