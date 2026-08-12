-keep class com.streamify.app.data.models.** { *; }
-keep class com.streamify.app.data.NativeBridge { *; }

# Chaquopy rules
-keep class com.chaquo.python.** { *; }
-keep class * implements com.chaquo.python.PyObject { *; }

# ExoPlayer rules
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Coil rules
-keep class coil.** { *; }
-dontwarn coil.**
