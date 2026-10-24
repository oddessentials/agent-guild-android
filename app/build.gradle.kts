plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.oddessentials.agentguild"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.oddessentials.agentguild"
        minSdk = 29
        targetSdk = 37
        // A release passes its version as -PagentGuildVersion=<major>.<minor>.<patch>.
        val release = providers.gradleProperty("agentGuildVersion").orNull
        versionCode = if (release == null) 1 else versionCodeOf(release)
        versionName = release ?: "0.0.0-development"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.webkit)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.barcode.scanning)
    implementation(libs.datastore)
    implementation(libs.browser)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
    debugImplementation(libs.compose.ui.test.manifest)
}

/**
 * major * 1,000,000 + minor * 1,000 + patch, so the code rises with every release. Android's
 * largest versionCode is 2,100,000,000, which caps major at 2099; minor and patch stop at 999.
 * tools/release/version.mjs computes the same code.
 */
fun versionCodeOf(version: String): Int {
    val parts = Regex("""(0|[1-9][0-9]{0,3})\.(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,2})""").matchEntire(version)
        ?.groupValues?.drop(1)?.map(String::toInt)
    if (parts == null || parts[0] > 2099 || parts.all { it == 0 }) {
        throw GradleException(
            "agentGuildVersion must be major.minor.patch between 0.0.1 and 2099.999.999, such as 1.2.3; \"$version\" is not.",
        )
    }
    val (major, minor, patch) = parts
    return major * 1_000_000 + minor * 1_000 + patch
}
