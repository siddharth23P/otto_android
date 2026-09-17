import java.net.URI
import java.security.MessageDigest
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.chaquopy)
}

// The embedded Python runtime is opt-in until the Android wheel set is published
// (see .github/workflows/wheels.yml). With it off, Chaquopy is still present (the
// bridge compiles) but installs nothing, and the app reports "embedded runtime
// unavailable" and offers otto serve.
val embeddedPython = (project.findProperty("embeddedPython") as String?)?.toBoolean() ?: false

// Which ABIs one build carries: both by default; a release builds one APK per ABI with
// -Pabi=arm64-v8a or -Pabi=x86_64, each half the size of the two together.
val abis = (project.findProperty("abi") as String?)?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
    ?: listOf("arm64-v8a", "x86_64")

// Release signing comes from the building machine's ~/.gradle/gradle.properties, never from the
// repository. Without those properties a release build is left unsigned.
val releaseStore = project.findProperty("OTTO_RELEASE_STORE_FILE") as String?

// Where the wheel set lands for pip. The binaries are not in the repository:
// wheels/wheels.json names the release that holds them and the digest of each,
// and `fetchWheels` puts them here -- from ../wheels when a build already has
// them (the machine that built them, or an offline build), else downloaded.
val wheelhouse = layout.buildDirectory.dir("wheels")

val fetchWheels = tasks.register("fetchWheels") {
    description = "Downloads the Android wheel set named by wheels/wheels.json, checking every digest."
    group = "build"
    val manifestFile = rootProject.file("wheels/wheels.json")
    val local = rootProject.file("wheels")
    val target = wheelhouse
    inputs.file(manifestFile)
    outputs.dir(target)
    doLast {
        val manifest = groovy.json.JsonSlurper().parse(manifestFile) as Map<*, *>
        val repository = manifest["repository"] as String
        val tag = manifest["tag"] as String
        val out = target.get().asFile.apply { mkdirs() }
        for (listed in manifest["files"] as List<*>) {
            val entry = listed as Map<*, *>
            val name = entry["name"] as String
            val want = entry["sha256"] as String
            val file = File(out, name)
            if (file.exists() && sha256(file) == want) continue
            val beside = File(local, name)
            if (beside.exists() && sha256(beside) == want) {
                beside.copyTo(file, overwrite = true)
                continue
            }
            val url = "https://github.com/$repository/releases/download/$tag/$name"
            logger.lifecycle("fetching $name")
            URI(url).toURL().openStream().use { input -> file.outputStream().use { output -> input.copyTo(output) } }
            val got = sha256(file)
            if (got != want) {
                file.delete()
                throw GradleException("$name from $url is not the file wheels/wheels.json names ($got, expected $want)")
            }
        }
    }
}

fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
    .digest(file.readBytes()).joinToString("") { byte -> "%02x".format(byte) }

android {
    namespace = "dev.otto.phone"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.otto.phone"
        minSdk = 30
        targetSdk = 35
        versionCode = 3
        versionName = "0.1.2"
        ndk { abiFilters += abis }
        buildConfigField("boolean", "EMBEDDED_PYTHON", embeddedPython.toString())
        // Whether files/otto_fake_model may switch otto's pipeline for a script (#14): never in release.
        buildConfigField("boolean", "FAKE_MODEL_ALLOWED", "true")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseStore != null) {
            create("release") {
                storeFile = file(releaseStore)
                storePassword = project.findProperty("OTTO_RELEASE_STORE_PASSWORD") as String?
                keyAlias = project.findProperty("OTTO_RELEASE_KEY_ALIAS") as String?
                keyPassword = project.findProperty("OTTO_RELEASE_KEY_PASSWORD") as String?
            }
        }
    }

    buildTypes {
        release {
            if (releaseStore != null) signingConfig = signingConfigs.getByName("release")
            // Shrinking takes ~20 MB of unused library code out of each APK (#14's size budget);
            // proguard-rules.pro keeps names, and what Python calls.
            isMinifyEnabled = true
            isShrinkResources = true
            buildConfigField("boolean", "FAKE_MODEL_ALLOWED", "false")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // The release build, shrunk exactly as release is, but signed with the debug key and allowed the
        // fake model: what the emulator job runs the smoke test against, so shrinking cannot break a
        // release unseen (#14).
        create("minified") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            buildConfigField("boolean", "FAKE_MODEL_ALLOWED", "true")
            proguardFile("proguard-minified-rules.pro")
            testProguardFiles("proguard-test-rules.pro")
            matchingFallbacks += listOf("release")
        }
    }
    // Instrumented tests run against debug unless -PtestBuildType=minified asks for the shrunk build.
    testBuildType = (project.findProperty("testBuildType") as String?) ?: "debug"
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

kotlin {
    compilerOptions { jvmTarget = JvmTarget.JVM_17 }
}

chaquopy {
    defaultConfig {
        version = "3.13"
        // The build-time interpreter: `python3.13` on PATH (actions/setup-python
        // in CI; pyenv or python.org locally). One name: extra arguments are a
        // command line to Chaquopy, not fallbacks.
        buildPython("python3.13")
        if (embeddedPython) {
            pip {
                // One options() call: Chaquopy passes these to pip as written, and constraints pin the
                // compiled packages to the versions the wheel set carries.
                options("--find-links", wheelhouse.get().asFile.absolutePath,
                        "-c", file("src/main/python/constraints.txt").absolutePath)
                install("-r", "src/main/python/requirements.txt")
            }
        }
        pyc {
            src = true
            pip = true
        }
    }
}

// Chaquopy's pip runs against the wheel set: it waits for it, and a changed set is a changed input.
// Waiting alone was not enough -- a rebuilt otto wheel of the same version left the install task up
// to date, and the APK kept the old otto (2026-09-16).
if (embeddedPython) {
    tasks.matching { it.name.contains("PythonRequirements") || it.name.contains("PythonPackages") }
        .configureEach {
            dependsOn(fetchWheels)
            inputs.dir(wheelhouse)
        }
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.markdown.renderer.m3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.datastore.preferences)
    // Pairing by QR (#16): Google's scanner UI, which needs no camera permission of Otto's own.
    implementation(libs.code.scanner)
    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
