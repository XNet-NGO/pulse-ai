# AIO Pulse ProGuard rules
-ignorewarnings

# --- App classes ---
-keepattributes *Annotation*
-keep class com.xnet.pulse.** { *; }

# --- OkHttp ---
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# --- Hilt / Dagger ---
-dontwarn dagger.**
-keep class dagger.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }

# --- Room ---
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }

# --- Kotlin / Coroutines ---
-dontwarn kotlinx.coroutines.**
-keep class kotlinx.coroutines.** { *; }

# --- Markwon / Markdown AARs ---
-dontwarn io.noties.markwon.**
-keep class io.noties.markwon.** { *; }
-dontwarn ru.noties.**
-keep class ru.noties.** { *; }

# --- Atlassian CommonMark ---
-dontwarn org.commonmark.**
-keep class org.commonmark.** { *; }

# --- JLatexMath ---
-dontwarn ru.noties.jlatexmath.**
-keep class ru.noties.jlatexmath.** { *; }

# --- Universal Markdown Compose ---
-dontwarn com.xnet.markdown.**
-keep class com.xnet.markdown.** { *; }
-dontwarn com.wakaztahir.**
-keep class com.wakaztahir.** { *; }

# --- Apache POI ---
-dontwarn org.apache.poi.**
-keep class org.apache.poi.** { *; }
-dontwarn org.apache.xmlbeans.**
-keep class org.apache.xmlbeans.** { *; }
-dontwarn org.apache.commons.**
-keep class org.apache.commons.** { *; }
-dontwarn org.openxmlformats.**
-keep class org.openxmlformats.** { *; }
-dontwarn schemaorg_apache_xmlbeans.**
-keep class schemaorg_apache_xmlbeans.** { *; }

# --- PDFBox Android ---
-dontwarn com.tom_roush.**
-keep class com.tom_roush.** { *; }

# --- OpenCSV ---
-dontwarn com.opencsv.**
-keep class com.opencsv.** { *; }

# --- JTokkit ---
-dontwarn com.knuddels.**
-keep class com.knuddels.** { *; }

# --- Coil ---
-dontwarn coil.**
-keep class coil.** { *; }

# --- AndroidSVG ---
-dontwarn com.caverock.**
-keep class com.caverock.** { *; }

# --- Google Play Services ---
-dontwarn com.google.android.gms.**
-keep class com.google.android.gms.** { *; }

# --- Media3 / ExoPlayer ---
-dontwarn androidx.media3.**
-keep class androidx.media3.** { *; }

# --- Compose (keep runtime metadata) ---
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# --- DataStore ---
-keep class androidx.datastore.** { *; }

# --- WorkManager + Hilt Worker ---
-keep class * extends androidx.work.Worker { *; }
-keep class * extends androidx.work.ListenableWorker { *; }
-keep class androidx.hilt.work.** { *; }

# --- JSON (org.json is part of Android, but keep for safety) ---
-keep class org.json.** { *; }

# --- General: keep enums, serializable ---
-keepclassmembers enum * { *; }
-keep class * implements java.io.Serializable { *; }
-keepclassmembers class * implements java.io.Serializable {
  static final long serialVersionUID;
  private static final java.io.ObjectStreamField[] serialPersistentFields;
  private void writeObject(java.io.ObjectOutputStream);
  private void readObject(java.io.ObjectInputStream);
  java.lang.Object writeReplace();
  java.lang.Object readResolve();
}

# --- Suppress warnings for missing optional deps ---
-dontwarn javax.annotation.**
-dontwarn javax.xml.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn org.slf4j.**
-dontwarn com.sun.**
-dontwarn sun.**
-dontwarn java.awt.**
-dontwarn javax.swing.**
-dontwarn org.w3.x2000.**
-dontwarn org.etsi.**
-dontwarn org.openxmlformats.schemas.**
-dontwarn schemasMicrosoftCom**
-dontwarn org.apache.batik.**
-dontwarn org.apache.logging.**
-dontwarn org.apache.xml.**
-dontwarn com.github.javaparser.**
-dontwarn com.graphbuilder.**
-dontwarn org.apache.pdfbox.**
