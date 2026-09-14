import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.chaquopy)
}

// The embedded Python runtime is opt-in until wheels/ has otto's compiled
// dependencies built for Android (see .github/workflows/wheels.yml). With it
// off, Chaquopy is still present (the bridge compiles) but installs nothing,
// and the app reports "embedded runtime unavailable" and offers otto serve.
val embeddedPython = (project.findProperty("embeddedPython") as String?)?.toBoolean() ?: false

android {
    namespace = "dev.otto.phone"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.otto.phone"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        buildConfigField("boolean", "EMBEDDED_PYTHON", embeddedPython.toString())
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
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
                options("--find-links", file("../wheels").absolutePath)
                install("-c", "src/main/python/constraints.txt")
                install("-r", "src/main/python/requirements.txt")
            }
        }
        pyc {
            src = true
            pip = true
        }
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
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.datastore.preferences)
    testImplementation(libs.junit)
}
