plugins {
    id("com.android.application")
}

android {
    namespace = "com.cryonum.captusbprint"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.cryonum.captusbprint"
        minSdk = 29
        targetSdk = 36
        // The GitHub Actions run number is monotonically increasing for private CI APKs.
        // Local debug builds are deliberately not a stable upgrade channel.
        val ciRunNumber = providers.environmentVariable("GITHUB_RUN_NUMBER")
            .orNull?.toIntOrNull()?.takeIf { it > 0 }
        versionCode = ciRunNumber ?: 1
        versionName = if (ciRunNumber != null) "0.1.0-a1.$ciRunNumber" else "0.1.0-a1.local"

        val revision = providers.environmentVariable("GITHUB_SHA")
            .orElse("local-source").get().take(12)
        buildConfigField("String", "SOURCE_REVISION", "\"$revision\"")
    }

    buildTypes {
        release { isMinifyEnabled = false }
        debug { applicationIdSuffix = ".debug" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { buildConfig = true }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
