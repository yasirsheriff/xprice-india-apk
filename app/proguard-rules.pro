# OkHttp3 & Kotlin Serialization / JSON
-keepattributes Signature, InnerClasses, EnclosingMethod
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep class org.json.** { *; }

# Jetpack Compose & Material3 UI
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# Media3 ExoPlayer
-dontwarn androidx.media3.**
-keep class androidx.media3.** { *; }

# Jetpack Glance Widgets
-dontwarn androidx.glance.**
-keep class androidx.glance.** { *; }