plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// T9.2 (D-3): release signing reads a keystore that lives OUTSIDE the
// repository. No secret is in the build script and none is committed. Provide
// all four values as Gradle properties (~/.gradle/gradle.properties, never in
// this repo, or -P on the command line) or as environment variables:
//
//   OFFLINE_WORKOUT_KEYSTORE          absolute path to the .jks/.keystore
//   OFFLINE_WORKOUT_KEYSTORE_PASSWORD store password
//   OFFLINE_WORKOUT_KEY_ALIAS        key alias
//   OFFLINE_WORKOUT_KEY_PASSWORD     key password
//
// Gradle properties win over environment variables. Debug builds never look at
// any of this, so the daily gate (assembleDebug/lintDebug/testDebugUnitTest)
// works on a machine that has no keystore at all.
fun releaseSigningValue(propertyName: String, envName: String): String? =
    providers.gradleProperty(propertyName).orNull?.takeIf { it.isNotBlank() }
        ?: System.getenv(envName)?.takeIf { it.isNotBlank() }

val releaseStorePath = releaseSigningValue("OFFLINE_WORKOUT_KEYSTORE", "OFFLINE_WORKOUT_KEYSTORE")
val releaseStorePassword = releaseSigningValue(
    "OFFLINE_WORKOUT_KEYSTORE_PASSWORD",
    "OFFLINE_WORKOUT_KEYSTORE_PASSWORD"
)
val releaseKeyAlias = releaseSigningValue("OFFLINE_WORKOUT_KEY_ALIAS", "OFFLINE_WORKOUT_KEY_ALIAS")
val releaseKeyPassword = releaseSigningValue("OFFLINE_WORKOUT_KEY_PASSWORD", "OFFLINE_WORKOUT_KEY_PASSWORD")
val releaseSigningConfigured = listOf(
    releaseStorePath,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).all { it != null }

// Building a release artifact without the four values would silently produce
// an unsigned APK, so that is a hard failure with instructions instead.
//
// T9.3 (bloqueio 2): the first version of this guard only fired when the task
// NAME spelled "release", so `:app:assemble` and `:app:build` — which both
// build the release variant — produced an unsigned APK in silence. The guard is
// now in two layers:
//
//   1. a fast configuration-time check for the task names people actually type,
//      including the aggregates that do not mention "release";
//   2. a `doFirst` on every task that really produces a release artifact, which
//      is the authoritative one: it fires for `:app:assemble`, `:app:build`,
//      `assembleAll`, `assembleRelease`, the IDE, and any alias, because those
//      tasks are the ones that would write the unsigned file.
val releaseArtifactPrefixes = listOf("assemble", "bundle", "package", "publish")
fun releaseArtifactTask(name: String): Boolean = run {
    val lower = name.lowercase()
    lower.contains("release") ||
        (releaseArtifactPrefixes.any { lower.startsWith(it) } && !lower.contains("debug"))
}
val wantsReleaseArtifact = gradle.startParameter.taskNames.any { releaseArtifactTask(it) }
if (wantsReleaseArtifact && !releaseSigningConfigured) {
    throw GradleException(releaseSigningMessage())
}

android {
    namespace = "com.offlineworkout"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.offlineworkout"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = file(releaseStorePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
            // T9.2 (D-3): signing only, no R8 yet. Turning minify/shrink on
            // later requires rerunning the whole suite, because
            // UiBoundaryTest and RepositoryBoundaryTest resolve classes by
            // name through reflection.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint {
        abortOnError = true
        warningsAsErrors = false
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // Robolectric roots its sandbox at java.io.tmpdir. On macOS that
            // path is a symlink (/var -> /private/var) while FileProvider
            // canonicalizes the candidate file but not the configured
            // cache-path root, so every provider lookup would miss. Pin a
            // canonical root for the test JVM.
            it.systemProperty("java.io.tmpdir", File(System.getProperty("java.io.tmpdir")).canonicalPath)
        }
    }
}

// Authoritative guard: every task that would write a release artifact fails
// before doing any work when signing is not configured.
if (!releaseSigningConfigured) {
    tasks.matching { task -> releaseArtifactTask(task.name) && task.name.contains("Release") }
        .configureEach {
            doFirst { throw GradleException(releaseSigningMessage()) }
        }
}

fun releaseSigningMessage(): String =
    "Release signing is not configured. Set OFFLINE_WORKOUT_KEYSTORE, " +
        "OFFLINE_WORKOUT_KEYSTORE_PASSWORD, OFFLINE_WORKOUT_KEY_ALIAS and " +
        "OFFLINE_WORKOUT_KEY_PASSWORD as Gradle properties (~/.gradle/" +
        "gradle.properties or -P) or as environment variables. No keystore " +
        "and no secret belong in this repository. Debug builds never need them."

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(libs.androidx.compose.ui)
    // T9.2 (R-2): appcompat and ui-tooling-preview were never referenced by a
    // single import or annotation; they were template leftovers. Both are gone
    // from the release graph — see the README for the APK size before/after.
    implementation(libs.androidx.compose.material3)

    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    // Merged into the debug manifest so Robolectric resolves the test activity.
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
