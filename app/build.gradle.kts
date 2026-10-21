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
        versionCode = 1
        versionName = "0.1.0-a1"

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
