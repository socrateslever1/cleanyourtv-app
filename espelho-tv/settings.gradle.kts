pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "espelho-tv"

// Código comum (JVM puro, testado sem Android): canal cifrado de vídeo/áudio,
// QR de pareamento e cliente do protocolo de controle remoto do Google TV.
include(":protocolo")
// App instalado na TV (Chromecast com Google TV): captura e transmite.
include(":tv")
// App instalado no celular: assiste e controla.
include(":celular")
