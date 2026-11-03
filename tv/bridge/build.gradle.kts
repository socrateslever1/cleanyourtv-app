plugins { id("cleanyourtv.tv.app") }

android {
    namespace = "tv.cleanyour.bridge"
    defaultConfig {
        applicationId = "tv.cleanyour.bridge"
        versionCode = 1
        versionName = "1.0"
    }
    signingConfigs {
        create("owner") {
            val filePath = providers.environmentVariable("CYT_RELEASE_KEYSTORE").orNull
            if (!filePath.isNullOrBlank()) storeFile = file(filePath)
            storePassword = providers.environmentVariable("CYT_RELEASE_KEYSTORE_PASSWORD").orNull
            keyAlias = providers.environmentVariable("CYT_RELEASE_KEY_ALIAS").orNull
            keyPassword = providers.environmentVariable("CYT_RELEASE_KEY_PASSWORD").orNull
            enableV1Signing = false
            enableV2Signing = true
            enableV3Signing = true
        }
    }
    buildTypes {
        getByName("release") {
            if (!providers.environmentVariable("CYT_RELEASE_KEYSTORE").orNull.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("owner")
            }
        }
    }
}
