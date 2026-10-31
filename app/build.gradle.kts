plugins {
    id("cleanyourtv.android.app")
    id("cleanyourtv.serialization")
}

android {
    // The app's version, in one place for every store flavour and for F-Droid's checkupdates
    // (STORES-PLAN 0 and 3): GitHub, F-Droid and Google Play ship the same versionCode and versionName.
    // 0.4.6.1 is the reconnect hotfix on 0.4.6 (four parts). It takes versionCode 23 because 22 is the
    // 0.4.7-beta1 test build signed with the same key, so a phone with that build never sees a downgrade.
    defaultConfig {
        // F-Droid checkupdates reads these first literal assignments.
        // GitHub's owned signed release can override them without changing
        // the version reported to F-Droid for normal builds.
        versionCode = 23
        versionName = "0.4.6.1"
        System.getenv("CLEAN_TV_VERSION_CODE")?.toIntOrNull()
            ?.takeIf { it in 1..2100000000 }
            ?.let { versionCode = it }
        System.getenv("CLEAN_TV_VERSION_NAME")?.takeIf { it.isNotBlank() }
            ?.let { versionName = it }
    }
    // The full fork uses a distinct package identity for side-by-side migration.
    // Consistent signing remains required for future in-place updates.
    // `full` (GitHub and F-Droid) checks GitHub for a newer release and offers the optional add-ons;
    // `play` (Google Play) has no update check, no add-on downloads, keeps the TV's software updaters
    // on and names no GitHub host. The differences live in src/full and src/play (Store.kt and the
    // network security config) and nowhere else.
    flavorDimensions += "store"
    productFlavors {
        create("full") {
            dimension = "store"
            // Independent identity: Clean-install can coexist with the old
            // Clean Your TV until its restoration history is exported.
            // The historical .cleaninstall APKs were distributed with Android
            // debug certificates. They cannot be upgraded with the owner's
            // permanent release key. A new stable package identity permits a
            // side-by-side installation without erasing the old app's data.
            // Never rotate this suffix or the signing key on subsequent builds.
            applicationIdSuffix = ".personal"
            isDefault = true
        }
        create("play") {
            dimension = "store"
        }
    }
    // Production signatures are used exclusively by fullRelease, via CYT_RELEASE_*.
    // The debug build intentionally retains its own disposable key.
    testOptions { unitTests { isIncludeAndroidResources = true } }
    // A text report per linted variant (build/reports/lint-results-<variant>.txt). No shared
    // textOutput: with two linted variants (fullDebug under `lint`, playDebug under `check`) their
    // report copies raced for one file and failed `check` at random (RF-STORES-030 M3).
    lint {
        textReport = true
    }
}

dependencies {
    implementation(project(":feature:connect"))
    implementation(project(":feature:setup"))
    implementation(project(":feature:manage"))
    implementation(project(":feature:launcher"))
    implementation(project(":feature:modules"))
    implementation(project(":feature:backup"))
    implementation(project(":ui:design"))
    implementation(project(":core:flows"))
    implementation(project(":android:work"))
    implementation(project(":android:platform"))
    implementation(project(":adb:session"))
    // T7: the real implementations behind Seams (layer 9 may use every lower layer).
    implementation(project(":adb:wire"))
    implementation(project(":core:ops"))
    implementation(project(":core:spec"))
    implementation(project(":core:probe"))
    implementation(project(":core:planner"))
    implementation(project(":core:engine"))
    implementation(project(":core:backup"))
    implementation(project(":core:trust"))
    implementation(project(":core:registry"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(project(":testing:fixtures"))
    testImplementation(project(":testing:fake-adbd"))
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.junit4)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// :benchmark measures the full build (it asks for store=full); the play benchmark variant is not
// built. Unit tests run on both flavours (testFullDebugUnitTest and testPlayDebugUnitTest under
// `check`); the benchmark build type is release code with the debug key and gets no unit tests.
androidComponents {
    beforeVariants(selector().withBuildType("benchmark")) { variant ->
        if (variant.productFlavors.contains("store" to "play")) variant.enable = false
        variant.hostTests[com.android.build.api.variant.HostTestBuilder.UNIT_TEST_TYPE]?.enable = false
    }
}

// Lint runs on the default flavour (full) with `lint`; the play sources and resources too.
tasks.named("check") { dependsOn("lintPlayDebug") }

// The size gate for every store build: the full APK and the play APK (its AAB holds the same code).
tasks.register("checkApkSizeRelease") {
    group = "verification"
    description = "Fails when the fullRelease or the playRelease APK is larger than the size gate."
    dependsOn("checkApkSizeFullRelease", "checkApkSizePlayRelease")
}

tasks.withType<Test>().configureEach {
    maxParallelForks = 1
    maxHeapSize = "1280m"
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
