plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    kotlin("android")
}

android {
    namespace = "com.oscar.endulink"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.oscar.endulink"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()

        val envVersionName = project.findProperty("appVersionName") as? String
        val envVersionCode = (project.findProperty("appVersionCode") as? String)?.toIntOrNull()

        versionCode = envVersionCode ?: 12
        versionName = envVersionName ?: "0.1.12"
    }

    signingConfigs {
        create("release") {
            storeFile = file("endulink-keystore.jks")
            storePassword = "endulink123"
            keyAlias = "endulink"
            keyPassword = "endulink123"
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
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
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
    implementation(projects.shared)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
}

