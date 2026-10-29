pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Public Maven Central endpoint for newly pinned backup crypto dependencies.
        maven("https://repo1.maven.org/maven2") {
            name = "backupCryptoCentral"
            content {
                includeGroup("com.google.crypto.tink")
                includeGroup("com.google.code.gson")
                includeGroup("com.google.errorprone")
                includeGroup("org.sonatype.oss")
            }
        }
        google(); mavenCentral()
    }
}
rootProject.name = "OpenCodeAndroidFull"
include(":app")
if (providers.gradleProperty("diagnosticsController").orNull == "true") include(":diagnostics-controller")
