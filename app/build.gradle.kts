import com.google.android.gms.oss.licenses.plugin.LicensesTask
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import groovy.json.JsonOutput

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.oss.licenses)
}

/** AGP omits its dependency report for debuggable variants; keep their notices real. */
@CacheableTask
abstract class DebugLicenseDependenciesTask : DefaultTask() {
    @get:Input abstract val coordinates: ListProperty<String>
    @get:OutputFile abstract val outputFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val dependencies = coordinates.get().distinct().sorted().map { coordinate ->
            val parts = coordinate.split(':', limit = 3)
            mapOf("group" to parts[0], "name" to parts[1], "version" to parts[2])
        }
        outputFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(JsonOutput.toJson(dependencies))
        }
    }
}

androidComponents.onVariants(androidComponents.selector().withBuildType("debug")) { variant ->
    val artifacts = variant.runtimeConfiguration.incoming.artifactView {
        componentFilter { it is ModuleComponentIdentifier }
    }.artifacts.resolvedArtifacts
    val dependencyReport = tasks.register<DebugLicenseDependenciesTask>("${variant.name}LicenseDependencies") {
        coordinates.set(artifacts.map { resolved ->
            resolved.map { artifact ->
                val id = artifact.id.componentIdentifier as ModuleComponentIdentifier
                "${id.group}:${id.module}:${id.version}"
            }
        })
        outputFile.set(layout.buildDirectory.file("generated/mindkit_licenses/${variant.name}/dependencies.json"))
    }
    tasks.named<LicensesTask>("${variant.name}OssLicensesTask") {
        dependenciesJson.set(dependencyReport.flatMap { it.outputFile })
    }
}

android {
    namespace = "com.localai.toolkit"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.localai.toolkit"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "com.localai.toolkit.HiltTestRunner"

        // Room schema export keeps migration history reviewable in source control.
        ksp { arg("room.schemaLocation", "$projectDir/schemas") }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/LICENSE.md",
            "/META-INF/LICENSE-notice.md",
        )
    }

    androidResources {
        generateLocaleConfig = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.oss.licenses)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.documentfile)

    implementation(libs.kotlinx.coroutines.android)
    // Bridges ML Kit's ListenableFuture APIs into coroutines.
    implementation(libs.kotlinx.coroutines.guava)
    // Bridges Play services Task<T> (ML Kit vision + translation) into coroutines.
    implementation(libs.kotlinx.coroutines.play.services)

    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    ksp(libs.hilt.compiler)

    // On-device ML Kit. None of these require an API key or a Google account.
    implementation(libs.mlkit.text.recognition)
    implementation(libs.mlkit.translate)
    implementation(libs.mlkit.language.id)

    // ML Kit GenAI (Gemini Nano via AICore). Availability is device dependent and is
    // always resolved at runtime - see DeviceAiCapabilityManager.
    implementation(libs.mlkit.genai.common)
    implementation(libs.mlkit.genai.prompt)
    implementation(libs.mlkit.genai.summarization)
    implementation(libs.mlkit.genai.rewriting)
    implementation(libs.mlkit.genai.proofreading)
    implementation(libs.mlkit.genai.image.description)
    implementation(libs.mlkit.genai.speech.recognition)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.room.testing)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.compiler)
}

/*
 * Robolectric refuses to build a sandbox for Android SDK 37 on a JVM older than 21, so
 * unit tests run on Java 21 while the app itself still compiles to Java 17 bytecode
 * (see android.compileOptions above). Only the test JVM changes; the shipped APK does not.
 */
val javaToolchainService = extensions.getByType<JavaToolchainService>()
tasks.withType<Test>().configureEach {
    javaLauncher.set(
        javaToolchainService.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        },
    )
    // Robolectric reaches into java.base internals (FileDescriptor, ClassLoader) to
    // emulate the Android runtime. The module system blocks that by default on JDK 17+.
    jvmArgs(
        "--add-opens=java.base/java.io=ALL-UNNAMED",
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.util=ALL-UNNAMED",
        "--add-opens=java.base/java.net=ALL-UNNAMED",
        "--add-opens=java.base/java.nio=ALL-UNNAMED",
        "--add-opens=java.base/java.text=ALL-UNNAMED",
    )
}
