// Explicit: inside a task block `java` resolves to Gradle's JavaPluginExtension,
// not the package, so fully-qualified java.* references fail to compile there.
import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val appVersionCode = 1
val appVersionName = "0.1.0"

val gitSha: String = providers.exec {
    commandLine("git", "rev-parse", "--short", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim().ifEmpty { "local" } }.orElse("local").get()

/**
 * sherpa-onnx is not published to Maven Central — the only artifact there is an
 * unofficial third-party wrapper. The supported distribution is a prebuilt AAR
 * from GitHub Releases, so it is fetched and checksum-verified here rather than
 * committed (36 MB) or resolved from an untrusted coordinate.
 *
 * The static-link build embeds onnxruntime, so there is no second native
 * dependency to keep in step.
 */
val sherpaVersion = libs.versions.sherpaOnnx.get()
val sherpaAar = layout.projectDirectory.file("libs/sherpa-onnx-$sherpaVersion.aar").asFile
val sherpaSha256 = "dc5ac19a28dee3bffc5e5a5d50cb6afa977703fc4a7ee535a308506990fdd295"

val fetchSherpaOnnx by tasks.registering {
    outputs.file(sherpaAar)
    doLast {
        fun sha256(f: File) = MessageDigest.getInstance("SHA-256")
            .digest(f.readBytes()).joinToString("") { b -> "%02x".format(b) }

        if (sherpaAar.exists() && sha256(sherpaAar) == sherpaSha256) return@doLast

        sherpaAar.parentFile.mkdirs()
        val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/" +
            "v$sherpaVersion/sherpa-onnx-static-link-onnxruntime-$sherpaVersion.aar"
        logger.lifecycle("Fetching sherpa-onnx $sherpaVersion…")
        val tmp = File(sherpaAar.parentFile, sherpaAar.name + ".part")
        URI(url).toURL().openStream().use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
        }
        val actual = sha256(tmp)
        check(actual == sherpaSha256) {
            "sherpa-onnx AAR checksum mismatch: expected $sherpaSha256, got $actual"
        }
        tmp.renameTo(sherpaAar)
    }
}

tasks.named("preBuild") { dependsOn(fetchSherpaOnnx) }

android {
    namespace = "org.lsrith.acousticmonitor"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.lsrith.acousticmonitor"
        minSdk = 29
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "GIT_SHA", "\"$gitSha\"")

        ndk {
            // Every target device for this spike is arm64. Shipping the other
            // ABIs quadruples the APK for no benefit.
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        jniLibs {
            // sherpa-onnx's static-link AAR still ships a stray libonnxruntime.so
            // for x86 only — a packaging quirk of their release — which collides
            // with onnxruntime-android's copy. arm64-v8a has no duplicate, and
            // this build ships arm64 exclusively, so neither x86 file ever
            // reaches a device.
            pickFirsts += "lib/x86/libonnxruntime.so"
        }
    }
}

/**
 * Produces a named APK under dist/ so a build handed over for testing is
 * identifiable after the fact. The bare Gradle output is always `app-debug.apk`,
 * which makes two builds indistinguishable once they leave this machine.
 */
tasks.register<Copy>("distDebug") {
    dependsOn("assembleDebug")
    from(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk"))
    into(rootProject.layout.projectDirectory.dir("dist"))
    rename { "acoustic-monitor-$appVersionName-$gitSha.apk" }
}

dependencies {
    // A second automated gate. assembleDebug only proves the code compiles;
    // these prove the decision arithmetic and the model-reply parser still do
    // what the requirements say, both of which have silently regressed before.
    testImplementation(libs.kotlin.test.junit)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.mediapipe.tasks.audio)
    implementation(files(sherpaAar))
    implementation(libs.commons.compress)
    implementation(libs.onnxruntime.android)
    implementation(libs.litertlm.android)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
