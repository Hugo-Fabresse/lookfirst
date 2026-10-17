plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.ktlint) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.cpd)
}

/**
 * Duplicate-code gate (PMD CPD), blocking: `cpdCheck` fails on ANY duplicate of >= `minimumTokenCount`
 * tokens (100). Factor the duplicate out rather than raising the threshold.
 */
cpd {
    toolVersion = libs.versions.pmd.get()
    language = "kotlin"
    minimumTokenCount = 100
    isIgnoreFailures = false
    encoding = "UTF-8"
}
tasks.named<de.aaschmid.gradle.plugins.cpd.Cpd>("cpdCheck") {
    source = files("app/src/main/java", "app/src/debug/java", "app/src/release/java").asFileTree.matching {
        include("**/*.kt")
    }
    reports {
        xml.required.set(true)
        text.required.set(true)
    }
}
