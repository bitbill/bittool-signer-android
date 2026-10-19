import com.android.build.api.dsl.LibraryExtension
import com.bittool.buildlogic.configureKotlinAndroid
import com.bittool.buildlogic.lib
import com.bittool.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")
        extensions.configure<LibraryExtension> {
            configureKotlinAndroid(this)
            defaultConfig.consumerProguardFiles("consumer-rules.pro")
        }
        dependencies {
            add("implementation", libs.lib("kotlinx-coroutines-android"))
            add("testImplementation", libs.lib("junit"))
            add("testImplementation", libs.lib("kotlin-test"))
            add("testImplementation", libs.lib("kotlinx-coroutines-test"))
            add("testImplementation", libs.lib("robolectric"))
            add("testImplementation", libs.lib("androidx-test-core"))
            add("androidTestImplementation", libs.lib("androidx-test-ext-junit"))
            add("androidTestImplementation", libs.lib("androidx-test-runner"))
            add("androidTestImplementation", libs.lib("kotlinx-coroutines-test"))
        }
    }
}
