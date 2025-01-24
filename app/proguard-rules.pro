# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.

# Keep source file and line numbers for better crash reports
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Keep annotation related classes
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes Exceptions
-keepattributes InnerClasses

# Keep all your model classes
-keep class com.example.screensaver.model.** { *; }

# Keep your app's classes
-keep class com.example.screensaver.** { *; }

# AutoValue specific rules
-keep class com.google.auto.value.AutoValue { *; }
-keep @com.google.auto.value.AutoValue class * { *; }
-keep class * extends com.google.auto.value.AutoValue { *; }

# Hilt related classes
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }

# Kotlin specific rules
-keep class kotlin.** { *; }
-keep class kotlin.Metadata { *; }
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlin.**
-dontwarn kotlinx.coroutines.**

# Android specific components
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.preference.PreferenceFragment
-keep public class * extends androidx.fragment.app.Fragment

# Keep your custom views
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# Keep Parcelable implementations
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# Keep enums
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Remove logging in release
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# Add warning suppressions for missing classes
-dontwarn javax.lang.model.**
-dontwarn javax.tools.**
-dontwarn javax.annotation.**
-dontwarn org.eclipse.jetty.**
-dontwarn org.slf4j.**
-dontwarn reactor.blockhound.**

# Keep Fragments
-keep class com.example.screensaver.MainFragment { *; }
-keep class com.example.screensaver.fragments.** { *; }

# Keep Navigation Component classes
-keep class androidx.navigation.fragment.NavHostFragment { *; }
-keepnames class * extends androidx.fragment.app.Fragment

# Keep classes that are referenced in the navigation graph
-keepclassmembers class * extends androidx.fragment.app.Fragment {
    <init>();
}

# Keep the navigation graph itself
-keep class **.navigation.** { *; }