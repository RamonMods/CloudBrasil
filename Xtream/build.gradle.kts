version = 1

plugins {
    kotlin("android")
    id("com.android.library")
}

android {
    buildFeatures {
        buildConfig = true
    }
    namespace = "com.Xtream"
    compileSdk = 33

    defaultConfig {
        minSdk = 21
        targetSdk = 33
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

cloudstream {
    description = "Provedor Xtream IPTV: canais ao vivo, filmes e séries via API/M3U"
    language = "pt-br"
    authors = listOf("RamonMods")
    status = 1
    tvTypes = listOf("Live", "Movies", "Series")
    iconUrl = "https://github.com/RamonMods.png"
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}
