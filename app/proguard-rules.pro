# Add project specific ProGuard rules here.

# ML Kit barcode scanning
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_barcode.** { *; }

# Google Play Services
-keep class com.google.android.gms.common.** { *; }

# CameraX
-keep class androidx.camera.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
