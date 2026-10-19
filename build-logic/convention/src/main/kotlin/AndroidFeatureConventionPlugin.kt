import com.bittool.buildlogic.lib
import com.bittool.buildlogic.libs
import com.bittool.buildlogic.pluginId
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/**
 * Feature module = Android library + Compose + Hilt + the core modules every screen needs.
 * Feature modules never depend on other feature modules; they navigate through :core:navigation routes.
 */
class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("bittool.android.compose")
        pluginManager.apply("bittool.hilt")
        pluginManager.apply(libs.pluginId("kotlin-serialization"))
        dependencies {
            add("implementation", project(":core:common"))
            add("implementation", project(":core:model"))
            add("implementation", project(":core:l10n"))
            add("implementation", project(":core:designsystem"))
            add("implementation", project(":core:ui"))
            add("implementation", project(":core:navigation"))
            add("implementation", project(":core:data"))
            add("implementation", project(":core:security"))
            add("implementation", libs.lib("androidx-core-ktx"))
            add("implementation", libs.lib("androidx-activity-compose"))
            add("implementation", libs.lib("androidx-lifecycle-runtime-compose"))
            add("implementation", libs.lib("androidx-lifecycle-viewmodel-compose"))
            add("implementation", libs.lib("androidx-navigation-compose"))
            add("implementation", libs.lib("androidx-hilt-navigation-compose"))
            add("implementation", libs.lib("kotlinx-serialization-json"))
            add("testImplementation", libs.lib("turbine"))
        }
    }
}
