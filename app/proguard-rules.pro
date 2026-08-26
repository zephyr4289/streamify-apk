# Preserve Data Models and Value Classes
-keep class com.streamify.app.data.models.** { *; }
-keep class com.streamify.app.ui.models.** { *; }

# Preserve Native JNI Bridge methods
-keepclassmembers class com.streamify.app.data.NativeBridge {
    public static <methods>;
    private static native <methods>;
    private native <methods>;
    public <methods>;
}

# Prevent stripping C++/Rust JNI entry points
-keepclasseswithmembernames class * {
    native <methods>;
}

# ExoPlayer / Media3 rules
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Coil rules
-keep class coil.** { *; }
-dontwarn coil.**
