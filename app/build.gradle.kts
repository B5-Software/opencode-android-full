import java.security.MessageDigest
import java.util.zip.GZIPInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.github.opencodeandroid"
    compileSdk = 36
    ndkVersion = "26.1.10909125"
    defaultConfig {
        applicationId = "io.github.opencodeandroid"
        minSdk = 29
        targetSdk = 36
        versionCode = providers.gradleProperty("appVersionCode").orElse("1").get().toInt().also { require(it > 0) }
        versionName = providers.gradleProperty("appVersionName").orElse("0.1.0-dev").get()
        ndk { abiFilters += "arm64-v8a" }
        testInstrumentationRunner = "io.github.opencodeandroid.ui.IsolatedWebTestRunner"
    }
    // x86_64 is an explicit host-side diagnostic build; distributed APKs remain ARM64.
    if (providers.gradleProperty("diagnosticX86").orNull == "true") {
        defaultConfig.ndk { abiFilters.clear(); abiFilters += "x86_64" }
    }
    buildFeatures { compose = true; buildConfig = true }
    androidResources { noCompress += "pack" }
    sourceSets.getByName("main").assets.srcDir("runtime-assets/" + if (providers.gradleProperty("diagnosticX86").orNull == "true") "x86_64" else "arm64-v8a")
    sourceSets.getByName("debug").java.srcDir(rootProject.file("diagnostics-protocol/src/main/java"))
    packaging {
        jniLibs { useLegacyPackaging = true; keepDebugSymbols += "**/libproot*.so" }
        resources.merges += setOf("META-INF/services/kotlinx.coroutines.CoroutineExceptionHandler", "META-INF/services/kotlinx.coroutines.internal.MainDispatcherFactory")
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildTypes { release { isMinifyEnabled = false } }
    lint { abortOnError = true }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

val verifyBundledRuntime by tasks.registering {
    doLast {
        val diagnostic = providers.gradleProperty("diagnosticX86").orNull == "true"
        val abi = if (diagnostic) "x86_64" else "arm64-v8a"
        val directory = file("runtime-assets/$abi/runtime")
        val manifest = file("runtime-assets/$abi/runtime/pack.json")
        check(manifest.isFile) { "Prepare the pinned runtime assets; see docs/DEVELOPER_PACK.md" }
        @Suppress("UNCHECKED_CAST")
        val pack = groovy.json.JsonSlurper().parse(manifest) as Map<String, Any>
        check(pack["abi"] == abi && File(directory, "debian.pack").isFile && File(directory, "opencode.pack").isFile)
        if (!diagnostic) {
            val developer = pack["developer"] as? Map<*, *>
            val packages = developer?.get("packages") as? List<*>
            check(packages != null && packages.containsAll(listOf("curl", "wget", "git", "gcc", "g++", "clang", "nodejs", "npm", "python3-venv", "python3-pip", "rustc", "cargo"))) {
                "ARM64 APK requires the bundled developer suite. Run tools/bundle_developer.py; see docs/DEVELOPER_PACK.md"
            }
        }
    }
}
tasks.named("preBuild").configure { dependsOn(verifyBundledRuntime) }

/** Debug uses the SDK identity; release proofs are injected by the isolated CI signing job. */
abstract class GenerateDebugCoreProof : DefaultTask() {
    @get:org.gradle.api.tasks.InputFile abstract val packFile: org.gradle.api.file.RegularFileProperty
    @get:org.gradle.api.tasks.InputFile abstract val payloadFile: org.gradle.api.file.RegularFileProperty
    @get:org.gradle.api.tasks.InputFile abstract val lockFile: org.gradle.api.file.RegularFileProperty
    @get:org.gradle.api.tasks.InputFile abstract val signerSource: org.gradle.api.file.RegularFileProperty
    @get:org.gradle.api.tasks.InputFile abstract val keyStore: org.gradle.api.file.RegularFileProperty
    @get:org.gradle.api.tasks.Input abstract val alias: org.gradle.api.provider.Property<String>
    @get:org.gradle.api.tasks.Internal abstract val storePassword: org.gradle.api.provider.Property<String>
    @get:org.gradle.api.tasks.Internal abstract val keyPassword: org.gradle.api.provider.Property<String>
    @get:org.gradle.api.tasks.OutputDirectory abstract val outputDirectory: org.gradle.api.file.DirectoryProperty
    @get:javax.inject.Inject abstract val processes: org.gradle.process.ExecOperations
    @org.gradle.api.tasks.TaskAction fun generate() {
        @Suppress("UNCHECKED_CAST")
        val pack = groovy.json.JsonSlurper().parse(packFile.get().asFile) as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val lock = groovy.json.JsonSlurper().parse(lockFile.get().asFile) as Map<String, Any>
        val core = pack["opencode"] as Map<*, *>
        check(pack["abi"] == "arm64-v8a" && core["commit"] == lock["commit"] && core["version"] == lock["buildVersion"])
        val raw = MessageDigest.getInstance("SHA-256"); var count = 0L
        GZIPInputStream(payloadFile.get().asFile.inputStream()).use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; count += n; check(count <= 1024L * 1024 * 1024); raw.update(buffer, 0, n) }
        }
        fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
        val rawSha = hex(raw.digest()); check(rawSha == core["sha256"] && count == core["size"].toString().toLong())
        val compressed = MessageDigest.getInstance("SHA-256")
        payloadFile.get().asFile.inputStream().use { input -> val buffer = ByteArray(1024 * 1024); while (true) { val n = input.read(buffer); if (n < 0) break; compressed.update(buffer, 0, n) } }
        val metadata = linkedMapOf("format" to 1, "kind" to "opencode-pair", "abi" to "arm64-v8a", "minHostVersionCode" to 1,
            "repository" to lock["repository"], "version" to core["version"], "commit" to core["commit"],
            "binary" to mapOf("size" to count, "sha256" to rawSha),
            "archive" to mapOf("size" to payloadFile.get().asFile.length(), "sha256" to hex(compressed.digest())))
        val directory = outputDirectory.get().dir("runtime").asFile.apply { mkdirs() }
        val manifest = File(directory, "opencode-publisher.json")
        manifest.writeText(groovy.json.JsonOutput.toJson(metadata) + "\n", Charsets.UTF_8)
        val javaCommand = File(System.getProperty("java.home"), "bin/" + if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
        processes.exec {
            commandLine(javaCommand, signerSource.get().asFile, keyStore.get().asFile, manifest, File(directory, "opencode-publisher.sig"))
            environment("ANDROID_KEYSTORE_PASSWORD", storePassword.get()); environment("ANDROID_KEY_PASSWORD", keyPassword.get()); environment("ANDROID_KEY_ALIAS", alias.get())
        }
    }
}

if (providers.gradleProperty("diagnosticX86").orNull != "true") {
    val generateDebugCoreProof = tasks.register<GenerateDebugCoreProof>("generateDebugCoreProof") {
        dependsOn("validateSigningDebug")
        packFile.set(layout.projectDirectory.file("runtime-assets/arm64-v8a/runtime/pack.json"))
        payloadFile.set(layout.projectDirectory.file("runtime-assets/arm64-v8a/runtime/opencode.pack"))
        lockFile.set(rootProject.layout.projectDirectory.file("runtime/opencode-dev.lock.json"))
        signerSource.set(rootProject.layout.projectDirectory.file("tools/SignUpdate.java"))
        val signing = android.signingConfigs.getByName("debug")
        keyStore.set(signing.storeFile!!); alias.set(signing.keyAlias!!)
        storePassword.set(signing.storePassword!!); keyPassword.set(signing.keyPassword!!)
        outputDirectory.set(layout.buildDirectory.dir("generated/debug-core-proof"))
    }
    androidComponents.onVariants(androidComponents.selector().withBuildType("debug")) { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(generateDebugCoreProof) { it.outputDirectory }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.08.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // Android instrumentation uses a separate class loader. Coroutines' service
    // lookup uses the app loader, so its test collector must be in the debug APK.
    // Release APKs contain neither the collector nor the test dispatcher.
    debugImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("com.google.crypto.tink:tink-android:1.24.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.08.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.espresso:espresso-intents:3.7.0")
    androidTestImplementation("com.google.errorprone:error_prone_annotations:2.36.0")
}
