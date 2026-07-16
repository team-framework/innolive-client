# InnoLive Client Agent Guide

## Mission

Build one InnoLive product across five platform-native clients:
macOS, iOS, Android, Windows, and Web. Agents receive natural-language tasks,
but must turn them into bounded, verifiable changes.

## First read

- `docs/architecture.md`: ownership and shared-contract boundaries
- `docs/platform-matrix.md`: platform technology and UX differences
- `docs/tasks/TEMPLATE.md`: required task format for substantial work

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
Run the affected platform's focused build/test when tooling is available. Run
`./scripts/verify-contracts.sh` for every contract change.
