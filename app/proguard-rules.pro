# Project data models — required by Gson reflection-based serialization
-keep class com.mpdplayer.Channel { *; }
-keep class com.mpdplayer.StreamSource { *; }
-keep class com.mpdplayer.Playlist { *; }
-keep class com.mpdplayer.EpgData { *; }
-keep class com.mpdplayer.EpgProgram { *; }
-keep class com.mpdplayer.CategoryInfo { *; }

# Media3 — uses reflection/NJI internally; keep entire library
-keep class androidx.media3.** { *; }

# Gson — keep TypeToken pattern and SerializedName annotations
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# OkHttp — full keep (reflection-based internals)
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# Glide — full keep (annotation-driven)
-keep class com.bumptech.glide.** { *; }

# Leanback TV
-dontwarn androidx.leanback.**
