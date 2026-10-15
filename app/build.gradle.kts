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
        }
    }

    lint {
        warningsAsErrors = false
        abortOnError = true
        error += "UnusedResources"
        // Dependency/plugin freshness is not a build concern here.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

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
    testImplementation(libs.androidx.compose.ui.test.junit4)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
