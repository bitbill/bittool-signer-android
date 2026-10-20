plugins {
    alias(libs.plugins.bittool.android.library)
    alias(libs.plugins.bittool.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.bittool.signer.core.engine"
    defaultConfig {
        ndk {
            // Phones (arm64, 32-bit ARM) and x86_64 emulators / Chromebooks.
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    androidResources {
        // signer.js stays uncompressed in the APK so its SHA-256 can be checked against a published build.
        noCompress += "js"
    }
}

dependencies {
    implementation(projects.core.common)
    api(projects.core.model)
    implementation(libs.kotlinx.serialization.json)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.kotlin.test)
}
