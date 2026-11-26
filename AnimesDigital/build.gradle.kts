import java.util.Properties

version = 1

plugins {
    kotlin("android")
    id("com.android.library")
}

android {
    buildFeatures {
        buildConfig = true
    }
    namespace = "com.AnimesDigital"
    compileSdk = 33

    defaultConfig {
        minSdk = 21
        targetSdk = 33

        val tmdbApiKey = project.findProperty("TMDB_API_KEY") as? String
            ?: System.getenv("TMDB_API_KEY")
            ?: getLocalProperty("TMDB_API_KEY")
            ?: "dummy_api_key"

        val tmdbAccessToken = project.findProperty("TMDB_ACCESS_TOKEN") as? String
            ?: System.getenv("TMDB_ACCESS_TOKEN")
            ?: getLocalProperty("TMDB_ACCESS_TOKEN")
            ?: "dummy_access_token"

        buildConfigField("String", "TMDB_API_KEY", "\"$tmdbApiKey\"")
        buildConfigField("String", "TMDB_ACCESS_TOKEN", "\"$tmdbAccessToken\"")
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

fun getLocalProperty(key: String): String? {
    val localProperties = Properties()
    val localPropertiesFile = project.rootProject.file("local.properties")

    return if (localPropertiesFile.exists()) {
        localProperties.load(localPropertiesFile.inputStream())
        localProperties.getProperty(key)
    } else {
        null
    }
}

cloudstream {
    description = "AnimesDigital - animes e filmes em portugues, dublados e legendados"
    language = "pt-br"
    authors = listOf("RamonMods")
    status = 1
    tvTypes = listOf("Animes", "AnimeMovie")
    // favicon real, validado com HTTP 200 em 26/11/2026
    iconUrl = "https://animesdigital.org/wp-content/uploads/2025/03/cropped-logo-e1740827992823-192x192.png"
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}
