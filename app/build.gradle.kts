plugins {
    id("com.android.application")
}

val releaseKeystorePath = providers.environmentVariable("RELEASE_KEYSTORE_PATH").orNull
// Engine binaries are built from pinned sources outside Gradle. Missing packages fail closed.
val officeEngineDir = file(providers.gradleProperty("officeEngineDir").getOrElse("${rootDir}/office-engine"))
val officeAbis = providers.gradleProperty("officeAbis").getOrElse("arm64-v8a,armeabi-v7a,x86_64")
    .split(",").also { require(it.isNotEmpty() && it.distinct().size == it.size &&
        it.all { abi -> abi in listOf("arm64-v8a", "armeabi-v7a", "x86_64") }) }


android {
    namespace = "com.cryonum.captusbprint"
    compileSdk = 36
    ndkVersion = "27.2.12479018"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    defaultConfig {
        applicationId = "com.cryonum.captusbprint"
        minSdk = 29
        ndk {
            abiFilters += officeAbis
        }
        targetSdk = 36
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
            buildConfigField("boolean", "PUBLIC_UPDATE_CHECKS", "true")
        }
        debug {
            applicationIdSuffix = ".debug"
            buildConfigField("boolean", "PUBLIC_UPDATE_CHECKS", "false")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { buildConfig = true }
    // A universal APK otherwise stores three large engines uncompressed. Android
    // extracts only the device ABI; verify both installation and JNI in API 29/36 CI.
    packaging { jniLibs { useLegacyPackaging = true } }
    // Compile the synthetic JNI probe in regular debug checks; never initialize it there.
    sourceSets.getByName("debug") {
        // AGP 9 built-in Kotlin ignores extra Kotlin roots registered as Java.
        kotlin.directories += "src/officeProbe/java"
        java.srcDir("src/officeProbe/java")
    }
    sourceSets.getByName("main") {
        assets.srcDir("${officeEngineDir}/assets")
        assets.srcDir("${officeEngineDir}/assets_strippedUI")
        jniLibs.srcDir("${officeEngineDir}/jniLibs")
    }
}

tasks.register("verifyOfficeEngine") {
    doLast {
        officeAbis.forEach { abi ->
            require(file("${officeEngineDir}/jniLibs/$abi/liblo-native-code.so").isFile) {
                "Pinned Office engine missing for $abi. See docs/OFFICE-USER-WORKFLOW.md"
            }
        }
        require(file("${officeEngineDir}/assets/office-licenses/index.json").isFile)
        require(file("${officeEngineDir}/assets/unpack/etc/fonts/fonts.conf").isFile)
    }
}
tasks.named("preBuild") { dependsOn("verifyOfficeEngine") }


dependencies {
    implementation(libs.androidx.core)
    implementation(libs.okhttp)
    implementation(libs.coroutines.android)
    testImplementation(libs.junit)
}
