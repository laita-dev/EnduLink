import java.io.StringReader
import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    kotlin("android")
}

val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        val text = keystorePropsFile.readText(Charsets.UTF_8).removePrefix("\uFEFF")
        StringReader(text).use { load(it) }
    }
}

val resolvedStorePath = System.getenv("ANDROID_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }
    ?: keystoreProps.getProperty("storeFile")?.takeIf { it.isNotBlank() }
val resolvedStoreFile = resolvedStorePath?.let { path ->
    val candidate = file(path)
    if (candidate.exists()) candidate else rootProject.file(path)
}?.takeIf { it.exists() }
val resolvedStorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() }
    ?: keystoreProps.getProperty("storePassword")?.takeIf { it.isNotBlank() }
val resolvedKeyAlias = System.getenv("ANDROID_KEY_ALIAS")?.takeIf { it.isNotBlank() }
    ?: keystoreProps.getProperty("keyAlias")?.takeIf { it.isNotBlank() }
val resolvedKeyPassword = System.getenv("ANDROID_KEY_PASSWORD")?.takeIf { it.isNotBlank() }
    ?: keystoreProps.getProperty("keyPassword")?.takeIf { it.isNotBlank() }

val hasReleaseSigning = resolvedStoreFile != null &&
    resolvedStorePassword != null &&
    resolvedKeyAlias != null &&
    resolvedKeyPassword != null

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
        if (hasReleaseSigning) {
            create("release") {
                storeFile = resolvedStoreFile
                storePassword = resolvedStorePassword
                keyAlias = resolvedKeyAlias
                keyPassword = resolvedKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            ndk {
                abiFilters += listOf("armeabi-v7a", "arm64-v8a")
            }
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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

