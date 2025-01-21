plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services") // Add this
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
    }
    signingConfigs {
        getByName("debug") {
            storeFile = file("${projectDir}/debug.keystore") // Use absolute path in project
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    sourceSets {
        getByName("main") {
            assets {
                srcDirs("src/main/assets")
            }
        }
    }
    packaging {
        resources {
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE"
            excludes += "META-INF/LICENSE.txt"
            excludes += "META-INF/license.txt"
            excludes += "META-INF/NOTICE"
            excludes += "META-INF/NOTICE.txt"
            excludes += "META-INF/notice.txt"
            excludes += "META-INF/*.kotlin_module"
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
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.webkit:webkit:1.9.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("androidx.core:core-ktx:1.12.0")

    // Google Sign In
    // Google Sign In - make sure these versions are correct
    implementation("com.google.android.gms:play-services-base:18.2.0")
    implementation("com.google.android.gms:play-services-auth:20.7.0")
    implementation("com.google.photos.library:google-photos-library-client:1.7.3") {
        exclude(group = "org.apache.httpcomponents")
        exclude(module = "protobuf-lite")
    }
    implementation(platform("com.google.firebase:firebase-bom:32.7.0"))
    implementation("com.google.firebase:firebase-auth-ktx")
    // HTTP Client
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
}

// Update the keystore tasks
tasks.register("cleanKeystore") {
    doLast {
        val keystoreFile = File(projectDir, "debug.keystore")
        if (keystoreFile.exists()) {
            delete(keystoreFile)
            println("Deleted existing debug.keystore from: ${keystoreFile.absolutePath}")
        }
    }
}

tasks.register("generateNewDebugKeystore") {
    dependsOn("cleanKeystore")
    doLast {
        val javaHome = System.getProperty("java.home")
        val keystoreFile = File(projectDir, "debug.keystore")

        println("Generating keystore at: ${keystoreFile.absolutePath}")

        exec {
            workingDir = projectDir
            executable = "$javaHome/bin/keytool"
            args = listOf(
                "-genkeypair",
                "-v",
                "-keystore", keystoreFile.absolutePath,
                "-storepass", "android",
                "-alias", "androiddebugkey",
                "-keypass", "android",
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-validity", "10000",
                "-dname", "CN=Android Debug,O=Android,C=US"
            )
        }

        println("Generated keystore. Printing details:")

        exec {
            workingDir = projectDir
            executable = "$javaHome/bin/keytool"
            args = listOf(
                "-list",
                "-v",
                "-keystore", keystoreFile.absolutePath,
                "-alias", "androiddebugkey",
                "-storepass", "android"
            )
        }

        if (keystoreFile.exists()) {
            println("Successfully created keystore at: ${keystoreFile.absolutePath}")
        } else {
            throw GradleException("Failed to create keystore file!")
        }
    }
}