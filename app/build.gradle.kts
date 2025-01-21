plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
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

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
        }
    }
    afterEvaluate {
        tasks.register("debugSigningInfo") {
            doLast {
                val debug = android.signingConfigs.getByName("debug")
                println("Debug keystore location: ${debug.storeFile?.absolutePath}")
                println("Debug keystore exists: ${debug.storeFile?.exists()}")
            }
        }
    }
    packaging {
        resources {
            excludes += listOf(
                "META-INF/INDEX.LIST",
                "META-INF/DEPENDENCIES",
                "META-INF/*.kotlin_module",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/gradle/incremental.annotation.processors",
                // Add these to resolve conflicts
                "META-INF/NOTICE.txt",
                "META-INF/LICENSE.txt",
                "META-INF/services/com.fasterxml.jackson.core.JsonFactory",
                "META-INF/services/com.google.auth.oauth2.OAuth2Credentials"
            )
            pickFirsts += listOf(
                "META-INF/io.netty.versions.properties",
                "META-INF/INDEX.LIST"
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

    lint {
        abortOnError = false
        baseline = file("lint-baseline.xml")
        disable += listOf(
            "InvalidPackage",
            "ObsoleteLintCustomCheck",
            "GradleDependency"
        )
    }
}

dependencies {
    // Version constants
    val coroutinesVersion = "1.7.3"
    val lifecycleVersion = "2.7.0"
    val googleAuthVersion = "1.19.0"
    val retrofitVersion = "2.9.0"

    // AndroidX Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("com.google.android.material:material:1.11.0")

    // Google Sign In and Services
    implementation("com.google.android.gms:play-services-auth:20.7.0")
    implementation("com.google.android.gms:play-services-base:18.2.0")

    // Google Photos API
    implementation("com.google.photos.library:google-photos-library-client:1.7.3") {
        exclude(group = "org.apache.httpcomponents")
        exclude(module = "protobuf-lite")
    }

    // Google Auth and API Client
    implementation("com.google.api-client:google-api-client-android:2.2.0") {
        exclude(group = "org.apache.httpcomponents")
    }
    implementation("com.google.auth:google-auth-library-oauth2-http:$googleAuthVersion") {
        exclude(group = "org.apache.httpcomponents")
    }
    implementation("com.google.oauth-client:google-oauth-client-jetty:1.34.1") {
        exclude(group = "org.apache.httpcomponents")
    }
    implementation("com.google.api:gax:2.23.0") {
        exclude(group = "org.threeten")
    }
    // Fragment
    implementation("androidx.fragment:fragment-ktx:1.6.2")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")


    // Retrofit
    implementation("com.squareup.retrofit2:retrofit:$retrofitVersion")
    implementation("com.squareup.retrofit2:converter-gson:$retrofitVersion")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:$coroutinesVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:$lifecycleVersion")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}

// Add configuration to handle dependency conflicts
configurations.all {
    resolutionStrategy {
        force("com.google.guava:guava:32.1.3-android")
        force("com.google.code.gson:gson:2.10.1")

        // Exclude conflicting dependencies
        exclude(group = "org.apache.httpcomponents")
        exclude(group = "org.threeten", module = "threetenbp")
    }
}


tasks.register("cleanKeystore") {
    doLast {
        val keystoreFile = File(project.rootDir, "app/debug.keystore")
        if (keystoreFile.exists()) {
            delete(keystoreFile)
            println("Deleted existing debug.keystore")
        }

        val userKeystoreFile = File(System.getProperty("user.home"), ".android/debug.keystore")
        if (userKeystoreFile.exists()) {
            delete(userKeystoreFile)
            println("Deleted existing user debug.keystore")
        }
    }
}

tasks.register("generateNewDebugKeystore") {
    dependsOn("cleanKeystore")
    doLast {
        // Get Java home from Android Studio's JDK
        val javaHome = System.getProperty("java.home")
            ?: project.android.sdkDirectory.parentFile.resolve("jre").absolutePath

        // Ensure parent directories exist
        File(project.rootDir, "app").mkdirs()

        // Generate new debug keystore
        exec {
            workingDir = project.rootDir
            executable = "$javaHome/bin/keytool"
            args = listOf(
                "-genkeypair",
                "-v",
                "-keystore", "app/debug.keystore",
                "-storepass", "android",
                "-alias", "androiddebugkey",
                "-keypass", "android",
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-validity", "10000",
                "-dname", "CN=Android Debug,O=Android,C=US"
            )
        }

        // Print the new SHA-1
        exec {
            workingDir = project.rootDir
            executable = "$javaHome/bin/keytool"
            args = listOf(
                "-list",
                "-v",
                "-keystore", "app/debug.keystore",
                "-alias", "androiddebugkey",
                "-storepass", "android"
            )
        }

        println("New debug keystore generated successfully!")
    }
}
