import java.time.Duration
import java.util.Properties
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "-opt-in=kotlinx.serialization.ExperimentalSerializationApi",
        )
    }
}

// The Google Play upload key is never in the repository. It comes from keystore.properties
// (git-ignored) or from the environment: storeFile, storePassword, keyAlias, keyPassword,
// or HUDSON_STOREFILE, HUDSON_STOREPASSWORD, HUDSON_KEYALIAS, HUDSON_KEYPASSWORD.
val uploadProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun uploadSecret(name: String): String? =
    (uploadProps.getProperty(name) ?: System.getenv("HUDSON_" + name.uppercase()))?.takeIf { it.isNotBlank() }
val uploadStore = uploadSecret("storeFile")

android {
    namespace = "app.hudson"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "app.hudson"
        // Dynamic colour in widgets needs Android 12; the phone runs Android 17.
        minSdk = 31
        targetSdk = 36
        versionCode = 21
        versionName = "2.0.1"
    }

    signingConfigs {
        // One fixed key for every build so each update installs over the last and keeps its data.
        // Personal sideload key: do NOT reuse it for a Play Store release.
        create("sideload") {
            storeFile = file("hudson.keystore")
            storePassword = "hudson-sideload"
            keyAlias = "hudson"
            keyPassword = "hudson-sideload"
        }
        // The key Google Play knows this developer by. Only present on a machine that has been given it.
        create("upload") {
            if (uploadStore != null) {
                storeFile = file(uploadStore)
                storePassword = uploadSecret("storePassword")
                keyAlias = uploadSecret("keyAlias")
                keyPassword = uploadSecret("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            versionNameSuffix = "-debug"
            signingConfig = signingConfigs.getByName("sideload")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("sideload")
        }
        // The same optimised build as release, signed for Google Play: ./gradlew :app:bundlePlay
        create("play") {
            initWith(getByName("release"))
            matchingFallbacks += "release"
            signingConfig = signingConfigs.getByName("upload")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(kotlin("test-junit"))
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.roborazzi)
}

tasks.withType<Test>().configureEach {
    maxHeapSize = "2g"
    systemProperty("robolectric.pixelCopyRenderMode", "hardware")
    systemProperty("roborazzi.test.record", "true")
    timeout.set(Duration.ofMinutes(15))
    testLogging {
        events("failed")
        exceptionFormat = TestExceptionFormat.FULL
        showStandardStreams = true
    }
}

// The sideload key is public, so a bundle signed with it must never reach Google Play:
// building the Play bundle without the real upload key is an error, not a fallback.
tasks.matching { it.name == "bundlePlay" || it.name == "assemblePlay" }.configureEach {
    doFirst {
        check(uploadStore != null) {
            "No Google Play upload key configured. See docs/play/PUBLISHING.md (keystore.properties or HUDSON_STOREFILE...)."
        }
    }
}

