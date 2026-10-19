plugins {
    alias(libs.plugins.bittool.android.library)
}

android {
    namespace = "com.bittool.signer.core.common"
}

dependencies {
    api(libs.kotlinx.coroutines.core)
}
