# Android 설정 레이어

대상: Android. HTTP·signaling 계약 변경 없음.

방송 화면 왼쪽 위 설정 버튼은 방송 화면 위에 설정 패널을 표시한다. 연결 중에는 기존 조건에 따라 버튼을 비활성화한다. 패널은 20dp 둥근 모서리와 테마 surface 색상의 90% 불투명도를 사용하며, 방송 영상과 경과 시간은 배경에서 계속 갱신된다. Figma의 실시간 배경 blur는 적용하지 않는다.

계정, 기기에서 지원하는 카메라 해상도·카메라 선택, 오디오 입력, 기존 카메라 보정, 방송 플랫폼·계정·방송 설정, AI 처리 방식, 개인정보 처리방침, 로그아웃·계정 삭제를 기존 동작과 연결한다. 지원하지 않는 플랫폼이나 볼륨 조절 기능을 추가하지 않는다. 요금제 사용량 표시와 방송 가이드 다시보기는 현재 main의 기존 기능을 유지한다.

`LiveScreen`은 설정 `NavEntry` 밖에서 Activity lifecycle을 사용하며, 설정과 하위 선택 화면을 열어도 composition에 남는다. 이에 따라 `CameraPreview`의 dispose와 CameraX unbind가 설정 이동으로 발생하지 않는다. 설정은 새 카메라나 WebRTC 세션을 만들지 않는다. 카메라 기기·해상도를 직접 변경하면 기존 CameraX 재바인딩 동작을 사용한다. 로그인 상태 종료와 계정 삭제는 기존 세션 정리 동작을 유지한다.

닫기 버튼은 설정을 닫고, 하위 화면의 뒤로 버튼은 이전 설정 화면으로 돌아간다. 패널 밖을 누르거나 시스템 뒤로를 사용해도 같은 back stack을 사용한다. 계정 삭제 처리 중·미완료 상태의 기존 이동 제한은 유지한다. 패널이 열려 있는 동안 배경 방송 화면은 접근성 탐색에서 제외한다.

## 검증

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
adb -s DEVICE_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s DEVICE_SERIAL install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s DEVICE_SERIAL shell am instrument -w -r \
  -e class com.framework.innolive.app.StudioSettingsLayerTest,com.framework.innolive.feature.settings.AIProcessingSettingsScreenTest,com.framework.innolive.feature.settings.AccountDeletionSettingsScreenTest \
  com.framework.innolive.test/androidx.test.runner.AndroidJUnitRunner
```

`StudioSettingsLayerTest`는 실제 CameraX 프레임 콜백을 사용해 설정 진입, 해상도 선택 화면 진입·선택 후 복귀, 카메라 설정 및 방송 설정 레이어 진입, 설정 닫기 동안 프레임이 계속 전달되는지 확인한다. 같은 studio composition이 1회 생성되고 화면 종료 전에는 해제되지 않는지도 확인한다. 방송 설정 화면의 내용은 테스트용 대체 화면이며, 실제 방송 API나 시청자 재생까지 검증하지 않는다.

실제 기기에서 라이브 방송을 시작한 뒤 설정과 각 하위 화면을 열고 닫으면서 시청자 영상·오디오와 방송 시간의 연속성을 확인하는 E2E 검증은 별도로 필요하다. 이 변경은 앱 백그라운드 방송이나 OS에 의한 Activity 종료 대응을 추가하지 않는다.

2026-10-02 검증: 단위 테스트 249개, debug 앱·계측 APK 빌드 통과. `emulator-5554`에서 설정 레이어·AI 처리·계정 삭제·인증 후 설정/로그아웃 계측 테스트 8개 통과. 최종 구분선 에셋 반영 후 설정 레이어 계측 테스트 1개 재통과 및 화면 배치 확인. 실제 기기·방송 서버·시청자 영상/오디오 E2E는 미실행.

## 2026-10-11 원격 main 통합 검증

- 원격 main `6e8283cd` 기준 통합 후 기존 요금제 표시와 방송 가이드 다시보기 유지.
- `:app:testDebugUnitTest` 309개 통과, `:app:assembleDebug`와 `:app:assembleDebugAndroidTest` 통과.
- Pixel_10 Android 17에서 `StudioSettingsLayerTest`, `AIProcessingSettingsScreenTest`, `AccountDeletionSettingsScreenTest`, `PlanUsageScreenTest`, `AuthenticatedNavigationTest`, `BroadcastTutorialRecreationTest` 총 14개 통과, 실패·건너뛰기 0개.
- 설정 및 하위 화면 이동 중 CameraX 프레임 공급과 studio 구성 유지 검증. 실제 기기·방송 서버·외부 시청자 영상과 오디오 E2E는 미실행.
