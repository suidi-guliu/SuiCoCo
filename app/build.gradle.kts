plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.suicoco"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.suicoco"
        minSdk = 33
        targetSdk = 33
        versionCode = 1
        versionName = "0.1.0"
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures { compose = true }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // 所有 core 与 feature 模块
    implementation(project(":core:design"))
    implementation(project(":core:resource"))
    implementation(project(":core:fs"))
    implementation(project(":core:config"))
    implementation(project(":core:buffer"))
    implementation(project(":core:editor"))
    implementation(project(":core:term"))
    implementation(project(":core:distro"))
    implementation(project(":core:syntax"))
    implementation(project(":core:extapi"))
    implementation(project(":core:extengine"))

    implementation(project(":feature:explorer"))
    implementation(project(":feature:editorpane"))
    implementation(project(":feature:terminalpane"))
    implementation(project(":feature:marketplace"))
    implementation(project(":feature:onboarding"))
    implementation(project(":feature:settings"))

    implementation(project(":native:proot"))
    implementation(project(":native:pty"))
}
