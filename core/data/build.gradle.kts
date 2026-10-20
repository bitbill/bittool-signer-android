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
        // Shared test code: the engine decorator (ControlledSigningEngine, EngineGate), the public fixtures and vectors,
        // and the crypto/encoder doubles of the JVM tests (StoreFixture). The instrumented tests run on the production
        // crypto instead (androidTest RealStoreFixture) and use only the engine decorator and fixtures from here.
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
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.turbine)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.kotlin.test)
    androidTestImplementation(libs.turbine)
    // The production QrCodeEncoder (ZxingQrCodeEncoder) for the Connect prefetch in the real-stack store tests.
    androidTestImplementation(projects.core.scanner)
}
