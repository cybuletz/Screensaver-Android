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

# Keep your app's core classes
-keep class com.example.screensaver.** { *; }

# WebView related rules (new)
-keep class android.webkit.** { *; }
-keepclassmembers class * extends android.webkit.WebViewClient {
    public void *(android.webkit.WebView, java.lang.String);
    public void *(android.webkit.WebView, java.lang.String, android.graphics.Bitmap);
    public boolean *(android.webkit.WebView, java.lang.String);
}
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Hilt related classes
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keepclassmembers,allowobfuscation class * {
    @dagger.hilt.* *;
}

# Navigation Component (updated)
-keepnames class androidx.navigation.fragment.NavHostFragment
-keep class * extends androidx.navigation.Navigator
-keep class androidx.navigation.** { *; }
-keepnames class * extends androidx.fragment.app.Fragment

# ViewBinding (new)
-keep class * implements androidx.viewbinding.ViewBinding {
    public static *** bind(android.view.View);
    public static *** inflate(android.view.LayoutInflater);
}

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

# Warning suppressions
-dontwarn javax.lang.model.**
-dontwarn javax.tools.**
-dontwarn javax.annotation.**
-dontwarn org.eclipse.jetty.**
-dontwarn org.slf4j.**
-dontwarn reactor.blockhound.**

# Keep specific fragments (updated)
-keep class com.example.screensaver.MainFragment { *; }
-keep class com.example.screensaver.WebViewFragment { *; }
-keep class com.example.screensaver.fragments.** { *; }