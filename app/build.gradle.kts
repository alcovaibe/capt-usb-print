plugins {
    id("com.android.application")
}

val releaseKeystorePath = providers.environmentVariable("RELEASE_KEYSTORE_PATH").orNull

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
        val signedVersionCode = providers.environmentVariable("APP_VERSION_CODE")
            .orNull?.toIntOrNull()
        versionCode = signedVersionCode ?: ciRunNumber ?: 1
        versionName = if (ciRunNumber != null) "0.1.0-a2.$ciRunNumber" else "0.1.0-a2.local"
        require(versionCode in 1..2_100_000_000) { "Invalid versionCode" }

        val revision = providers.environmentVariable("GITHUB_SHA")
            .orElse("local-source").get().take(12)
        buildConfigField("String", "SOURCE_REVISION", "\"$revision\"")
    }

    signingConfigs {
        // Permanent private key is restored only on trusted CI main builds.
        if (releaseKeystorePath != null) {
            create("permanent") {
                storeFile = file(releaseKeystorePath)
                storePassword = providers.environmentVariable("RELEASE_STORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("RELEASE_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("RELEASE_STORE_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            if (releaseKeystorePath != null) signingConfig = signingConfigs.getByName("permanent")
            isMinifyEnabled = false
        }
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
