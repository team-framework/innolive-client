# 테스트 APK는 난독화된 대상 앱과 이 런타임 API를 공유합니다.
# -PprivacyReleaseTest=true일 때만 포함하며, 제품 Release에는 이런 keep이 없습니다.
-keep class kotlin.** { *; }
-keep class androidx.tracing.Trace { *; }
# 그렇지 않으면 R8이 별도 테스트 APK에서만 참조하는 API를 병합해 없앨 수 있습니다.
# 계측 테스트를 위해 형태는 유지하되 메서드·본문 최적화는 허용합니다.
# 네이티브 라이브러리(LiteRT, ONNX Runtime)는 proguard-rules.pro의 운영 keep만 쓰므로, 이 빌드는
# 출시 릴리스와 같은 JNI 대상 클래스를 제거합니다.
-keep,allowoptimization class com.framework.innolive.feature.live.** { *; }
-keep,allowoptimization class org.webrtc.** { *; }
-keep,allowoptimization class androidx.camera.** { *; }
-keep,allowoptimization class androidx.lifecycle.** { *; }
-keep,allowoptimization class androidx.activity.ComponentActivity { *; }
-keep,allowoptimization class androidx.core.content.ContextCompat { *; }
