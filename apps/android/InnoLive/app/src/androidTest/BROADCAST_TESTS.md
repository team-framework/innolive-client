# 방송·비식별화 연동 테스트

## 자동으로 확인하는 범위

| 테스트 | 범위 | 경계 |
| --- | --- | --- |
| `BroadcastApiFlowTest` | Off 변경 → 준비 → 라이브 시작 → On/Off → 변경 실패·재시도 → 종료·재준비. 설정 저장 실패 시 prepare 미호출, 준비되지 않은 라이브 재시도, 종료 실패 표시, 세션 삭제 404·중복 close | 실제 `WebRtcConnection`의 요청 생성, 인증 헤더, JSON, 응답 처리와 큐를 실행한다. 연결된 세션과 OkHttp 응답을 테스트에서 주입한다. 네트워크·WebRTC 미디어는 실행하지 않는다. |
| 기존 `AnonymizationChangeTest` | 변경 실패 시 마지막 확인값 유지, 재시도, 이전 요청·이전 세션의 늦은 응답 무시 | 서버와 통신하지 않는 상태 전이 단위 테스트 |
| 기존 `WebRtcSessionStateLifecycleTest`, `BroadcastPreparationTest` | 선택 저장, 인증 실패·재시도, 연결 중 종료, 준비 요청 거부 후 복구 | 인증 완료 전의 수명주기 및 상태 검증 |
| `BroadcastLiveDeviceTest` | 실제 비공개 준비·라이브 시작, 방송 중 On/Off, 종료 후 연결·원격 트랙·Off 선택 유지, 새 연결에 Off 재적용 | 실제 기기·서버·Google 로그인·YouTube 연동 필요. 별도 인자 없이 실행하면 건너뛴다. |
| `WebRtcRecoveryPolicyTest`, `WebRtcSignalTest` | 서버 복구 창·시도 횟수, ICE 세대 구분, 복구 메시지 형식 | 실제 네트워크 전환과 ICE 연결 성공은 검증하지 않는다. |

가짜 응답을 주입하는 테스트는 운영용 HTTPS 검증을 끄지 않는다. 테스트 코드에서만 세션과 HTTP 클라이언트를 교체한다. 응답 코드와 필드가 서버의 모든 동작을 대표한다고 가정하지 않는다.

## 실행

`apps/android/InnoLive`에서 Android SDK와 JDK를 설정한 뒤 실행한다.

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest
adb -s DEVICE_SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s DEVICE_SERIAL install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s DEVICE_SERIAL shell am instrument -w -r \
  -e class com.framework.innolive.feature.live.BroadcastApiFlowTest,com.framework.innolive.feature.live.BroadcastPreparationTest,com.framework.innolive.feature.live.WebRtcSessionStateLifecycleTest \
  com.framework.innolive.test/androidx.test.runner.AndroidJUnitRunner
```

실제 송출은 아래 명령으로 별도 실행한다. 승인된 테스트 계정·서버와 송출 가능한 카메라·마이크 환경을 사용한다. 이 테스트는 비공개 YouTube 라이브를 실제로 시작하고 영상·음성을 전송한다. 종료·연결 정리와 원래 비식별화 선택 복구를 시도하지만, 기기 종료나 통신 장애로 정리가 실패하면 YouTube Studio에서 방송 종료 여부를 확인해야 한다.

```bash
adb -s DEVICE_SERIAL shell am instrument -w -r \
  -e class com.framework.innolive.feature.live.BroadcastLiveDeviceTest \
  -e liveBroadcastLifecycle true \
  com.framework.innolive.test/androidx.test.runner.AndroidJUnitRunner
```

## 별도로 필요한 확인

- **시청자 측 영상:** Off 원본과 On 비식별화가 실제 YouTube 재생 화면에 반영되는지, 얼굴 처리가 적절한지 확인한다. 원격 트랙 객체와 앱의 LIVE 상태가 유지돼도 프레임이 정상 재생된다는 보장은 없다.
- **오디오·끊김·동기화:** 방송 중 On/Off, 종료 전후에 소리가 들리는지, 무음·단절·영상 지연이 없는지 실제 재생으로 확인한다. 현재 테스트는 음성 파형이나 시청자 플레이어를 분석하지 않는다.
- **네트워크 장애:** 서버가 PATCH를 적용한 뒤 응답만 유실될 수 있다. 클라이언트가 마지막 확인값을 유지하는 테스트만으로 서버와 항상 일치한다고 결론 내릴 수 없다. 제어 가능한 서버/프록시에서 응답 유실을 주입하고 서버 조회·로그와 대조해야 한다.
- **방송 중 ICE 복구:** 비공개 방송에서 Wi-Fi를 잠시 끊었다 다시 연결하고, 2초 내 자연 회복과 2초 이후 ICE restart를 각각 확인한다. 앱의 `RECONNECTING` 안내, 동일 세션 유지, 방향 잠금, YouTube 수신 영상·대기 화면을 확인한다. 서버 로그의 `WebRTC network recovery succeeded` 및 협상 ID를 대조하고, 50초 이상 단절 시 최종 실패와 방향 잠금 해제를 확인한다. 종료·로그아웃 중에는 복구가 재시작되지 않아야 한다.
- **실제 세션 삭제 404:** 자동 테스트는 404 응답 시 close 완료와 중복 삭제 방지만 확인한다. 이전 실제 실행의 404 원인은 같은 세션의 서버 수명주기 로그로 확인해야 한다. 세션·송출·미디어 자원이 누수 없이 정리됐는지도 별도 확인한다.
- **장치·앱 수명주기:** Wi-Fi/모바일 전환, 앱 프로세스 종료·재실행, 카메라·마이크 권한 철회, Bluetooth/USB 오디오는 별도 기기 시나리오가 필요하다. ViewModel 재생성 테스트가 OS 프로세스 종료를 대신하지 않는다.

이 항목 중 일부는 향후 재생 프레임·오디오 분석과 장애 주입 장치를 갖추면 자동화할 수 있다. 현재 추가한 테스트 코드만으로 완료 판정하지 않는다는 의미다.
