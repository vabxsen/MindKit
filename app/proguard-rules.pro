# ML Kit GenAI and ML Kit vision clients are resolved reflectively through Google Play
# services module loading. Keep their public surface so release builds keep working.
-keep class com.google.mlkit.genai.** { *; }
-keep interface com.google.mlkit.genai.** { *; }
-keep class com.google.android.gms.internal.mlkit_genai_** { *; }

# Room generated implementations.
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# Guava ListenableFuture is used across the ML Kit boundary.
-dontwarn com.google.common.util.concurrent.**
-dontwarn javax.annotation.**
-dontwarn javax.lang.model.element.**
