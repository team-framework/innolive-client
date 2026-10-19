# The test APK shares these runtime APIs with the minified target application.
# Included only with -PprivacyReleaseTest=true; product Release has no such keeps.
-keep class kotlin.** { *; }
-keep class androidx.tracing.Trace { *; }
# R8 can otherwise merge away APIs referenced only by the separate test APK.
# Keep their shape for instrumentation while allowing method/body optimization.
# Native libraries (LiteRT, ONNX Runtime) use the production keeps in proguard-rules.pro only, so this
# build strips the same JNI-facing classes as the shipped release.
-keep,allowoptimization class com.framework.innolive.feature.live.** { *; }
-keep,allowoptimization class org.webrtc.** { *; }
-keep,allowoptimization class androidx.camera.** { *; }
-keep,allowoptimization class androidx.lifecycle.** { *; }
-keep,allowoptimization class androidx.activity.ComponentActivity { *; }
-keep,allowoptimization class androidx.core.content.ContextCompat { *; }
