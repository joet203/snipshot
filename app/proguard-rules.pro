# Keep Compose runtime essentials (defensive — most are covered by AGP defaults).
-keep class androidx.compose.runtime.** { *; }
-keep class androidx.compose.ui.** { *; }

# Keep Kotlin metadata for reflection-light operations.
-keep class kotlin.Metadata { *; }

# Our package — keep public APIs used via reflection (DataStore, etc.).
-keep class com.jt.snipshot.** { *; }
