# 플랫폼별 방송 설정

범위: iOS P1 방송 설정. 2026-09-30 서버 main의
`internal/server/server.go`, `youtube_categories.go`,
`internal/auth/chzzk_oauth_http.go`, `internal/session/chzzk_broadcast.go`를 확인했다.
실제 배포 서버와 플랫폼 송출 검증은 별도로 수행한다.

## 저장과 방송 준비

세션 요청에는 Bearer access token과 `X-Session-Owner-Token`이 필요하다.

- `PUT /sessions/{id}/broadcast?provider=youtube`:
  `title`, `description`, `privacy`, `made_for_kids`, `category_id`.
  category ID는 숫자 문자열이다. 기존 필드와 호출 순서를 유지한다.
- `PUT /sessions/{id}/broadcast?provider=chzzk`:
  `title`, `category_type`, `category_id`, `tags`.
- 저장 성공 후 같은 대상에 `POST /sessions/{id}/stream/prepare`를 보낸다.
  body는 `{"provider":"youtube"}` 또는 `{"provider":"chzzk"}`이다.
  저장 실패, 세션 무효화, 계정 변경 뒤에는 prepare를 보내지 않는다.
- prepare는 라이브 공개 동작이 아니다. 공개에는 사용자의 라이브 시작이 필요하다.

치지직 카테고리 종류는 GAME, SPORTS, ETC다. 종류와 ID는 함께 설정한다.
카테고리 없음은 두 필드를 **빈 문자열**로 보낸다. 생략하거나 null로 보내지 않는다.
태그는 최대 5개, 각 15 Unicode scalar까지이며 문자와 숫자만 허용한다.
제목은 100 Unicode scalar, YouTube 설명은 5,000 Unicode scalar까지다.
클라이언트는 공백뿐인 제목을 허용하지 않는다.

## 카테고리 목록

- `GET /auth/youtube/categories` → `{"categories":[{"id":"20","title":"Gaming"}]}`.
  서버는 계정에 지정 가능한 카테고리만 제공한다.
- `GET /auth/chzzk/categories?query={검색어}` →
  `{"categories":[{"category_type":"GAME","category_id":"…","category_value":"…","poster_image_url":"…"}]}`.
  클라이언트는 query를 URLQueryItem으로 인코딩한다.
- 목록·검색에는 access token이 필요하다. 세션 owner token은 필요하지 않다.
- 검색 결과 없음, 검색 요청 실패, 설정의 필드 오류를 서로 다른 상태로 표시한다.
- 설정의 `400 bad_request` 응답에서 `error.details.field`·`reason`을 보존하고 해당 필드에 오류를 표시한다.

## 직전 값과 초안

`GET /sessions/{id}/broadcast/defaults?provider={youtube|chzzk}`는
`title`, `description`, `privacy`, `made_for_kids`, `category_id`를 제공하고,
치지직에서는 `category_type`, `tags`를 추가할 수 있다. 필드는 null 또는 생략될 수 있다.
서버는 직전 값을 조회할 수 없으면 기본값을 반환할 수 있으므로 클라이언트는
200 응답을 실제 직전 방송이 있었다는 증거로 사용하지 않는다.

iOS 적용 순서:

1. 서버·InnoLive 사용자·플랫폼·연결 채널별 로컬 초안을 읽는다. 없으면 새 폼 기본값을 쓴다.
2. 직전 방송 값 사용이 켜졌고 아직 직접 편집하지 않았다면 서버 값을 적용한다.
3. 직접 편집한 초안은 이후 도착하는 defaults보다 우선한다.

직전 값 사용을 끄면 직접 편집하지 않은 폼은 처음 읽은 로컬 값으로 돌아간다.
직접 편집한 값은 유지한다. 조회 실패·세션 없음에서도 입력할 수 있다.
치지직 defaults에서 category_type이 없으면 공통 YouTube 폴백 category_id를 무시한다.
플랫폼별 초안·옵션을 분리하고 계정·채널 변경, 옵션 해제, 저장 시작으로 무효화된 응답은 적용하지 않는다.
소유자를 알 수 없는 기존 전역 로컬 설정은 다른 계정에 이관하지 않는다.
세션이 없는 설정 화면의 저장은 로컬 저장이며 준비 시 서버에 적용한다.

## 호환성

provider query, category_id 및 치지직 설정은 추가 계약이다.
provider를 생략하는 기존 YouTube 클라이언트와 기존 JSON 로컬 설정은 유지된다.
새 categoryID는 iOS 로컬 모델의 optional 필드로 기존 저장값을 읽을 수 있다.
서버 URL 생성은 path와 query를 분리한다.

P1 치지직 OAuth 연결과 동시 송출, P2 업그레이드 화면은 별도 작업이다.
현재 폼·defaults 편집기는 후속 추가 플랫폼 화면에서 재사용할 수 있다.
