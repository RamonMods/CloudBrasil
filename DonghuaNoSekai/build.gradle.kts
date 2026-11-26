import java.util.Properties

// Identificador interno do plugin (usado como nome do módulo e do pacote).
val internalName = "DonghuaNoSekai"

version = 1

plugins {
    kotlin("android")
    id("com.android.library")
}

android {
    buildFeatures {
        buildConfig = true
    }
    namespace = "com.$internalName"
    compileSdk = 33

    defaultConfig {
        minSdk = 21
        targetSdk = 33

        buildConfigField("String", "INTERNAL_NAME", "\"$internalName\"")
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
    description = "Donghuas e dramas asiáticos legendados em português"
    language = "pt-br"
    authors = listOf("RamonMods")
    status = 1
    tvTypes = listOf("Animes", "AsianDrama")
    iconUrl = "https://cdn.dnskcdn.link/2020/09/cropped-icon_dnsk.png"
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}
