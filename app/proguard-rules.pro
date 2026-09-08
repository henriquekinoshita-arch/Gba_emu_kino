# Keep classes with native methods so JNI signatures are not renamed.
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep data classes used across the JNI boundary and DataStore serialization.
-keep class com.kino.gbaemu.core.** { *; }
-keep class com.kino.gbaemu.data.** { *; }
