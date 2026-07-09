-keep class com.libretv.android.** { *; }
-keepclassmembers class com.libretv.android.** {
    @android.webkit.JavascriptInterface <methods>;
    public <methods>;
}

-keep class androidx.media3.** { *; }
-keep class androidx.media.** { *; }
-keep class androidx.mediarouter.** { *; }

-keepclassmembers class * extends androidx.media3.common.Player$Listener {
    <methods>;
}

-dontwarn androidx.media3.**
-dontwarn androidx.mediarouter.**
-dontwarn org.teleal.cling.**
-dontwarn org.jupnp.**
-dontwarn org.eclipse.jetty.**

-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
