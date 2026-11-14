plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.vellum.shield"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.vellum.shield"
        minSdk = 26
        targetSdk = 34
        versionCode = 103
        versionName = "1.1.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    androidResources {
        noCompress += listOf("vbin", "woff2")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.24")
}
