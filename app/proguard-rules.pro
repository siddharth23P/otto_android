# Release builds are shrunk, not renamed: a stack trace from a phone stays readable, and nothing
# reached by name moves.
-dontobfuscate

# Python calls these by name through Chaquopy (otto_app/backend.py: jclass("...PyBridge")).
-keep class dev.otto.phone.bridge.PyBridge { public static *; }

# The instrumented smoke test (androidTest) runs against the shrunk "minified" build: what it reaches
# directly in the app must survive shrinking there too.
-keep class dev.otto.phone.ui.MainActivity { *; }
-keep class dev.otto.phone.access.OttoAccessibilityService { *; }
-keep class dev.otto.phone.access.OttoAccessibilityService$Companion { *; }
-keep class dev.otto.phone.transport.EmbeddedTransport$Companion { *; }
