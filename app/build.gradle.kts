

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt") // Add this line to properly enable kapt
    id("com.google.dagger.hilt.android")
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
    id("kotlin-parcelize")
}

kapt {
    correctErrorTypes = true
}


// Use ProcessBuilder to get commit count for versionCode
val commitCount: Int = try {
    val process = ProcessBuilder("git", "rev-list", "--count", "HEAD")
        .redirectErrorStream(true)
        .start()
    process.inputStream.bufferedReader().readText().trim().toInt()
} catch (e: Exception) {
    0 // If git fails, set commit count to 0
}

// Get current time in milliseconds since epoch
val currentTimeMillis = System.currentTimeMillis()

// Use the time in seconds (or milliseconds) as the base for versionCodew
val autoVersionCode = (currentTimeMillis / 1000).toInt()

val autoVersionName = "0.1.0.${autoVersionCode}"

android {
    namespace = "com.photostreamr"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.photostreamr"
        minSdk = 26
        targetSdk = 34
        versionCode = autoVersionCode
        versionName = autoVersionName
        manifestPlaceholders["google_oauth_client_id"] = "@string/google_oauth_client_id"
        manifestPlaceholders["redirectSchemeName"] = "photostreamr-spotify"
        manifestPlaceholders["redirectHostName"] = "callback"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("${projectDir}/photostreamr.jks")
            storePassword = "luvvmMary"
            keyAlias = "key0"
            keyPassword = "luvvmMary"
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
            excludes += listOf(
                "META-INF/INDEX.LIST",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/license.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/notice.txt",
                "META-INF/*.kotlin_module",
                "META-INF/LGPL2.1"
            )
            pickFirsts.add("mozilla/public-suffix-list.txt")
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }

        getByName("debug") {

            versionNameSuffix = "-debug"
            isDebuggable = true
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debug")
            buildConfigField("String", "BUILD_TYPE", "\"debug\"")
            buildConfigField("boolean", "DEBUG_MODE", "true")

            // Add this to make Firebase use the base applicationId for the google-services plugin
            manifestPlaceholders["appIdSuffix"] = ""
        }
    }

    lint {
        baseline = file("lint-baseline.xml")
        warning += "ProtectedPermissions"
    }

    buildFeatures {
        dataBinding = true
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    hilt {
        enableAggregatingTask = true
        enableExperimentalClasspathAggregation = true
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-Xjvm-default=all"
            // Remove the "-Xuse-k2" flag as it's deprecated
        )
        // Add explicit language version
        languageVersion = "2.0"
        apiVersion = "2.0"
    }
}

dependencies {
    implementation("androidx.palette:palette-ktx:1.0.0")
    // Version constants
    val navVersion = "2.7.6"
    val lifecycleVersion = "2.6.2"
    val hiltVersion = "2.48.1"
    val coroutinesVersion = "1.7.3"
    val grpcVersion = "1.72.0"

    // Google AdMob
    implementation("com.google.android.gms:play-services-ads:24.1.0")
    implementation("com.google.android.ump:user-messaging-platform:3.2.0")

    // Coil
    implementation("io.coil-kt:coil:2.7.0")

    // In-app billing (optional, for handling purchases)
    implementation("com.android.billingclient:billing:7.1.1")

    implementation("com.google.android.play:feature-delivery:2.1.0")
    implementation("com.google.android.play:feature-delivery-ktx:2.1.0")

    implementation("com.google.mlkit:face-detection:16.1.7")

    implementation("androidx.room:room-common:2.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-metadata-jvm:0.7.0")

    // SMB library for network file access
    implementation("eu.agno3.jcifs:jcifs-ng:2.1.9")
    implementation("org.bouncycastle:bcprov-jdk15on:1.70")

    // AndroidX Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.webkit:webkit:1.9.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.databinding:databinding-runtime:8.1.4")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation("androidx.coordinatorlayout:coordinatorlayout:1.2.0")
    implementation("androidx.hilt:hilt-navigation-compose:1.1.0")
    implementation("androidx.biometric:biometric:1.2.0-alpha05")
    implementation("androidx.activity:activity:1.8.2")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.fragment:fragment-ktx:1.6.2")


    // AutoValue
    kapt("com.google.auto.value:auto-value:1.11.0")
    implementation("com.google.auto.value:auto-value-annotations:1.11.0")

    // Navigation
    implementation("androidx.navigation:navigation-fragment-ktx:$navVersion")
    implementation("androidx.navigation:navigation-ui-ktx:$navVersion")
    implementation("androidx.navigation:navigation-dynamic-features-fragment:$navVersion")
    androidTestImplementation("androidx.navigation:navigation-testing:$navVersion")

    // Core Hilt dependencies
    implementation("com.google.dagger:hilt-android:$hiltVersion")
    kapt("com.google.dagger:hilt-android-compiler:$hiltVersion")

    // Keep only these Hilt-related dependencies
    implementation("androidx.hilt:hilt-navigation-fragment:1.1.0")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:$lifecycleVersion")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // Timber
    implementation("com.jakewharton.timber:timber:5.0.1")

    // Testing
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    testImplementation("junit:junit:4.13.2")

    // Material Design
    implementation("com.google.android.material:material:1.12.0")

    //Spotify
    implementation("com.spotify.android:auth:2.1.2")
    implementation(files("libs/spotify-app-remote-release-0.8.0.aar"))
    implementation("se.michaelthelin.spotify:spotify-web-api-java:9.2.0")

    // Google Services
    implementation("com.google.android.gms:play-services-base:18.7.0")
    implementation("com.google.android.gms:play-services-auth:21.3.0")
    implementation(platform("com.google.firebase:firebase-bom:33.13.0"))
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("com.google.firebase:firebase-analytics-ktx")
    implementation("com.google.firebase:firebase-crashlytics-ktx")
    implementation("com.google.auth:google-auth-library-oauth2-http:1.34.0")
    implementation("com.google.api:gax:2.64.3")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("com.google.android.recaptcha:recaptcha:18.7.0")

    implementation("com.google.android.play:app-update:2.1.0")
    implementation("com.google.android.play:app-update-ktx:2.1.0")

    // Google Photos Library
    implementation("com.google.photos.library:google-photos-library-client:1.7.3")
    implementation("com.google.api-client:google-api-client-android:2.7.2")
    implementation("com.google.api-client:google-api-client-gson:2.7.2")

    // Retrofit
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // RecyclerView
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:$coroutinesVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:$coroutinesVersion")

    // gRPC
    implementation("io.grpc:grpc-okhttp:$grpcVersion")
    implementation("io.grpc:grpc-android:$grpcVersion")
    implementation("io.grpc:grpc-protobuf:$grpcVersion")
    implementation("io.grpc:grpc-stub:$grpcVersion")

    // JSON
    implementation("org.json:json:20250107")

    // Add these dependencies if you're using them
    implementation("org.slf4j:slf4j-api:2.0.17")
    implementation("org.slf4j:slf4j-simple:2.0.17")

    // If you need the Jetty ALPN boot library
    implementation("org.eclipse.jetty.alpn:alpn-api:1.1.3.v20160715")
}