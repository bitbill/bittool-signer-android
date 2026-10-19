import com.android.build.api.dsl.ApplicationExtension
import com.bittool.buildlogic.SignerConfig
import com.bittool.buildlogic.configureCompose
import com.bittool.buildlogic.configureKotlinAndroid
import com.bittool.buildlogic.libs
import com.bittool.buildlogic.pluginId
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.application")
        pluginManager.apply(libs.pluginId("compose-compiler"))
        extensions.configure<ApplicationExtension> {
            configureKotlinAndroid(this)
            configureCompose(this)
            defaultConfig.targetSdk = SignerConfig.TARGET_SDK
            buildFeatures.buildConfig = true
        }
    }
}
