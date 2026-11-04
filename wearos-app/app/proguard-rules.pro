# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-keep class com.wearos.ancsbridge.contacts.** { *; }
-keep class com.wearos.ancsbridge.messages.** { *; }

# Keep data & protocol models
-keep class com.wearos.ancsbridge.model.** { *; }
-keep class com.wearos.ancsbridge.data.** { *; }
-keep class com.wearos.ancsbridge.health.** { *; }
-keep class com.wearos.ancsbridge.weather.** { *; }
-keep class com.wearos.ancsbridge.filetransfer.** { *; }
-keep class com.wearos.ancsbridge.installer.** { *; }
-keep class com.wearos.ancsbridge.ancs.** { *; }
-keep class com.wearos.ancsbridge.ble.** { *; }

# Keep AppBrandRegistry and BrandMetadata
-keep class com.wearos.ancsbridge.ancs.AppBrandRegistry { *; }
-keep class com.wearos.ancsbridge.ancs.BrandMetadata { *; }

# Keep Receivers & Services for AndroidManifest
-keep class com.wearos.ancsbridge.installer.WatchFaceInstallReceiver { *; }
-keep class com.wearos.ancsbridge.installer.AppUpdateRestartReceiver { *; }
-keep class com.wearos.ancsbridge.ancs.BootReceiver { *; }
-keep class com.wearos.ancsbridge.ancs.AncsService { *; }
-keep class com.wearos.ancsbridge.ancs.AncsConnectionService { *; }
-keep class com.wearos.ancsbridge.ble.BLEClientService { *; }

# Retain generic type signatures & annotations for JSON parsing
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*

# Coroutines & Wear OS support
-dontwarn kotlinx.coroutines.**
-dontwarn androidx.wear.**
-dontwarn com.google.android.gms.**
