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
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()

        val envVersionName = project.findProperty("appVersionName") as? String
        val envVersionCode = (project.findProperty("appVersionCode") as? String)?.toIntOrNull()

        val resolvedVersionName = envVersionName ?: "0.3.0"
        val isTestBuild = (project.findProperty("isTestBuild") as? String)?.toBooleanStrictOrNull()
            ?: resolvedVersionName.contains("-")
        val baseVersion = resolvedVersionName.substringBefore("-")
        val versionParts = baseVersion.split(".").mapNotNull { it.toIntOrNull() }
        val derivedVersionCode = if (versionParts.size >= 3) {
            versionParts[0] * 10000 + versionParts[1] * 100 + versionParts[2]
        } else {
            300
        }

        applicationId = if (isTestBuild) "com.oscar.endulink.test" else "com.oscar.endulink"
        versionCode = envVersionCode ?: derivedVersionCode
        versionName = resolvedVersionName
        resValue("string", "app_name", if (isTestBuild) "EnduLink-test" else "EnduLink")
        resourceConfigurations += listOf("es", "en")
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
            isMinifyEnabled = true
            isShrinkResources = true
            ndk {
                abiFilters += listOf("armeabi-v7a", "arm64-v8a")
            }
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

    packaging {
        resources {
            excludes += listOf(
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                "DebugProbesKt.bin"
            )
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

