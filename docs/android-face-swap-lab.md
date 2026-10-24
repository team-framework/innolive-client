# Android 실험실 얼굴 합성

대상은 설정 화면의 실험실 섹션이다. 방송 화면이나 설정 화면에 들어갈 때 `GET /admin/me`를
보내 200이면 실험실 섹션을 표시한다. 403·404는 관리자가 아닌 것으로 보고 숨긴다. 네트워크
실패도 숨기고, 다음 화면 진입에서 다시 확인한다. iOS 동작([ios-face-swap-lab.md](ios-face-swap-lab.md))과 같다.

실험실에는 iOS와 같이 비식별화 방법 드롭다운 하나만 둔다. 선택지는 블러·합성·토끼·부엉이이고 기본값은 블러다.
선택값은 `live_face_anonymization` 환경설정의 `method`에 저장하고, 이전 버전 호환을 위해 합성 여부를 `face_swap`에도 쓴다.
토끼·부엉이는 온디바이스 처리에서만 쓰며 연결을 만들 때 처리 파이프라인에 고정한다([on-device-animal-assets.md](on-device-animal-assets.md)). 합성을 고르면 서버 AI 세션 생성 요청
(`POST /sessions`)에 `face_anonymization: "face_swap"`을 보낸다. 블러는 서버 기본값이라 필드를
생략한다. 온디바이스 처리에서는 보내지 않는다. 서버가 `on_device` + `face_swap`을 400으로 거절하기 때문이다.
관리자가 아닌 계정으로 확인되면 저장된 선택(합성·동물)을 블러로 되돌린다.

모드는 세션 생성 때 고정된다. 비식별화 방법은 AI 처리 방식을 바꿀 수 있을 때에만 바꿀 수 있다. 서버 AI의
준비 전 연결이 남아 있으면 닫고, 다음 방송 준비에서 새 방식으로 세션을 만든다.

서버가 합성을 거절하면 방송 준비가 실패하고 실험실에서 블러로 바꾸라는 안내를 표시한다.

| 응답 | 의미 |
| --- | --- |
| 403 `face_swap_not_allowed` | 관리자(`ADMIN_USER_IDS`)가 아닌 계정 |
| 503 `face_swap_unavailable` | `AI_FACE_SWAP_ENABLED`가 꺼진 배포 |

## 검증

`ServerSessionRequestTest`에서 처리 방식별 요청 본문을 검사한다. `ServerErrorGuidanceTest`에서는 합성 거절 안내를,
`AdministratorStatusTest`에서는 `/admin/me` 403·404 판정을 검사한다.

실기기 확인 절차:
1. 관리자 계정으로 로그인해 설정 화면에 실험실 섹션이 보이는지 확인한다.
2. 비관리자 계정에서 실험실 섹션이 보이지 않는지 확인한다.
3. 합성을 고르고 서버 AI로 방송을 준비해 미리보기와 송출 화면의 얼굴이 합성되는지 확인한다.
