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
-keep class com.photostreamr.model.** { *; }

# Keep your app's core classes
-keep class com.photostreamr.** { *; }

# WebView related rules
-keep class android.webkit.** { *; }
-keepclassmembers class * extends android.webkit.WebViewClient {
    public void *(android.webkit.WebView, java.lang.String);
    public void *(android.webkit.WebView, java.lang.String, android.graphics.Bitmap);
    public boolean *(android.webkit.WebView, java.lang.String);
}
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Google Play Billing Library
-keep class com.android.billingclient.** { *; }
-keep interface com.android.billingclient.** { *; }

# Keep required classes for callbacks
-keepclassmembers class * implements com.android.billingclient.api.PurchasesUpdatedListener {
    public void onPurchasesUpdated(com.android.billingclient.api.BillingResult, java.util.List);
}
-keepclassmembers class * implements com.android.billingclient.api.BillingClientStateListener {
    public void onBillingSetupFinished(com.android.billingclient.api.BillingResult);
    public void onBillingServiceDisconnected();
}

# Keep ProductDetails class and its components
-keep class com.android.billingclient.api.ProductDetails { *; }
-keep class com.android.billingclient.api.ProductDetails$* { *; }
-keep class com.android.billingclient.api.Purchase { *; }

# AdMob/Google Play Services
-keep public class com.google.android.gms.ads.** {
   public *;
}

-keep public class com.google.ads.** {
   public *;
}

# For mediation adapters (if using)
-keepattributes *Annotation*
-keepclassmembers class * {
    @com.google.android.gms.ads.annotation.AdUrlParam <fields>;
}

# Hilt and Dagger rules
-keep class dagger.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.internal.GeneratedComponent
-keep class * implements dagger.hilt.android.lifecycle.HiltViewModel
-keepnames @dagger.hilt.android.lifecycle.HiltViewModel class * extends androidx.lifecycle.ViewModel
-keep,allowobfuscation,allowshrinking class dagger.hilt.android.internal.** { *; }
-keep,allowobfuscation,allowshrinking class dagger.hilt.android.components.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager.ViewWithFragmentComponentBuilder { *; }

# Navigation Component
-keepnames class androidx.navigation.fragment.NavHostFragment
-keep class * extends androidx.navigation.Navigator
-keep class androidx.navigation.** { *; }
-keepnames class * extends androidx.fragment.app.Fragment

# ViewBinding
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

# Custom views
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# Parcelable implementations
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# Enums
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

# Keep specific fragments
-keep class com.photostreamr.MainFragment { *; }
-keep class com.photostreamr.fragments.** { *; }

# Google Services and Libraries
-keep class com.google.android.gms.** { *; }
-keep class com.google.api.** { *; }
-keep class com.google.cloud.** { *; }
-dontwarn com.google.api.**
-dontwarn com.google.cloud.**

# Firebase
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.tasks.** { *; }
-dontwarn com.google.firebase.**

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapter
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# Glide
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep public class * extends com.bumptech.glide.module.AppGlideModule
-keep class com.bumptech.glide.** { *; }

# Suppress specific R8 warnings for Spotify SDK
-dontwarn com.spotify.**
-keep class com.spotify.** { *; }

# Specifically handle annotation warnings
-dontwarn kotlin.annotations.jvm.**
-dontwarn javax.annotation.**
-dontwarn org.jetbrains.annotations.**

# Keep all Spotify SDK related classes
-keepclassmembers class * {
    @com.spotify.protocol.types.** *;
}

# Keep annotation classes
-keep @interface com.spotify.protocol.types.**
-keep class com.spotify.protocol.types.** { *; }

# Suppress specific warning messages
-keepattributes SourceFile,LineNumberTable
-keep class com.spotify.protocol.types.ListItems { *; }
-keep class com.spotify.protocol.types.ListItem { *; }
-keep class com.spotify.protocol.types.PlayerRestrictions { *; }
-keep class com.spotify.protocol.types.PlayerState { *; }
-keep class com.spotify.protocol.types.Track { *; }
-keep class com.spotify.protocol.types.Album { *; }
-keep class com.spotify.protocol.types.Artist { *; }
-keep class com.spotify.protocol.types.Uri { *; }

# Additional rules to suppress annotation processing warnings
-dontwarn com.spotify.protocol.annotations.**

# Keep JCIFS classes
-keep class jcifs.** { *; }
-keep interface jcifs.** { *; }
-keep enum jcifs.** { *; }
-keepclassmembers class jcifs.** { *; }

# Ignore missing JCIFS dependencies
-dontwarn javax.security.**
-dontwarn javax.servlet.**
-dontwarn org.ietf.jgss.**

-keep class com.photostreamr.photos.network.** { *; }
-keep class jcifs.** { *; }

# Keep network-related classes
-keep class com.photostreamr.photos.network.** { *; }
-keepclassmembers class com.photostreamr.photos.network.** { *; }

# Keep any class that might be using lateinit properties
-keepclassmembers class * {
    void initialize();
}

-dontwarn android.media.LoudnessCodecController$OnLoudnessCodecUpdateListener
-dontwarn android.media.LoudnessCodecController
-dontwarn java.beans.ConstructorProperties
-dontwarn java.beans.Transient
-dontwarn javax.naming.NamingEnumeration
-dontwarn javax.naming.NamingException
-dontwarn javax.naming.directory.Attribute
-dontwarn javax.naming.directory.Attributes
-dontwarn javax.naming.directory.DirContext
-dontwarn javax.naming.directory.InitialDirContext

-dontwarn android.media.LoudnessCodecController**
-dontwarn com.google.android.gms.internal.location.zze$Companion
-dontwarn com.google.android.gms.internal.location.zze
-dontwarn com.fasterxml.jackson.databind.ext.Java7SupportImpl
-dontwarn com.fasterxml.jackson.module.**