# 네이티브 진입점은 RegisterNatives가 아닌 이름 기반 JNI 심볼을 씁니다.
-keep class com.framework.innolive.feature.live.privacy.PrivacyNativePixels { *; }
-keep class com.framework.innolive.feature.live.privacy.PrivacyNativeGpuModel { *; }
-keep class com.framework.innolive.feature.live.privacy.PrivacyNativeGpuFence { *; }
-keep class com.framework.innolive.feature.live.privacy.PrivacyNativePostprocessor$Native { *; }
# ONNX Runtime JNI는 NodeInfo, TensorInfo, OnnxTensor 등을 이름으로 생성하며, AAR에는 consumer 규칙이 없습니다.
-keep class ai.onnxruntime.** { *; }
# LiteRT JNI(liblitert_jni.so)는 TensorType, TensorType$Layout, TensorBufferRequirements,
# LiteRtException을 이름으로 생성하고 TensorType$ElementType 상수를 읽지만, AAR은 @UsedByReflection만 keep합니다.
-keep class com.google.ai.edge.litert.** { *; }
# 네이티브 코드가 조회하는 모든 클래스와 생성자는 jni-keep-contract.txt에 있으며 R8 이후 검사합니다.
# WorkManager의 Room 데이터베이스는 생성된 클래스 이름으로 인스턴스화됩니다.
-keep class androidx.work.impl.WorkDatabase { *; }
-keep class androidx.work.impl.WorkDatabase_Impl { *; }
# WebRTC는 SDK의 새 JNI 부트스트랩을 포함해 Java 콜백을 이름으로 등록합니다.
-keep class org.jni_zero.** { *; }
-keep class org.webrtc.** { *; }
# ML Kit은 병합된 매니페스트에서 이 registrar 이름을 찾아 리플렉션으로 생성합니다.
-keep class com.google.mlkit.vision.face.internal.FaceRegistrar { <init>(); }
-keep class com.google.mlkit.common.internal.CommonComponentRegistrar { <init>(); }
-keep class com.google.mlkit.vision.common.internal.VisionCommonRegistrar { <init>(); }
