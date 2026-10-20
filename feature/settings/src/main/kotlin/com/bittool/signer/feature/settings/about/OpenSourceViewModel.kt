package com.bittool.signer.feature.settings.about

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bittool.signer.core.common.BuildInfo
import com.bittool.signer.core.common.Distribution
import com.bittool.signer.core.data.ReferenceDataRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A value of "This build" that is measured on the device: "Reading…", the value, or "Unavailable". */
@Immutable
sealed interface Measured<out T> {
    data object Reading : Measured<Nothing>
    data class Value<T>(val value: T) : Measured<T>
    data object Unavailable : Measured<Nothing>
}

/** What the Open Source page draws (iOS `OpenSourceView`). */
@Immutable
data class OpenSourceUiState(
    /** "1.0.0 (1)": About › Version and the tag to check out. */
    val version: String,
    /** This app's source (`BuildConfig.REPOSITORY_URL`, DECISIONS.md §9.1). */
    val repository: String,
    val distribution: Distribution,
    /** Upper-case colon-separated SHA-256 of the signing certificate, or null ("Unavailable"). */
    val signingCertificate: String?,
    /** Lower-case hex SHA-256 of the bundled `signer.js`. */
    val engineDigest: Measured<String> = Measured.Reading,
    val engineBytes: Measured<Int> = Measured.Reading,
    val registryVersion: Measured<String> = Measured.Reading,
)

/**
 * Open Source (iOS `OpenSourceView`, activity-settings.md §9, §14.6–§14.8): the repositories, this build's identity and
 * how to rebuild the engine. The engine digest is `SignerEngine.bundleDigest()` of the bundled asset, computed on this
 * device once per page visit (gaps.md G7.5); the registry version comes from the first 512 bytes of the registry. A
 * value that cannot be read shows "Unavailable" — including the engine size, which iOS leaves at "Reading…".
 */
@HiltViewModel
class OpenSourceViewModel @Inject constructor(
    private val repository: ReferenceDataRepository,
    buildInfo: BuildInfo,
) : ViewModel() {
    private val state = MutableStateFlow(
        OpenSourceUiState(
            version = buildInfo.displayVersion,
            repository = buildInfo.repositoryUrl,
            distribution = buildInfo.distribution,
            signingCertificate = buildInfo.signingCertificateSha256,
        ),
    )
    val uiState: StateFlow<OpenSourceUiState> = state.asStateFlow()

    init {
        viewModelScope.launch {
            val digest = async { measure { repository.engineDigest() } }
            val registry = async { measure { repository.registryVersion() } }
            val engine = digest.await()
            val version = registry.await()
            state.update {
                it.copy(
                    engineDigest = engine?.let { d -> Measured.Value(d.sha256) } ?: Measured.Unavailable,
                    engineBytes = engine?.let { d -> Measured.Value(d.bytes) } ?: Measured.Unavailable,
                    registryVersion = version?.let { v -> Measured.Value(v) } ?: Measured.Unavailable,
                )
            }
        }
    }

    private suspend fun <T> measure(block: suspend () -> T?): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }
}
