# TV VPN ProGuard Rules

# Keep libv2ray
-keep class libv2ray.** { *; }
-keep class go.** { *; }

# Keep Gson serialization
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.v2rayng.mytv.data.** { *; }
-keep class com.google.gson.** { *; }

# Keep NanoHTTPD
-keep class fi.iki.elonen.** { *; }

# Keep VPN service
-keep class com.v2rayng.mytv.vpn.** { *; }