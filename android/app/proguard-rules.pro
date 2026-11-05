# ─── Moodify ProGuard / R8 rules ─────────────────────────────────────────────

# Keep line numbers for crash stack traces
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ─── Kotlin ──────────────────────────────────────────────────────────────────
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**
-keepclassmembers class **$WhenMappings { <fields>; }

# ─── Kotlin Coroutines ───────────────────────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.**

# ─── Room ────────────────────────────────────────────────────────────────────
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keepclassmembers @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class *
-keepclassmembers @androidx.room.Dao class * { *; }
-dontwarn androidx.room.**

# ─── DataStore ───────────────────────────────────────────────────────────────
-keep class androidx.datastore.** { *; }

# ─── OkHttp ──────────────────────────────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# ─── Gson / JSON ─────────────────────────────────────────────────────────────
-keep class com.google.gson.** { *; }
-keepattributes Signature
-keepattributes *Annotation*
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
# Keep the fields of every Moodify class that Gson reads or writes. Their JSON
# is persisted (Room rows, queue snapshots, WorkManager tags, catalog cache), so
# renaming a field would make data written by one build unreadable by the next.
# Note: sources live under com/example/myapplication but the package is com.moodify.app.
-keep class com.moodify.app.data.** { *; }
-keep class com.moodify.app.player.DownloadedTrack { *; }
-keep class com.moodify.app.player.ListeningHistoryEntry { *; }
# Generic types read through TypeToken (List<Track>, Map<String, DownloadedTrack>…).
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken

# ─── Media3 / ExoPlayer ──────────────────────────────────────────────────────
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**
-keep class com.google.android.exoplayer2.** { *; }

# ─── Coil ────────────────────────────────────────────────────────────────────
-keep class coil.** { *; }
-dontwarn coil.**

# ─── QuickJS (sandboxed JS for YouTube player decipher) ──────────────────────
-keep class app.cash.quickjs.** { *; }
-dontwarn app.cash.quickjs.**

# ─── org.json (used directly in InnerTubeCatalogClient) ──────────────────────
-keep class org.json.** { *; }

# ─── Parcelables ─────────────────────────────────────────────────────────────
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}

# ─── Enums ───────────────────────────────────────────────────────────────────
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ─── Serializable ────────────────────────────────────────────────────────────
-keepclassmembers class * implements java.io.Serializable {
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# ─── Compose ─────────────────────────────────────────────────────────────────
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# ─── Suppress noisy warnings from transitive deps ────────────────────────────
-dontwarn com.google.errorprone.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
