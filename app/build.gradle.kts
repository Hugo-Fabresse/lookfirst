import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        // Kotlin 2.2 changes the default annotation target; opt into the future behaviour now
        // so `@Inject constructor(@ApplicationContext ...)` doesn't warn on every DI class.
        freeCompilerArgs.add("-Xannotation-default-target=param-property")
    }
}

ktlint {
    version.set("1.5.0")
    android.set(true)
    ignoreFailures.set(false)
    filter {
        exclude { it.file.path.contains("/build/") }
    }
}

detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
    baseline = file("$rootDir/config/detekt/baseline.xml")
    // The detekt default ignores the build-type source sets; src/debug and src/release hold real code.
    source.setFrom(
        "src/main/java",
        "src/main/kotlin",
        "src/debug/java",
        "src/release/java",
        "src/test/java",
    )
}

/**
 * Dependency locking, limited to what ships in the APK (`releaseRuntimeClasspath`): the lockfile
 * (`app/gradle.lockfile`) is what osv-scanner reads. Build tooling, test, lint and ksp
 * classpaths are deliberately not locked. A stale lockfile fails the build; regenerate with:
 *   ./gradlew :app:dependencies --configuration releaseRuntimeClasspath --write-locks
 */
configurations.matching { it.name == "releaseRuntimeClasspath" }.configureEach {
    resolutionStrategy.activateDependencyLocking()
}

/**
 * Robolectric's android-all jar (sdk 34, see src/test/resources/robolectric.properties), resolved by Gradle so
 * that gradle/verification-metadata.xml checks it. Robolectric then runs offline (robolectric.offline) and reads
 * it from a plain directory (robolectric.dependency.dir) instead of downloading it into ~/.m2 on its own.
 */
val robolectricAndroidAll by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

val prepareRobolectricAndroidAll by tasks.registering(Sync::class) {
    from(robolectricAndroidAll)
    into(layout.buildDirectory.dir("robolectric-android-all"))
}

/**
 * Release guard (LF-23): the public keys pinned in PinnedKeys.kt are test keys (their private halves are in the
 * test sources) until LF-25 replaces them and flips KEYS_ARE_PRODUCTION. Any release build fails before then, so
 * an APK that trusts keys anybody can sign with cannot be produced. See docs/design/reputation-bundle.md,
 * "Production keys".
 */
val verifyPinnedKeysAreProduction by tasks.registering {
    val pinnedKeys = layout.projectDirectory.file("src/main/java/com/lookfirst/data/reputation/PinnedKeys.kt")
    inputs.file(pinnedKeys)
    doLast {
        val text = pinnedKeys.asFile.readText()
        val flagged = Regex("""const\s+val\s+KEYS_ARE_PRODUCTION\s*=\s*true\b""").containsMatchIn(text)
        if (!flagged) {
            throw GradleException(
                "PinnedKeys.kt still pins TEST keys (KEYS_ARE_PRODUCTION is not true): refusing a release build. " +
                    "Replace the keys and set the flag in LF-25 (docs/design/reputation-bundle.md).",
            )
        }
    }
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(verifyPinnedKeysAreProduction)
}

android {
    namespace = "com.lookfirst"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lookfirst"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // R8 on; proguard-rules.pro strips android.util.Log calls (threat model T-STO-3).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true // Robolectric needs the merged resources
            all {
                it.dependsOn(prepareRobolectricAndroidAll)
                it.inputs.files(prepareRobolectricAndroidAll)
                    .withPropertyName("robolectricAndroidAll")
                    .withPathSensitivity(PathSensitivity.RELATIVE)
                // Relative to the test working directory (the app module): no absolute path.
                it.systemProperty("robolectric.offline", "true")
                it.systemProperty("robolectric.dependency.dir", "build/robolectric-android-all")
            }
        }
    }

    lint {
        warningsAsErrors = false
        abortOnError = true
        error += "UnusedResources"
        // Dependency/plugin freshness is not a build concern here.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)

    // Camera + QR decoding on the device (D11: ZXing core, no Google services).
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)

    testImplementation(libs.junit)
    testImplementation(composeBom)
    testImplementation(libs.robolectric)
    testImplementation(libs.turbine)
    robolectricAndroidAll(libs.robolectric.android.all)
    testImplementation(libs.androidx.compose.ui.test.junit4)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
