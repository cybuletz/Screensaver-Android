plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services") // Add this after other plugins
}

android {
    namespace = "com.example.screensaver"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.screensaver"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        viewBinding = true  // Add this for view binding support
    }
}

dependencies {
    // Existing dependencies
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.preference:preference-ktx:1.2.1")

    // Google Sign In
    implementation("com.google.android.gms:play-services-auth:20.7.0")

    // Google Photos Library API
    implementation("com.google.photos.library:google-photos-library-client:1.7.3")

    // For handling auth tokens
    implementation("com.google.auth:google-auth-library-oauth2-http:1.19.0")

    // Import the Firebase BoM
    implementation(platform("com.google.firebase:firebase-bom:33.8.0"))

    // Firebase dependencies
    implementation("com.google.firebase:firebase-analytics")

    // Additional recommended dependencies for modern Android development
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")

    // Lifecycle components
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.2")

    // WebView related
    implementation("androidx.webkit:webkit:1.9.0")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}

// Gradle tasks for keystore inspection
tasks.register("showDebugKeystore") {
    doLast {
        val debugKeystoreFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
        println("=================")
        println("Debug Keystore Info:")
        println("=================")
        println("Location: $debugKeystoreFile")
        println("Exists: ${debugKeystoreFile.exists()}")

        android.signingConfigs.findByName("debug")?.let { config ->
            println("Signing Config Details:")
            println("Store File: ${config.storeFile}")
            println("Key Alias: ${config.keyAlias}")
            println("Store Password: ${config.storePassword}")
        }
    }
}

tasks.register("inspectDebugKeystore") {
    doLast {
        val debugKeystoreFile = "${System.getProperty("user.home")}/.android/debug.keystore"
        exec {
            workingDir = projectDir
            commandLine("keytool",
                "-list",
                "-v",
                "-keystore", debugKeystoreFile,
                "-alias", "androiddebugkey",
                "-storepass", "android",
                "-keypass", "android"
            )
        }
    }
}

tasks.register("printDebugSigning") {
    doLast {
        val debugKeystore = "${System.getProperty("user.home")}/.android/debug.keystore"
        exec {
            commandLine = listOf(
                "keytool",
                "-list",
                "-v",
                "-keystore",
                debugKeystore,
                "-alias",
                "androiddebugkey",
                "-storepass",
                "android",
                "-keypass",
                "android"
            )
        }
    }
}