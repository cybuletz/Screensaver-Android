buildscript {
    // Define kotlin_version first
    val kotlin_version by extra("1.9.21")  // Using the same version as in plugins

    repositories {
        google()
        mavenCentral()
    }

    dependencies {
        classpath("com.google.dagger:hilt-android-gradle-plugin:2.48")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:${kotlin_version}")
        classpath("org.jetbrains.kotlin:kotlin-serialization:${kotlin_version}")
    }
}

plugins {
    id("com.android.application") version "8.1.0" apply false
    id("com.android.library") version "8.1.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.21" apply false
    id("com.google.dagger.hilt.android") version "2.48" apply false
    id("com.google.gms.google-services") version "4.4.0" apply false
}

// You can keep these version definitions if needed
val kotlinVersion = "1.9.21"  // Updated to match the plugin version
val gradleVersion = "8.1.0"   // Updated to match the plugin version
val googleServicesVersion = "4.4.0"

tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}