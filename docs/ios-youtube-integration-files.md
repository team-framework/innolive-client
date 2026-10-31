# iOS YouTubeIntegration 파일 구성

작성 기준: 2026-10-31 (#803)

`YouTubeIntegration`은 방송 연결·준비·송출 상태를 소유하는 단일 `ObservableObject`다. 한 파일 3,400줄을 기능별 extension 파일로 나눴고 동작은 바꾸지 않았다.

## 파일별 역할

| 파일 | 내용 |
| --- | --- |
| `YouTubeIntegration.swift` | 상태 선언, `init`, 요금제·인증 설정, 화면용 계산 속성, 방송 설정 저장, 초기화·오류 처리, 화면 방향 고정, 작업 세대 관리 |
| `+Accounts` | YouTube·치지직 계정 연결과 해제, 채널 선택, 설정 편집 범위, 송출 동의 |
| `+Preparation` | 방송 준비, 온디바이스 전환, 대상별 준비·실패 처리 |
| `+Session` | 세션 생성·삭제, 세션 응답 반영, polling |
| `+Video` | 영상 연결, 카메라·오디오·화질 전환, 영상 재연결 |
| `+BroadcastControl` | 카운트다운, 라이브 시작, 대상별 중지·일시정지·재개, 백그라운드 처리, 방송 종료 |
| `+Privacy` | AI 처리 방식 전환, 화면 블러, 모자이크 설정, 비식별화 전환 |
| `+BroadcastMode` | 방송 중 해상도·대상 변경 |
| `+UpgradeOffer` | 업그레이드 제안 선택·거절 |
| `+LiveSettings` | 방송 중 대상별 방송 정보 수정 |
| `+DirectPublish` | 앱 직접 송출, 하트비트 |
| `+Testing` | `DEBUG` 전용 테스트 보조 메서드 |

## 접근 제어

- Swift extension은 저장 속성을 가질 수 없어 상태 선언을 본 파일에 둔다.
- `private` 멤버는 같은 파일의 extension에서만 보이므로, 다른 파일이 쓰는 상태와 메서드는 모듈 범위(`internal`)로 열었다.
- 화면 코드는 `@Published` 상태를 읽기만 하고 변경은 `YouTubeIntegration` 메서드로 요청한다.
- 한 파일 안에서만 쓰는 멤버는 `private`을 유지한다.
