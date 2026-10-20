package com.bittool.signer.feature.setup

import androidx.compose.runtime.snapshots.Snapshot
import com.bittool.signer.core.crypto.SecureRandomSource
import com.bittool.signer.core.crypto.bip39.Bip39
import com.bittool.signer.core.crypto.bip39.Bip39WordlistSource
import com.bittool.signer.core.crypto.bip39.DefaultBip39
import com.bittool.signer.core.model.util.Hex
import java.io.File
import kotlin.random.Random

/** The real BIP39 implementation over the shipped wordlists (JVM tests read the crypto module's asset folder). */
val testBip39: Bip39 by lazy { DefaultBip39(Bip39WordlistSource.directory(File("../../core/crypto/src/main/assets"))) }

/** A deterministic [SecureRandomSource] (the backup check's shuffles become reproducible). */
class SeededRandomSource(seed: Int = 7) : SecureRandomSource {
    private val random = Random(seed)
    override fun bytes(count: Int): ByteArray = random.nextBytes(count)
    override fun entropyHex(wordCount: Int): CharArray = Hex.encodeToChars(bytes(wordCount / 3 * 4))
    override fun asKotlinRandom(): Random = random
}

/** Delivers pending snapshot writes (text-field edits made by the test) to `snapshotFlow` observers. */
fun applySnapshots() = Snapshot.sendApplyNotifications()

/** Published BIP39 test vectors and UI-test phrases only (ARCHITECTURE §4.9); never fund them. */
object Phrases {
    /** BIP39 vector (entropy 7f7f…7f). */
    const val VECTOR_12 = "legal winner thank year wave sausage worth useful legal winner thank yellow"

    /** BIP39 vector (entropy 8080…80, 24 words). */
    const val VECTOR_24 = "letter advice cage absurd amount doctor acoustic avoid letter advice cage absurd amount doctor acoustic avoid " +
        "letter advice cage absurd amount doctor acoustic bless"

    /** The iOS UI tests' Spanish phrase ("dragon" deliberately without its accent, UIT:273). */
    const val SPANISH = "obra diadema gorila farmacia colgar gorra pausa talar cocina duda dragon optar"

    /** Valid in Italian as typed and in Spanish without its accents (UIT:287). */
    const val ITALIAN_OR_SPANISH = "nomina solido comodo nucleo pagina armonia nucleo liquido pagina logica nucleo nucleo"
}
