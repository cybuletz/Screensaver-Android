buildscript {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }

    dependencies {
        classpath("com.android.tools.build:gradle:8.2.1")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.10")
        classpath("com.google.dagger:hilt-android-gradle-plugin:2.48.1")
        classpath("com.google.gms:google-services:4.4.0")
        classpath("com.google.firebase:firebase-crashlytics-gradle:2.9.9")
    }
}
//plugins {
//    id("com.android.application") version "8.2.1" apply false
//    id("com.android.library") version "8.2.1" apply false
//    id("org.jetbrains.kotlin.android") version "1.9.10" apply false // Updated to 1.9.10
//    id("com.google.dagger.hilt.android") version "2.48.1" apply false
   // id("com.google.gms.google-services") version "4.4.0" apply false
//}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}