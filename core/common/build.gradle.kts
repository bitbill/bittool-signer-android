plugins {
    alias(libs.plugins.bittool.android.library)
    alias(libs.plugins.bittool.hilt)
}

android {
    namespace = "com.bittool.signer.core.common"
}

dependencies {
    api(libs.kotlinx.coroutines.core)
}
