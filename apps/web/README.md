# Web client

Next.js App Router와 PostgreSQL 기반의 InnoLive 웹 랜딩 페이지입니다. 사전신청은
Server Action으로 처리하므로 DB 연결 정보가 브라우저에 노출되지 않습니다.

## Requirements

- Node.js 24 이상
- npm 11 이상
- Docker Compose (홈서버 배포 시)

## Local development

```bash
npm install
cp .env.local.example .env
docker compose -f docker-compose.yml -f docker-compose.local.yml up -d db
npm run dev
```

로컬 개발용 override는 PostgreSQL을 `127.0.0.1:5433`에만 노출합니다. 운영용
Compose에는 DB 포트를 공개하지 않습니다.

## Verification

```bash
npm run typecheck
npm run build
```

## Structure

- `app`: 페이지, Server Action, 개인정보처리방침
- `components`: 클라이언트 상호작용 컴포넌트
- `lib`: PostgreSQL 접근과 사전신청 저장 로직
- `db/init`: PostgreSQL 초기 스키마

## Environment

`.env.example`을 `.env`로 복사한 뒤 실제 DB 비밀번호와 개인정보 문의 이메일을
설정합니다. `.env`는 커밋하지 않습니다.

## Home server deployment

독립 서버에서는 자체 Caddy를 포함해 실행할 수 있습니다.

```bash
cp .env.example .env
docker compose --profile standalone-proxy up -d --build
```

`chaeyn` 홈서버는 이미 실행 중인 공용 Caddy를 사용합니다. GitHub Actions는
`main`의 웹 변경을 감지해 서버에서 `docker-compose.server.yml`을 적용하고,
`innolive.221.164.162.113.sslip.io`를 `127.0.0.1:3010`에 연결합니다.

배포 전 개인정보처리방침의 문의 이메일(`NEXT_PUBLIC_PRIVACY_CONTACT_EMAIL`)과
실제 운영 주체 정보를 검토·확정해야 합니다.
