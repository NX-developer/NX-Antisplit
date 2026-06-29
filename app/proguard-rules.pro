# Keep ARSCLib and apksig (used via direct + reflective calls)
-keep class com.reandroid.** { *; }
-keep class com.android.apksig.** { *; }
-dontwarn com.reandroid.**
-dontwarn com.android.apksig.**
