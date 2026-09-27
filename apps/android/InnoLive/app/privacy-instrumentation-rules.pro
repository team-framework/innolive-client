# The test APK shares these runtime APIs with the minified target application.
# Included only with -PprivacyReleaseTest=true; product Release has no such keeps.
-keep class kotlin.** { *; }
-keep class androidx.tracing.Trace { *; }
# R8 can otherwise merge away APIs referenced only by the separate test APK.
# Keep their shape for instrumentation while allowing method/body optimization.
-keep,allowoptimization class com.framework.innolive.feature.live.** { *; }
-keep,allowoptimization class com.google.ai.edge.litert.** { *; }
-keep,allowoptimization class ai.onnxruntime.** { *; }
-keep,allowoptimization class org.webrtc.** { *; }
-keep,allowoptimization class androidx.camera.** { *; }
-keep,allowoptimization class androidx.lifecycle.** { *; }
-keep,allowoptimization class androidx.activity.ComponentActivity { *; }
-keep,allowoptimization class androidx.core.content.ContextCompat { *; }
