plugins {
    id("com.android.application")
}
android {
    namespace = "io.github.opencodeandroid.diagnostics"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.opencodeandroid.diagnostics"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-test-only"
    }
    sourceSets.getByName("main").java.srcDir(rootProject.file("diagnostics-protocol/src/main/java"))
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    lint { abortOnError = true }
}
dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.2.10")
    implementation("androidx.lifecycle:lifecycle-common:2.9.2")
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("androidx.test:runner:1.7.0")
    implementation("androidx.test.ext:junit:1.3.0")
    implementation("junit:junit:4.13.2")
}
