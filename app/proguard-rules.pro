# Keep ExoPlayer/Media3
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Keep OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# Keep Glide
-keep class com.bumptech.glide.** { *; }
-dontwarn com.bumptech.glide.**

# Keep Leanback
-dontwarn androidx.leanback.**

# Keep Gson
-keep class com.google.gson.** { *; }
-dontwarn com.google.gson.**

# Keep Project Data Models
-keep class com.mpdplayer.Channel { *; }
-keep class com.mpdplayer.StreamSource { *; }
-keep class com.mpdplayer.Playlist { *; }
-keep class com.mpdplayer.EpgData { *; }
-keep class com.mpdplayer.EpgProgram { *; }
-keep class com.mpdplayer.CategoryInfo { *; }
