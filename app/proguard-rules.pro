# ProGuard rules for Cloud Shell
-keep class org.mozilla.gecko.** { *; }
-keep class org.mozilla.geckoview.** { *; }
-keep class com.example.** { *; }
-dontwarn org.mozilla.**
-dontwarn org.yaml.snakeyaml.**
-dontwarn java.beans.**

# Keep Compose and Coroutines
-keepattributes *Annotation*,InnerClasses,EnclosingMethod
-keepclassmembers class * {
    @androidx.compose.runtime.Composable *;
}
