# InnoLive Client Agent Guide

## Mission

Build one InnoLive product across five platform-native clients:
macOS, iOS, Android, Windows, and Web. Agents receive natural-language tasks,
but must turn them into bounded, verifiable changes.

## First read

- `docs/architecture.md`: ownership and shared-contract boundaries
- `docs/platform-matrix.md`: platform technology and UX differences

## Natural-language task protocol

Before editing, identify:

1. Target app(s): `mac`, `ios`, `android`, `windows`, or `web`.
2. Feature boundary: UI, camera/microphone, media/WebRTC, local storage,
   server API, or signaling.
3. Contract impact: none, additive, or breaking.
4. Acceptance criteria and an executable or manual verification path.

If the platform or expected behavior is ambiguous, inspect the relevant
specification and existing client first. Ask before making a breaking contract
change or broadening the requested platform scope.

## Ownership

- `apps/<platform>/` owns native UI, permissions, media capture, secure storage,
  platform lifecycle, and platform-specific tests.
- `contracts/` owns API payloads, WebRTC signaling payloads, state vocabulary,
  error codes, fixtures, and compatibility expectations.
- `docs/` owns behavior specifications; do not use source comments as the only
  place an inter-platform rule is defined.

## Rules

- Never share UI code simply because clients look similar.
- Do share schemas, fixtures, error codes, behavior specifications, and
  contract tests.
- Do not alter HTTP or WebRTC signaling fields without updating the matching
  contract, fixture, and compatibility notes.
- Keep secrets, stream keys, access tokens, and production endpoints out of
  repository files and logs.
- Prefer additive protocol changes while multiple app versions may coexist.
- `apps/mac` is the canonical home for future macOS client changes. The legacy
  `../innolive-mac` repository is preserved as a historical reference only.

## Completion criteria

Report changed files, target platform, contract impact, and verification.
Run the affected platform's focused build/test when tooling is available.

<!-- framework-collaboration-harness:start -->
# Framework 협업 규칙

작업을 시작하기 전에 작업 종류에 맞는 `.codex/skills/{issue,branch,commit,pull-request}/SKILL.md`를 읽는다.

- 이슈는 작업 유형과 한국어 작업 내용을 분명히 작성한다.
- 브랜치는 연결된 이슈를 만든 뒤 `<type>/<english-slug>/#<issue-number>` 형식으로 만든다.
- 커밋은 하나의 목적만 담고 `<type>: <한국어 변경 내용>` 형식을 사용한다.
- PR은 `<type>: <english-slug>/#<issue-number>` 형식으로 만들고 처음에는 Draft로 유지한다.
- 다른 사람이 만든 변경과 저장소 고유 규칙을 보존한다.
<!-- framework-collaboration-harness:end -->
