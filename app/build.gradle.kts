plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.v2rayng.mytv"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.v2rayng.mytv"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("release") {
            storeFile = file("${rootProject.projectDir}/release-key.jks")
            storePassword = "android123"
            keyAlias = "release"
            keyPassword = "android123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // Local AAR
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))

    // AndroidX
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.leanback)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.datastore)

    // Coroutines
    implementation(libs.kotlinx.coroutines)

    // HTTP Server
    implementation(libs.nanohttpd)

    // QR Code generation
    implementation(libs.zxing.core)

    // JSON
    implementation(libs.gson)

    // Image loading
    implementation(libs.glide)
}