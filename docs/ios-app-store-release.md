# iOS App Store 릴리즈

## 0.1.1 빌드 5

- 대상: iOS 앱, iOS 18.0 이상
- 기준 코드: `145e4ada3220f4d05dbb34bdb3af84abc50fc48e` 이후 버전 설정 변경
- 계약 영향: 없음. 최신 main에 병합된 HTTP·WebRTC 계약 유지
- 배포 설정: 심사 승인 후 자동 출시, 즉시 업데이트 제공

### 포함 기능

- 방송 준비 시점의 세션 연결, 연결 진행 상태와 복구 안내
- YouTube·SOOP·CHZZK 대상 선택, 복수 송출, 방송 중 정보 수정
- 요금제·남은 방송 시간·업그레이드 제안 표시
- 서버 및 온디바이스 AI 처리, 기기 내 얼굴 등록
- 카메라 영상 조절, 동의 유지, iOS 18 호환 처리

### 필수 모델

모델 생성물은 Git에서 제외한다. Release archive 전에 아래 세 패키지를
`apps/ios/InnoLive/InnoLive/Resources/`에 준비해야 한다. 모델 없이도 앱 빌드는
성공하므로 빌드 성공만으로 온디바이스 기능의 배포 준비를 판단하지 않는다.
배포 앱과 내보낸 IPA에 세 `.mlmodelc` 디렉터리가 있는지 확인한다.

| 패키지 | 원본 SHA-256 | 생성 패키지 크기 |
| --- | --- | ---: |
| `PrivacyDetector.mlpackage` | `307e9b5895654d25d264903451abc4acad9ee30f5e2f2bf64af164a9f46bc115` | 5,656,020바이트 |
| `PrivacyFaceRecognizer.mlpackage` | `04b4bee1de7cefa9e97900f8449fca906d8afbab2029bd39cc5049d33e927ed9` | 232,905,024바이트 |
| `PrivacyYuNet.mlpackage` | `8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4` | 291,013바이트 |

변환 환경은 `scripts/requirements-ios-privacy.txt`, 생성 방법은
[온디바이스 AI](ios-on-device-privacy-lab.md)와
[기기 내 얼굴 등록](ios-local-face-registration.md)을 따른다.
얼굴 인식과 YuNet 변환기는 iOS 18을 최소 버전으로 지정한다.

### 검증

- iOS 전체 테스트 461개 실행: 최초 458개 통과, 2개 실패, 1개 건너뜀
- 한국어·한국 지역 및 서명을 적용한 재검증에서 실패했던 2개 통과
- 공개 이미지 fixture와 생성한 모델을 포함한 native YuNet 검사 1개 통과. 총 461개 테스트 검증 완료
- 얼굴 인식 무작위 입력 3개 변환 비교: 코사인 유사도 최솟값 0.99988973, 최대 절대 오차 0.00202225
- YuNet 입력 크기 3종 변환 비교: 최대 절대 오차 0.00000477
- 변환 수치 검사는 실제 얼굴 인식 정확도·기기 속도·지속 송출 품질을 검증하지 않음

### 배포 상태

- App Store Connect 0.1.1 버전 생성
- 한국어·영어 설명과 변경 사항, 최신 심사 메모 저장
- 모델 3종을 포함한 Release archive와 App Store IPA 생성 및 서명 검증 통과
- IPA 버전 0.1.1(5), 최소 iOS 18.0, 229,281,552바이트 확인
- App Store Connect 업로드 및 심사 제출 진행 중
