plugins { id("com.android.application") }
android {
    namespace = "com.cryonum.captusbprint.urifixture"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.cryonum.captusbprint.urifixture"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "synthetic-only"
    }
    sourceSets.getByName("main").assets.srcDir("../app/src/debug/assets")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
