# Android 방송 준비와 비식별화 상태

확인일: 2026-09-15. 구현 기준: `e9aa122` 및 선행 커밋. 관련 작업: [PR #177](https://github.com/team-framework/innolive-client/pull/177).

대상은 `apps/android`의 방송 화면, WebRTC 연결, 비식별화 선택과 기존 방송 API 호출 흐름이다. HTTP 및 signaling 계약 변경은 없다. 이 문서는 해당 브랜치의 구현을 설명하며, main 병합 여부나 다른 플랫폼의 동일 동작을 보장하지 않는다.

## 사용자 흐름

1. 앱 실행 후 방송 화면에서는 기기 카메라 미리보기를 표시한다. 이 시점에는 WebRTC를 연결하지 않는다.
2. 메인 화면의 **방송 준비**를 누르면 플랫폼 선택 및 방송 설정 창으로 이동한다. 설정 창을 열거나 뒤로 닫는 것만으로 연결하지 않는다.
3. 설정 창에서 제목·설명·아동용 콘텐츠 여부를 확인하고 **방송 준비**를 확정한다. 카메라·마이크 권한과 YouTube 계정 연동이 필요하다.
4. 연결이 없으면 세션 생성 → 저장된 비식별화 On/Off 적용 → 영상 전송 및 WebRTC 연결 완료를 기다린다. 이미 연결되어 있으면 기존 연결을 사용한다.
5. 연결 성공 후 방송 설정을 저장하고 YouTube 방송을 준비한다. 화면은 **라이브 시작** 상태가 된다.
6. **라이브 시작**을 누르면 준비된 방송을 실제 라이브로 전환한다. 준비 시점과 실제 라이브 시작 시점은 다르다.
7. **방송 준비 취소** 또는 **방송 종료**는 방송 중지 API를 사용한다. 연결을 닫는 명령과 분리되어 있으며, 준비 취소 후 같은 연결로 재준비할 수 있다.

수동 연결·해제 UI는 없다. 비식별화 아이콘은 WebRTC 연결 버튼이 아니라 비식별화 On/Off 선택 버튼이다. 이전의 ‘비식별화 버튼 → 연결 → 방송 준비’ 흐름을 대체했다.

## 계정 및 비식별화 상태

- Google 앱 로그인과 YouTube 방송 계정 연동은 별개다. 앱에 로그인했더라도 YouTube 미연동이면 방송을 준비할 수 없다.
- `MainActivity`의 실제 계정 존재 여부를 `hasYouTubeAccount`로 전달한다. 준비 허용 조건은 계정 존재, 재연동 불필요, 계정 작업 진행 중이 아님이다.
- `channel_title`은 표시용이다. 유효한 계정에 채널명이 없거나 비어 있어도 준비를 허용하며, 제목이 없다는 이유로 연동 버튼을 표시하지 않는다.
- 연결 상태와 서버에서 확인한 비식별화 상태를 별도로 관리한다. 서버의 `media.anonymization_enabled`가 true이면 `ENABLED`, false이면 `DISABLED`, 확인할 수 없으면 `UNKNOWN`이다.
- 연결 전 선택은 로컬에 저장한다. 앱을 다시 실행하거나 새 세션을 생성할 때 저장된 선택을 적용한다. 연결 중에는 초기 선택 변경을 막는다.
- 연결 후 아이콘 조작은 변경 API를 호출한다. 진행·실패 상태를 표시하고 중복 요청을 막으며, 서버가 성공을 확인한 선택만 다음 연결의 기본값으로 저장한다.

## API 순서와 실패 처리

| 단계 | 기존 API | 호출 조건 |
| --- | --- | --- |
| 세션 생성 | `POST /sessions` | 새 연결 필요 시 |
| 초기 비식별화 선택 적용 | `PATCH /sessions/{id}/anonymization`, `{"enabled": false}` 또는 true | 새 세션에서 영상 전송 전, 성공 응답 확인 |
| 방송 설정 저장 | `PUT /sessions/{id}/broadcast` | WebRTC 연결 성공 후 |
| 방송 준비 | `POST /sessions/{id}/stream/prepare` | 설정 저장 성공 후 |
| 라이브 시작 | `POST /sessions/{id}/stream/golive` | 준비 완료 후 사용자 조작 |
| 준비 취소·방송 종료 | `POST /sessions/{id}/stream/stop` | 준비 완료 또는 방송 중 |

세션 연결과 방송 준비는 사용자에게 한 번의 준비 동작으로 제공하지만, 내부 API를 동시에 호출하지 않는다. 연결 성공을 확인한 뒤 방송 준비를 진행한다.

### 앱 재실행 후 세션 복구 (2026-09-17)

Android는 `POST /sessions`의 생성 응답에서 받은 세션 ID와 owner token을 Android Keystore 기반 AES-GCM으로 암호화해 기기에 보관한다. 앱 프로세스가 정리 콜백 없이 종료된 뒤 다음 연결을 시작하면, 저장된 자격증명으로 자신이 소유한 이전 세션을 `DELETE /sessions/{id}`로 먼저 정리한 다음 새 세션을 생성한다. 삭제가 실패하면 새 세션을 만들지 않고 자격증명을 보존해 재시도할 수 있게 한다. 서버가 이미 세션을 정리해 404를 반환하면 저장된 자격증명을 지운다. 이 저장값은 클라우드 백업·기기 이전 대상에서 제외한다.

구버전 앱이 남긴 세션처럼 owner token이 저장되지 않은 상태의 `session_already_exists` 409는 클라이언트에서 임의로 삭제할 수 없다. 이 경우 기존 방송을 종료한 뒤 재시도하도록 안내한다. HTTP 및 signaling 필드는 변경하지 않는다.

- 잘못된 설정이나 부족한 미디어 권한은 연결 전에 차단한다. 설정 창 취소 후 다시 준비를 누르면 설정을 재확인할 수 있다.
- 연결 대기 및 방송 작업 중에는 중복 준비를 막는다. 연결 실패 시 방송 준비 API를 호출하지 않고 재시도를 허용한다.
- 준비 흐름의 연결 대기는 45초로 제한한다. 초과 시 연결을 정리하고 실패를 안내한다.
- 연결 종료 시 대기 작업을 취소한다. 세대 번호(`generation`)로 종료된 세션의 늦은 인증 응답과 콜백이 현재 상태를 덮어쓰지 못하게 한다.
- 네이티브 연결 객체가 준비 요청을 접수하지 못하면 `SAVING_SETTINGS`에 남지 않고 `FAILED`로 복구한다(`e9aa122`).

## 확인한 검증과 한계

2026-09-15에 수행한 검증 기록이다. 아래 결과는 실행한 시나리오에 한하며 전체 방송 E2E 완료를 뜻하지 않는다.

| 검증 | 결과 및 범위 |
| --- | --- |
| 자동 연결 변경의 단위 테스트 | 당시 76개 통과. 이후 계정 조건 수정 시에도 단위 테스트 및 debug·계측 APK 빌드 통과 |
| 실제 기기의 방송 준비 UI | 설정 취소·재진입, 필수 설정 확인, 자동 연결 진입, 인증 실패 후 재시도 확인 |
| 계정 조건 회귀 테스트 | `BroadcastPreparationUiTest` 3개 통과. 채널명 빈 문자열·null인 연동 계정 허용, 미연동·재연동 필요·계정 작업 중 차단 확인. 계정 상태를 제어한 UI 테스트이며 실제 서버의 채널명을 변경하지 않음 |
| 실제 계정 미연동 | 세션 생성 201, Off 적용 200, 방송 설정 저장 200 이후 `stream/prepare` 409 및 YouTube 연동 필요 안내 확인 |
| 실제 YouTube 연동 후 비공개 준비 | `BroadcastPreparationDeviceTest` 1개 통과. Off 자동 연결 후 설정 저장·prepare·stop·재준비·stop 모두 200. 같은 연결과 원격 트랙, Off 상태 유지 확인 |

실제 계정 테스트는 ViewModel과 카메라를 사용하는 계측 테스트로 수행했다. UI 버튼 경로는 별도의 UI 테스트로 확인했다. 실제 `stream/golive`와 시청자 측 영상·오디오 확인은 수행하지 않았다. 실제 기기에서 확인한 중지는 **준비 취소**이며, 방송 중 종료까지 검증한 것으로 해석하지 않는다.

테스트 정리 단계의 `DELETE /sessions/{id}` 로그에는 404가 관찰됐다. 삭제 시점에 세션이 이미 정리됐는지 원인은 추가 확인이 필요하며, 세션 삭제 응답을 성공 검증 항목에 포함하지 않는다. 위 실제 API 검증은 `2446e84` 시점, 계정 조건 UI 검증은 `45505df` 변경 기준이며 `e9aa122`를 포함한 전체 흐름을 재실행했다는 의미는 아니다.

## 재검증 방법

`apps/android/InnoLive`에서 실행한다. JDK 및 Android SDK 설정이 필요하다.

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
adb -s DEVICE_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s DEVICE_SERIAL install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s DEVICE_SERIAL shell am instrument -w -r \
  -e class com.framework.innolive.feature.live.BroadcastPreparationUiTest \
  com.framework.innolive.test/androidx.test.runner.AndroidJUnitRunner
```

실제 API 테스트는 Google 로그인과 YouTube 연동이 완료된 승인된 테스트 계정·서버에서만 다음과 같이 명시적으로 실행한다. 비공개 방송을 준비하고 취소하며, 라이브 시작은 호출하지 않는다. 인증 보존을 위해 앱을 삭제하거나 데이터를 초기화하지 않는다.

```bash
adb -s DEVICE_SERIAL shell am instrument -w -r \
  -e class com.framework.innolive.feature.live.BroadcastPreparationDeviceTest \
  -e liveBroadcastPreparation true \
  com.framework.innolive.test/androidx.test.runner.AndroidJUnitRunner
```

## 남은 업무

- YouTube 미연동·연동 처리 중 긴 계정 상태 문구와 버튼의 레이아웃 깨짐 수정. 기존 계정 정보 행의 폭 배분 문제이며 계정 존재 여부 조건 수정과는 별개다.
- Off 상태에서 실제 라이브 시작 → 시청자 측 원본 영상·오디오 확인 → 방송 종료 → 미리보기·선택 유지 검증.
- 방송 중 On/Off 전환 시 영상·오디오·연결 유지 및 토글 API 실패 시 표시·서버 상태 일치 검증.
- 최신 변경을 포함한 전체 흐름 재검증과 실제 권한 거부, 앱 재실행, 새 세션 재연결, 네트워크 변경 시나리오 보완.
- 테마 전환 시 관찰한 `YouTubeApi.close()` 충돌은 별도 안정성 작업으로 추적한다.

## 구현 위치

아래 경로는 `apps/android/InnoLive/app/src/main/java/com/framework/innolive` 기준이다.

- `app/MainActivity.kt`: 실제 YouTube 계정 상태를 화면에 전달.
- `feature/live/Live.kt`, `LiveScreenPresentation.kt`: 준비 버튼·설정 창과 진행 상태.
- `feature/live/components/YouTubeLiveSettingsDialog.kt`: 설정 검증, 연동 여부에 따른 준비 허용.
- `feature/live/WebRtcSessionViewModel.kt`, `WebRtcConnection.kt`: 연결 대기, 세션 수명주기, 초기 비식별화 선택 적용 및 방송 API 호출.
- `feature/live/WebRtcSessionState.kt`, `AnonymizationPreference.kt`, `AnonymizationControls.kt`: 상태 분리, 선택 저장, 아이콘 조작.
