import com.bittool.buildlogic.configureKotlinJvm
import com.bittool.buildlogic.lib
import com.bittool.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.jvm")
        configureKotlinJvm()
        dependencies {
            add("testImplementation", libs.lib("junit"))
            add("testImplementation", libs.lib("kotlin-test"))
        }
    }
}
