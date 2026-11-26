version = 1

plugins {
    kotlin("android")
    id("com.android.library")
}

android {
    buildFeatures {
        buildConfig = true
    }

    // internalName do plugin = nome da pasta = AnimesOnlineCC
    namespace = "com.AnimesOnlineCC"
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
    description = "Animes Online CC - animes em portugues, dublados e legendados"
    language = "pt-br"
    authors = listOf("RamonMods")
    status = 1
    tvTypes = listOf("Animes")
    // favicon real: <link rel="shortcut icon"> da home do site
    iconUrl = "https://animesonlinecc.to/wp-content/uploads/2019/06/favicon.png"
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}
