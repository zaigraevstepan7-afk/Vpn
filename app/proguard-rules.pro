# Go-mobile generated bindings must not be obfuscated/stripped.
-keep class libv2ray.** { *; }
-keep class go.** { *; }
-dontwarn libv2ray.**
-dontwarn go.**

# The core is called from native (JNI) via the libv2ray callback interfaces,
# so keep our implementations and their method names intact.
-keep class com.nebula.vpn.core.** { *; }
-keepclassmembers class * implements libv2ray.CoreCallbackHandler { *; }
-keepclassmembers class * implements libv2ray.ProcessFinder { *; }
