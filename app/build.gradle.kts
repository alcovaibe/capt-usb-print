plugins {
    alias(libs.plugins.android.application)
}

val releaseKeystorePath = providers.environmentVariable("RELEASE_KEYSTORE_PATH").orNull

android {
    namespace = "com.cryonum.captusbprint"
    compileSdk = libs.versions.compileSdk.get().toInt()
    ndkVersion = "27.2.12479018"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    defaultConfig {
        applicationId = "com.cryonum.captusbprint"
        minSdk = libs.versions.minSdk.get().toInt()
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        targetSdk = libs.versions.targetSdk.get().toInt()
        // The GitHub Actions run number is monotonically increasing for private CI APKs.
        // Local debug builds are deliberately not a stable upgrade channel.
        val ciRunNumber = providers.environmentVariable("GITHUB_RUN_NUMBER")
            .orNull?.toIntOrNull()?.takeIf { it > 0 }
        val signedVersionCode = providers.environmentVariable("APP_VERSION_CODE")
            .orNull?.toIntOrNull()
        // Keep CI debug APKs installable over previously tested builds (a2.22 used 1022).
        // The signed-release job supplies the same 1000 + run-number value explicitly.
        versionCode = signedVersionCode ?: ciRunNumber?.let { 1000 + it } ?: 1
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
    implementation(libs.androidx.core)
    testImplementation(libs.junit)
}
