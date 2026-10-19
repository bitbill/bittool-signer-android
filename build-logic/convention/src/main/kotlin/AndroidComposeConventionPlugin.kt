import com.android.build.api.dsl.LibraryExtension
import com.bittool.buildlogic.configureCompose
import com.bittool.buildlogic.libs
import com.bittool.buildlogic.pluginId
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/** Android library + Compose. */
class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("bittool.android.library")
        pluginManager.apply(libs.pluginId("compose-compiler"))
        extensions.configure<LibraryExtension> {
            configureCompose(this)
        }
    }
}
