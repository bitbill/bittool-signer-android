package com.bittool.signer.core.data.reference

import android.content.Context
import com.bittool.signer.core.common.coroutines.DefaultDispatcher
import com.bittool.signer.core.common.coroutines.IoDispatcher
import com.bittool.signer.core.common.coroutines.runCatchingCancellable
import com.bittool.signer.core.data.ReferenceDataRepository
import com.bittool.signer.core.engine.BundleDigest
import com.bittool.signer.core.engine.SignerEngine
import com.bittool.signer.core.model.json.SignerJson
import com.bittool.signer.core.model.registry.KnownContractEntry
import com.bittool.signer.core.model.registry.ReferenceData
import com.bittool.signer.core.model.registry.TokenRegistryEntry
import com.bittool.signer.core.model.registry.TokenRegistryNetwork
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * The bundled reference data of Settings › Networks, Tokens & Contracts and the Open Source page (activity-settings.md
 * §4, §9; iOS `TokenDirectory.load`, `TokenDirectoryView.swift:97-147`, and `OpenSourceView.readBuild`,
 * `SettingsTab.swift:310-330`). Public data only.
 *
 * Both files are decoded once per process on [default] and cached (iOS decodes once per page instance; the content
 * is immutable, so caching changes nothing visible). A missing or undecodable file leaves its half empty, as on iOS:
 * registry → no networks/tokens and an empty version; contracts → no contracts and an empty date.
 */
@Singleton
class AssetReferenceDataRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val engine: SignerEngine,
    @IoDispatcher private val io: CoroutineDispatcher,
    @DefaultDispatcher private val default: CoroutineDispatcher,
) : ReferenceDataRepository {

    private val assets = context.assets
    private val mutex = Mutex()

    /** Read without the lock on the fast path, so it is published with a volatile write. */
    @Volatile
    private var cached: ReferenceData? = null

    override suspend fun load(): ReferenceData = cached ?: mutex.withLock {
        cached ?: withContext(default) { decode() }.also { cached = it }
    }

    override suspend fun registryVersion(): String? = withContext(io) {
        // The version sits in the first bytes of the file; the rest is megabytes of tokens (SettingsTab.swift:318-324).
        runCatchingCancellable {
            val head = assets.open(TOKEN_REGISTRY).use { stream ->
                val buffer = ByteArray(REGISTRY_HEAD_BYTES)
                var read = 0
                while (read < buffer.size) {
                    val n = stream.read(buffer, read, buffer.size - read)
                    if (n < 0) break
                    read += n
                }
                buffer.copyOf(read)
            }
            REGISTRY_VERSION.find(head.decodeToString())?.groupValues?.get(1)
        }.getOrNull()
    }

    override suspend fun engineDigest(): BundleDigest? = runCatchingCancellable { engine.bundleDigest() }.getOrNull()

    private fun decode(): ReferenceData {
        val registry = read(TOKEN_REGISTRY) { SignerJson.decodeFromString(RegistryFile.serializer(), it) }
        val contracts = read(KNOWN_CONTRACTS) { SignerJson.decodeFromString(ContractsFile.serializer(), it) }
        return ReferenceData(
            registryVersion = registry?.registryVersion ?: "",
            networks = registry?.networks ?: emptyList(),
            // The file is ordered by network, then rank: grouping keeps that order.
            tokensByChain = registry?.assets?.groupBy { it.chain } ?: emptyMap(),
            // Within a network, by project then name: a long list is read by eye (TokenDirectoryView.swift:136-139).
            contractsByChain = contracts?.contracts?.groupBy { it.chain }?.mapValues { (_, list) -> list.sortedWith(PROJECT_THEN_NAME) }
                ?: emptyMap(),
            contractsUpdated = contracts?.updated ?: "",
        )
    }

    private fun <T> read(path: String, parse: (String) -> T): T? = try {
        parse(assets.open(path).use { it.readBytes() }.decodeToString(throwOnInvalidSequence = true))
    } catch (e: Exception) {
        null
    }

    @Serializable
    private data class RegistryFile(
        val registryVersion: String,
        val networks: List<TokenRegistryNetwork>,
        val assets: List<TokenRegistryEntry>,
    )

    @Serializable
    private data class ContractsFile(val updated: String, val contracts: List<KnownContractEntry>)

    companion object {
        const val TOKEN_REGISTRY = "registry/token-registry.json"
        const val KNOWN_CONTRACTS = "registry/known-contracts.json"
        private const val REGISTRY_HEAD_BYTES = 512
        private val REGISTRY_VERSION = Regex("\"registryVersion\":\"([^\"]+)\"")

        /**
         * Swift's `(project, name) <` on `String`s: lexicographic by Unicode scalar, which differs from Kotlin's UTF-16
         * order only for supplementary characters.
         */
        internal val PROJECT_THEN_NAME: Comparator<KnownContractEntry> =
            Comparator<KnownContractEntry> { a, b -> compareScalars(a.project, b.project) }.thenComparator { a, b -> compareScalars(a.name, b.name) }

        internal fun compareScalars(a: String, b: String): Int {
            var i = 0
            var j = 0
            while (i < a.length && j < b.length) {
                val ca = a.codePointAt(i)
                val cb = b.codePointAt(j)
                if (ca != cb) return ca.compareTo(cb)
                i += Character.charCount(ca)
                j += Character.charCount(cb)
            }
            return (a.length - i).compareTo(b.length - j)
        }
    }
}
