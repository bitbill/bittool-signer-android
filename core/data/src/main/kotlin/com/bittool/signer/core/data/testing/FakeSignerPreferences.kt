package com.bittool.signer.core.data.testing

import com.bittool.signer.core.data.PreferenceValues
import com.bittool.signer.core.data.SignerPreferences
import com.bittool.signer.core.model.Appearance
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

/**
 * An in-memory [SignerPreferences] for tests (Wave 2). **Test double only.** Pass `initial = null` to model the
 * "still loading" state (App Lock resolving): [load] then suspends until a test publishes values with [publish].
 * Setters record their call and update [values]; set [failure] to make them throw.
 */
class FakeSignerPreferences(initial: PreferenceValues? = PreferenceValues()) : SignerPreferences {

    private val mutableValues = MutableStateFlow(initial)
    override val values: StateFlow<PreferenceValues?> = mutableValues.asStateFlow()

    private val recorded = CopyOnWriteArrayList<Call>()

    /** Every setter call so far, oldest first. */
    val calls: List<Call> get() = recorded.toList()

    /** Thrown by every setter while non-null. */
    @Volatile
    var failure: Throwable? = null

    sealed interface Call {
        data class SetAppearance(val appearance: Appearance) : Call
        data class SetAppLockEnabled(val enabled: Boolean) : Call
        data class SetOnboardingVersionCompleted(val version: Int) : Call
    }

    /** Publishes [values] as if the store had just read them (or `null` to go back to loading). */
    fun publish(values: PreferenceValues?) {
        mutableValues.value = values
    }

    override suspend fun load(): PreferenceValues = mutableValues.filterNotNull().first()

    override suspend fun setAppearance(appearance: Appearance) = set(Call.SetAppearance(appearance)) { it.copy(appearance = appearance) }

    override suspend fun setAppLockEnabled(enabled: Boolean) = set(Call.SetAppLockEnabled(enabled)) { it.copy(appLockEnabled = enabled) }

    override suspend fun setOnboardingVersionCompleted(version: Int) =
        set(Call.SetOnboardingVersionCompleted(version)) { it.copy(onboardingVersionCompleted = version) }

    private suspend fun set(call: Call, transform: (PreferenceValues) -> PreferenceValues) {
        recorded += call
        failure?.let { throw it }
        val loaded = load()
        mutableValues.update { transform(it ?: loaded) }
    }
}
