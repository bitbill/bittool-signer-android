package com.bittool.signer.core.common.coroutines

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** File and asset I/O (`Dispatchers.IO`). */
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class IoDispatcher

/** CPU work: PBKDF2, QR encoding, BIP39 analysis, JSON of large documents (`Dispatchers.Default`). */
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class DefaultDispatcher

/** UI state mutation (`Dispatchers.Main.immediate`): `SignerStore` confines every state change to it. */
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class MainDispatcher

/**
 * Process-lifetime scope (`SupervisorJob() + Dispatchers.Default`) for work that outlives a screen: the Connect
 * prefetch, receipt writes, the engine prewarm. Never cancelled; children must be cancelled by their owner.
 */
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object DispatchersModule {
    @Provides @IoDispatcher fun ioDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides @DefaultDispatcher fun defaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides @MainDispatcher fun mainDispatcher(): CoroutineDispatcher = Dispatchers.Main.immediate

    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(@DefaultDispatcher dispatcher: CoroutineDispatcher): CoroutineScope =
        CoroutineScope(SupervisorJob() + dispatcher)
}

/**
 * `runCatching` that never swallows cancellation (models-state.md §12.3: "Never wrap requireSession in runCatching").
 * A [CancellationException] — a late result or an epoch change — is rethrown; any other failure becomes a [Result].
 */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}

/** Rethrows [this] when it is a cancellation; returns it otherwise (for `catch (e: Throwable)` blocks). */
fun Throwable.rethrowIfCancellation(): Throwable {
    if (this is CancellationException) throw this
    return this
}
