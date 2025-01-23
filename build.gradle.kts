buildscript {
    // Define versions in a single place
    ext {
        kotlinVersion = '1.9.0'
        gradleVersion = '8.8.0'
        googleServicesVersion = '4.4.0'
    }

    repositories {
        google()
        mavenCentral()
    }

    dependencies {
        classpath("com.android.tools.build:gradle:${gradleVersion}")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:${kotlinVersion}")
        classpath("com.google.gms:google-services:${googleServicesVersion}")
    }
}

plugins {
    id("com.android.application") version "8.8.0" apply false // Update to match buildscript version
    id("org.jetbrains.kotlin.android") version "1.9.0" apply false
    id("com.google.gms.google-services") version "4.4.0" apply false
}

// Add common configuration for all projects
allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

// Add clean task
tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}