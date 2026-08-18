-keep class com.streamify.app.data.models.** { *; }
-keep class com.streamify.app.data.NativeBridge { *; }

# ExoPlayer rules
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Coil rules
-keep class coil.** { *; }
-dontwarn coil.**
