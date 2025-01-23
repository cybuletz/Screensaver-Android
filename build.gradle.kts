plugins {
    id("com.android.application") version "8.8.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.0" apply false
    id("com.google.gms.google-services") version "4.4.0" apply false
}
buildscript {
    dependencies {
        classpath("com.google.dagger:hilt-android-gradle-plugin:2.48")
    }
}
// Define versions for other dependencies
val kotlinVersion = "1.9.0"
val gradleVersion = "8.8.0"
val googleServicesVersion = "4.4.0"

// Add clean task
tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}