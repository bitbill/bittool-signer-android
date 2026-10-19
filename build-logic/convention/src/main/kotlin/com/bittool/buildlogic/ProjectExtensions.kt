package com.bittool.buildlogic

import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.getByType

val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

fun VersionCatalog.lib(alias: String): Provider<MinimalExternalModuleDependency> =
    findLibrary(alias).orElseThrow { IllegalStateException("Missing library alias '$alias' in libs.versions.toml") }

fun VersionCatalog.pluginId(alias: String): String =
    findPlugin(alias).orElseThrow { IllegalStateException("Missing plugin alias '$alias'") }.get().pluginId

object SignerConfig {
    const val COMPILE_SDK = 37
    /** Android 8.0: old phones are common air-gapped devices; Keystore AES-GCM/HMAC and CameraX are all available. */
    const val MIN_SDK = 26
    const val TARGET_SDK = 36
}
