plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

val stableKeystoreFile = System.getenv("KEYSTORE_FILE")
val stableKeystorePassword = System.getenv("KEYSTORE_PASSWORD")
val stableKeyAlias = System.getenv("KEY_ALIAS")
val stableKeyPassword = System.getenv("KEY_PASSWORD")

val hasStableSigning =
    !stableKeystoreFile.isNullOrBlank() &&
    !stableKeystorePassword.isNullOrBlank() &&
    !stableKeyAlias.isNullOrBlank() &&
    !stableKeyPassword.isNullOrBlank()

android {
    namespace = "com.calltune.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.calltune.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        if (hasStableSigning) {
            create("stable") {
                storeFile = file(stableKeystoreFile!!)
                storePassword = stableKeystorePassword
                keyAlias = stableKeyAlias
                keyPassword = stableKeyPassword
            }
        }
    }

    buildTypes {
        getByName("debug") {
            if (hasStableSigning) {
                signingConfig = signingConfigs.getByName("stable")
            }
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
    implementation(platform("com.google.firebase:firebase-bom:34.5.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("androidx.core:core:1.13.1")
}
