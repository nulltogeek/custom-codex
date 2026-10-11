plugins { id("com.android.test") }
android {
    namespace = "dev.nulltogeek.codexremote.benchmark"
    compileSdk = 35
    targetProjectPath = ":app"
    defaultConfig {
        minSdk = 30
        targetSdk = 35
        testInstrumentationRunner = "dev.nulltogeek.codexremote.benchmark.NativeFrameRunner"
    }
    buildTypes { create("release") {
        signingConfig = signingConfigs.getByName("debug")
        isMinifyEnabled = true
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
    } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
androidComponents { beforeVariants(selector().all()) { it.enable = it.buildType == "release" } }
