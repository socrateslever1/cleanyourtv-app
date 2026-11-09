plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "br.espelhotv.tv"
    compileSdk = 34

    defaultConfig {
        applicationId = "br.espelhotv.tv"
        // Captura de áudio de outros apps (AudioPlaybackCapture) exige Android 10.
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        // Chave SÓ DE TESTE, pública no repositório, para que cada versão nova
        // instale por cima da anterior. Trocar por chave privada antes de publicar na Play Store.
        create("teste") {
            storeFile = rootProject.file("teste.keystore")
            storePassword = "espelhotv-teste"
            keyAlias = "teste"
            keyPassword = "espelhotv-teste"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("teste")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":protocolo"))
    implementation("com.google.zxing:core:3.5.3")
}
