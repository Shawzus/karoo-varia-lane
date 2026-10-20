plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.varialanes"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.varialanes"
        minSdk = 26
        targetSdk = 34
        versionCode = System.getenv("BUILD_NUMBER")?.toIntOrNull() ?: 1
        versionName = "0.1.${System.getenv("BUILD_NUMBER") ?: "0"}"
    }

    // Fixed signing key in the repo so each new build installs over the old one on the Karoo.
    // Fine for a personal sideloaded app; swap for a private key if you ever publish it.
    signingConfigs {
        create("release") {
            storeFile = file("varialanes.p12")
            storeType = "pkcs12"
            storePassword = "varialanes"
            keyAlias = "varialanes"
            keyPassword = "varialanes"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
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
    implementation("io.hammerhead:karoo-ext:1.1.9")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
}
