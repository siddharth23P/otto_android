# Only the "minified" build the emulator job instruments: the app's own rules are release's
# (proguard-rules.pro), and on top the libraries the instrumentation shares with the app stay whole --
# the test runner and Compose's test APIs call into them, and R8 cannot see those calls.
-keep class androidx.tracing.** { *; }
-keep class androidx.test.** { *; }
-keep class androidx.compose.** { *; }
-keep class androidx.lifecycle.** { *; }
-keep class androidx.activity.** { *; }
-keep class androidx.core.** { *; }
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-dontwarn com.google.errorprone.annotations.**
