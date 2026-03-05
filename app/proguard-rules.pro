# Project specific ProGuard rules.

# Keep CameraX and Media3 classes used through reflection/metadata.
-keep class androidx.camera.** { *; }
-keep class androidx.media3.** { *; }

# Keep app entry points.
-keep class com.camerax.app.MainActivity { *; }
