package com.bittool.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

/** Shared Android configuration. AGP 9 compiles Kotlin itself (built-in Kotlin), so no Kotlin plugin is applied here. */
internal fun Project.configureKotlinAndroid(commonExtension: CommonExtension) {
    commonExtension.apply {
        compileSdk = SignerConfig.COMPILE_SDK
        defaultConfig.apply {
            minSdk = SignerConfig.MIN_SDK
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            vectorDrawables.useSupportLibrary = true
        }
        compileOptions.apply {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
        packaging.resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/INDEX.LIST",
            "/META-INF/DEPENDENCIES",
            "/META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            "DebugProbesKt.bin",
        )
        testOptions.unitTests.isIncludeAndroidResources = true
        testOptions.unitTests.isReturnDefaultValues = true
        lint.apply {
            lintConfig = rootProject.file("lint.xml")
            abortOnError = true
        }
    }
    configureKotlin()
    configureUnitTestJvm()
}

internal fun Project.configureKotlinJvm() {
    extensions.configure<JavaPluginExtension> {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    configureKotlin()
    configureUnitTestJvm()
}

private fun Project.configureUnitTestJvm() {
    tasks.withType<Test>().configureEach {
        jvmArgs("-XX:+EnableDynamicAgentLoading")
        maxHeapSize = "1536m"
    }
}

private fun Project.configureKotlin() {
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            freeCompilerArgs.addAll(
                "-opt-in=kotlin.RequiresOptIn",
                "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
                "-opt-in=kotlinx.coroutines.FlowPreview",
            )
        }
    }
}
