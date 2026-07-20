# Web client

`Vite`, `React`, `Tailwind CSS`, `Axios`, `TanStack Query` 기반의 단일 페이지 웹
클라이언트입니다.

## Requirements

- Node.js 20.19 이상
- npm 10 이상

## Local development

```bash
npm install
npm run dev
```

## Verification

```bash
npm run build
```

## Structure

- `src/app`: 애플리케이션 초기화, 전역 Provider, 전역 스타일
- `src/pages`: 화면 단위 UI
- `src/shared/api`: 공통 Axios 인스턴스와 TanStack Query 설정

## API base URL

`VITE_API_BASE_URL`을 설정하면 Axios 요청의 base URL로 사용합니다. 설정하지
않으면 상대 경로로 요청합니다. `VITE_*` 값은 브라우저에 노출되므로 비밀 값이나
토큰을 저장하면 안 됩니다.
