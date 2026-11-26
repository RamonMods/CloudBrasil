version = 1

plugins {
    kotlin("android")
    id("com.android.library")
}

android {
    buildFeatures {
        buildConfig = true
    }
    namespace = "com.AnimesCloud"
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
    description = "Animes e filmes em português (PT-BR) do catálogo AnimesDrive."
    language = "pt-br"
    authors = listOf("RamonMods")
    status = 1
    tvTypes = listOf("Animes", "AnimeMovie")
    // favicon real do site, validado com HTTP 200 em 26/11/2026
    iconUrl = "https://animesdrive.cloud/wp-content/uploads/2026/08/cropped-ico-1-192x192.png"
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}
