plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}
android {
    namespace = "com.suicoco.nativelib.pty"
    compileSdk = 36
    defaultConfig {
        minSdk = 33
        externalNativeBuild { cmake { cppFlags += "-std=c++17"; abiFilters += "arm64-v8a" } }
    }
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.31.6" }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_21; targetCompatibility = JavaVersion.VERSION_21 }
    kotlinOptions { jvmTarget = "21" }
}
dependencies { implementation(libs.androidx.core.ktx) }
