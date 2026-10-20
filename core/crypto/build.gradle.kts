plugins {
    alias(libs.plugins.bittool.android.library)
    alias(libs.plugins.bittool.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.bittool.signer.core.crypto"
    defaultConfig {
        ndk {
            // The same ABIs as :core:engine and :app (arm64 and 32-bit ARM phones, x86_64 emulators / Chromebooks).
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }
    externalNativeBuild {
        cmake {
            // libbittoolsigner_crypto.so: the vault's PBKDF2-HMAC-SHA256 (vault-crypto.md §17.6 option 1).
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    implementation(projects.core.common)
    api(projects.core.model)
    implementation(projects.core.l10n)
    implementation(libs.kotlinx.serialization.json)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.kotlin.test)
    // nativeSeedDerivesTheSameWalletAsTheEngine and the engine `wordlist` parity test (ARCHITECTURE.md §3 W1-crypto).
    androidTestImplementation(projects.core.engine)
}
