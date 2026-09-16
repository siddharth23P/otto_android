# The instrumented test APK against the "minified" build is shrunk too; keep its names, and ignore
# the compile-only annotations androidx.test refers to.
-dontobfuscate
-dontwarn com.google.errorprone.annotations.**
