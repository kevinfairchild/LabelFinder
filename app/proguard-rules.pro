# Add project specific ProGuard rules here.

# ML Kit barcode scanning
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_barcode.** { *; }

# Google Play Services
-keep class com.google.android.gms.common.** { *; }

# CameraX
-keep class androidx.camera.** { *; }

# Keep data classes used for JSON serialization via JSONObject
-keepclassmembers class com.labelfinder.MainActivity$ScanHistoryEntry { *; }
-keepclassmembers class com.labelfinder.MainActivity$SearchListItem { *; }
