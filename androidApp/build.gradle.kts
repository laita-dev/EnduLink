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

        val resolvedVersionName = envVersionName ?: "0.1.14"
        val versionParts = resolvedVersionName.split(".").mapNotNull { it.toIntOrNull() }
        val derivedVersionCode = if (versionParts.size >= 3) {
            versionParts[0] * 10000 + versionParts[1] * 100 + versionParts[2]
        } else {
            114
        }

        versionCode = envVersionCode ?: derivedVersionCode
        versionName = resolvedVersionName
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

