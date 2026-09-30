# Native entry points use name-based JNI symbols rather than RegisterNatives.
-keep class com.framework.innolive.feature.live.privacy.PrivacyNativePixels { *; }
-keep class com.framework.innolive.feature.live.privacy.PrivacyNativeGpuModel { *; }
-keep class com.framework.innolive.feature.live.privacy.PrivacyNativeGpuFence { *; }
-keep class com.framework.innolive.feature.live.privacy.PrivacyNativePostprocessor$Native { *; }
# WorkManager's Room database is instantiated by its generated class name.
-keep class androidx.work.impl.WorkDatabase { *; }
-keep class androidx.work.impl.WorkDatabase_Impl { *; }
# WebRTC registers Java callbacks by name, including the SDK's new JNI bootstrap.
-keep class org.jni_zero.** { *; }
-keep class org.webrtc.** { *; }
# ML Kit discovers these registrar names from the merged manifest and constructs them by reflection.
-keep class com.google.mlkit.vision.face.internal.FaceRegistrar { <init>(); }
-keep class com.google.mlkit.common.internal.CommonComponentRegistrar { <init>(); }
-keep class com.google.mlkit.vision.common.internal.VisionCommonRegistrar { <init>(); }
