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

android {
    namespace = "com.example.screensaver"
    compileSdk = 34

    android {
        defaultConfig {
            applicationId = "com.example.screensaver"
            minSdk = 23
            targetSdk = 34
            versionCode = 1
            versionName = "1.0"
            manifestPlaceholders["google_oauth_client_id"] = "@string/google_oauth_client_id"
            manifestPlaceholders["redirectSchemeName"] = "screensaver-spotify"
            manifestPlaceholders["redirectHostName"] = "callback"
        }
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("${projectDir}/debug.keystore")
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
        languageVersion = "1.9"
        apiVersion = "1.9"
    }
}

dependencies {
    // Version constants
    val navVersion = "2.7.6"
    val lifecycleVersion = "2.6.2"
    val hiltVersion = "2.48.1"
    val coroutinesVersion = "1.7.3"
    val grpcVersion = "1.58.0"


    // AndroidX Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.webkit:webkit:1.9.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.databinding:databinding-runtime:8.1.4")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
    implementation("androidx.coordinatorlayout:coordinatorlayout:1.2.0")
    implementation("androidx.hilt:hilt-navigation-compose:1.1.0")
    implementation("androidx.biometric:biometric:1.2.0-alpha05")

    // AutoValue
    kapt("com.google.auto.value:auto-value:1.9")
    implementation("com.google.auto.value:auto-value-annotations:1.9")

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
    implementation("androidx.work:work-runtime-ktx:2.8.1")

    // Timber
    implementation("com.jakewharton.timber:timber:5.0.1")

    // Testing
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    testImplementation("junit:junit:4.13.2")

    // Material Design
    implementation("com.google.android.material:material:1.9.0")

    //Spotify
    implementation("com.spotify.android:auth:2.1.1")
    implementation(files("libs/spotify-app-remote-release-0.8.0.aar"))
    implementation("se.michaelthelin.spotify:spotify-web-api-java:8.0.0")

    // Google Services
    implementation("com.google.android.gms:play-services-base:18.2.0")
    implementation("com.google.android.gms:play-services-auth:20.7.0")
    implementation(platform("com.google.firebase:firebase-bom:32.7.0"))
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("com.google.firebase:firebase-analytics-ktx")
    implementation("com.google.firebase:firebase-crashlytics-ktx")
    implementation("com.google.auth:google-auth-library-oauth2-http:1.19.0")
    implementation("com.google.api:gax:2.19.5")
    implementation("com.google.android.gms:play-services-location:21.1.0")

    // Google Photos Library
    implementation("com.google.photos.library:google-photos-library-client:1.7.3")
    implementation("com.google.api-client:google-api-client-android:2.2.0")
    implementation("com.google.api-client:google-api-client-gson:2.2.0")

    // Retrofit
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")

    // Glide
    implementation("com.github.bumptech.glide:glide:4.16.0")
    implementation("com.github.bumptech.glide:okhttp3-integration:4.16.0")
    kapt("com.github.bumptech.glide:compiler:4.16.0")
    implementation("com.squareup.okhttp3:okhttp:4.11.0")

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
    implementation("org.json:json:20231013")

    // Add these dependencies if you're using them
    implementation("org.slf4j:slf4j-api:1.7.36")
    implementation("org.slf4j:slf4j-simple:1.7.36")

    // If you need the Jetty ALPN boot library
    implementation("org.eclipse.jetty.alpn:alpn-api:1.1.3.v20160715")

    implementation("com.google.api-client:google-api-client-gson:2.2.0")
}