plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace         = "com.footprintai.app"
    compileSdk        = 35

    defaultConfig {
        applicationId       = "com.footprintai.app"
        minSdk              = 26
        targetSdk           = 35
        versionCode         = 1
        versionName         = "1.0"

        // Backend URL for live trading
        buildConfigField "String", "BACKEND_BASE_URL", "\"${project.findProperty("backendUrl") ?: "http://localhost:8000"}\""
    }

    buildFeatures { 
        compose = true 
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // ONNX Runtime: keep .so files, don't strip them
    packaging {
        jniLibs { keepDebugSymbols += "**/*.so" }
    }
}

dependencies {
    // Compose
    val bom = platform(libs.compose.bom)
    implementation(bom)
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.extended)
    implementation(libs.compose.activity)
    implementation(libs.compose.lifecycle)
    implementation(libs.viewmodel.compose)
    debugImplementation(libs.compose.ui.tooling)

    // ONNX Runtime on-device inference
    implementation(libs.onnxruntime)

    // Vico charts
    implementation(libs.vico.compose)
    implementation(libs.vico.compose.m3)

    // OkHttp WebSocket
    implementation(libs.okhttp)

    // Moshi
    implementation(libs.moshi)

    // Coroutines
    implementation(libs.coroutines.android)

    // Unit tests
    testImplementation(libs.junit)
    testImplementation(libs.json)
}
