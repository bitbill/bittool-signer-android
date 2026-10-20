package com.bittool.signer.core.data.testing

import com.bittool.signer.core.data.ReferenceDataRepository
import com.bittool.signer.core.engine.BundleDigest
import com.bittool.signer.core.model.registry.ReferenceData
import java.util.concurrent.atomic.AtomicInteger

/**
 * A configurable [ReferenceDataRepository] for tests (Wave 2). **Test double only.** Defaults: no reference data, the
 * shipped registry version and the shipped engine digest (activity-settings.md §0 item 9); set a field to `null` to
 * model "Unavailable", or [failure] to make [load] throw.
 */
class FakeReferenceDataRepository(
    @Volatile var data: ReferenceData = ReferenceData.Empty,
    @Volatile var version: String? = "2026-09-22.1",
    @Volatile var digest: BundleDigest? = BundleDigest(
        sha256 = "6933d0d8a6f60faa8222506ac0bd7eef80eb884c9f73e31e9f354b4740f21077",
        bytes = 1_181_026,
        quickJsVersion = "2026-06-04",
    ),
) : ReferenceDataRepository {

    @Volatile
    var failure: Throwable? = null

    private val loads = AtomicInteger()

    /** How many times [load] ran. */
    val loadCount: Int get() = loads.get()

    override suspend fun load(): ReferenceData {
        loads.incrementAndGet()
        failure?.let { throw it }
        return data
    }

    override suspend fun registryVersion(): String? = version

    override suspend fun engineDigest(): BundleDigest? = digest
}
