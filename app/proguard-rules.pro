# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
# Keep classes referenced in your project
-keep class javax.lang.model.** { *; }
-keep class org.eclipse.jetty.** { *; }
-keep class org.slf4j.** { *; }
-keep class reactor.blockhound.** { *; }

# Specific rules for auto-value
-keep class com.google.auto.value.** { *; }
-keep class * extends com.google.auto.value.AutoValue { *; }

# Keep annotation processors
-keep class * extends com.google.auto.value.processor.AutoValueProcessor { *; }
-keep class * extends com.google.auto.value.extension.** { *; }

# Keep specific classes mentioned in the error
-dontwarn javax.lang.model.**
-dontwarn org.eclipse.jetty.**
-dontwarn org.slf4j.**
-dontwarn reactor.blockhound.**