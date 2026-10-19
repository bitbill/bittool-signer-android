package com.bittool.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

internal fun Project.configureCompose(commonExtension: CommonExtension) {
    commonExtension.buildFeatures.compose = true
    dependencies {
        val bom = libs.lib("androidx-compose-bom")
        add("implementation", platform(bom))
        add("androidTestImplementation", platform(bom))
        add("testImplementation", platform(bom))
        add("implementation", libs.lib("androidx-compose-ui"))
        add("implementation", libs.lib("androidx-compose-ui-graphics"))
        add("implementation", libs.lib("androidx-compose-foundation"))
        add("implementation", libs.lib("androidx-compose-material3"))
        add("implementation", libs.lib("androidx-compose-material-icons-extended"))
        add("implementation", libs.lib("androidx-compose-animation"))
        add("implementation", libs.lib("androidx-compose-ui-tooling-preview"))
        add("debugImplementation", libs.lib("androidx-compose-ui-tooling"))
        add("debugImplementation", libs.lib("androidx-compose-ui-test-manifest"))
        add("androidTestImplementation", libs.lib("androidx-compose-ui-test-junit4"))
        add("testImplementation", libs.lib("androidx-compose-ui-test-junit4"))
    }
}
