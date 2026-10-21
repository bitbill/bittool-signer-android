package com.bittool.signer.core.crypto.vault

import com.bittool.signer.core.crypto.internal.hasUnpairedSurrogate
import com.bittool.signer.core.model.VaultMetadata

/**
 * The wallet-name rule of every write that stores a name (vault-crypto.md §12.1): trimmed of whitespace and newlines,
 * 1–40 grapheme clusters ([VaultMetadata.validName], iOS) and — the Android addition of QA audit A04 — short enough
 * that the vault header holding it stays within the 4,096 bytes the reader accepts ([fitsVaultHeader]).
 *
 * Forty clusters can be long: a letter followed by a hundred combining marks is one cluster of about 200 UTF-8 bytes.
 * Such a name passes the grapheme limit, but its header would be refused on read, and one unreadable vault makes
 * `WalletVault.list()` (which fails as a whole, iOS parity) fail for every wallet on the device. iOS has the same latent
 * defect (`VaultCrypto.encrypt` has no header bound, `EncryptedVault.init(from:)` refuses more than 4,096 bytes).
 * Names in any script, including long emoji sequences, stay far below the bound, so only such stacked marks are
 * refused. A refused name is [VaultError.InvalidName], "Choose a wallet name of 1–40 characters.", the iOS name error.
 */
object WalletNames {
    /**
     * The trimmed [name] when a wallet can be created with it, else null. Also refuses a lone UTF-16 surrogate, which
     * a Swift string cannot hold and UTF-8 would store as "?", so the header would not read back as the name given.
     */
    fun valid(name: String): String? =
        VaultMetadata.validName(name)?.takeIf { !hasUnpairedSurrogate(it.toCharArray()) && fitsVaultHeader(it) }

    /**
     * Whether a vault header storing [trimmedName] stays within the reader's 4,096-byte bound for any wallet id, salt
     * and creation time. What counts is the name as the header stores it (escaped UTF-8 JSON): about 3,880 bytes.
     */
    fun fitsVaultHeader(trimmedName: String): Boolean = VaultFormat.headerFits(trimmedName)
}
