plugins {
    alias(libs.plugins.bittool.android.library)
    alias(libs.plugins.bittool.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.bittool.signer.core.data"
    androidResources {
        // The token icon pack is memory-mapped (PackTokenIconSource): it must stay uncompressed in every APK that
        // packages this module's assets, including the instrumented-test APK (`:app` declares the same).
        noCompress += "pack"
    }
    sourceSets {
        // Test doubles of the Wave-1 interfaces (vault, BIP39, journal cipher, QR encoder) shared by the JVM tests and
        // the instrumented tests over the real engine.
        getByName("test") {
            kotlin.directories += "src/testShared/kotlin"
            resources.directories += "src/testShared/resources"
        }
        getByName("androidTest") {
            kotlin.directories += "src/testShared/kotlin"
            resources.directories += "src/testShared/resources"
        }
    }
}

dependencies {
    implementation(projects.core.common)
    api(projects.core.model)
    api(projects.core.engine)
    api(projects.core.crypto)
    implementation(projects.core.l10n)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.turbine)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.kotlin.test)
    androidTestImplementation(libs.turbine)
}
