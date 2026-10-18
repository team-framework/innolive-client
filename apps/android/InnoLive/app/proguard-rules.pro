# Native entry points use name-based JNI symbols rather than RegisterNatives.
-keep class com.framework.innolive.feature.live.privacy.PrivacyNativePixels { *; }
-keep class com.framework.innolive.feature.live.privacy.PrivacyNativeGpuModel { *; }
-keep class com.framework.innolive.feature.live.privacy.PrivacyNativeGpuFence { *; }
-keep class com.framework.innolive.feature.live.privacy.PrivacyNativePostprocessor$Native { *; }
# ONNX Runtime JNI constructs NodeInfo, TensorInfo, OnnxTensor and friends by name; the AAR ships no consumer rules.
-keep class ai.onnxruntime.** { *; }
# LiteRT JNI (liblitert_jni.so) constructs TensorType, TensorType$Layout, TensorBufferRequirements and
# LiteRtException by name and reads TensorType$ElementType constants; the AAR keeps only @UsedByReflection.
-keep class com.google.ai.edge.litert.** { *; }
# Every class and constructor native code looks up is listed in jni-keep-contract.txt and checked after R8.
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
