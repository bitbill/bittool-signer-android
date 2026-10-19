pluginManagement {
    includeBuild("build-logic")
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "bittool-signer"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(":app")
include(":core:common")
include(":core:model")
include(":core:l10n")
include(":core:designsystem")
include(":core:engine")
include(":core:crypto")
include(":core:data")
include(":core:security")
include(":core:scanner")
include(":core:ui")
include(":core:navigation")
include(":feature:onboarding")
include(":feature:setup")
include(":feature:wallets")
include(":feature:sign")
include(":feature:activity")
include(":feature:settings")
