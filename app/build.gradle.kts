plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.manhwareader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.manhwareader"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "1.6"
    }

    // One fixed key for every build, so a new APK installs over the old one without uninstalling.
    signingConfigs {
        create("nux") {
            storeFile = file("nux.jks")
            storePassword = "nuxreader"
            keyAlias = "nux"
            keyPassword = "nuxreader"
        }
    }

    buildTypes {
        release {
            // The optimized build: much smoother than a debug build.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("nux")
        }
        debug {
            signingConfig = signingConfigs.getByName("nux")
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
