package com.bittool.signer.core.crypto

import com.bittool.signer.core.crypto.vault.KeystoreKeyPolicy
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertFalse

/**
 * Owner decision 2026-10-24 (DECISIONS.md §6): production keys are never `setUnlockedDeviceRequired`, which Android
 * 12–13 keep locked after a face or Smart Lock unlock, leaving every wallet and Activity unusable until a PIN unlock.
 */
@RunWith(RobolectricTestRunner::class)
class KeyPolicyTest {
    @Test
    @Config(sdk = [31])
    fun productionKeysDoNotRequireAnUnlockedDevice() {
        assertFalse(KeystoreKeyPolicy.forDevice(RuntimeEnvironment.getApplication()).unlockedDeviceRequired)
    }
}
