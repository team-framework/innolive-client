# Windows studio workspace

## Request

Implement the selected InnoLive Figma desktop studio in the Windows client.

## Scope

- Target platform(s): windows
- Directories allowed to change: `apps/windows/`, `docs/tasks/`
- Explicitly out of scope: media capture, WebRTC transport, authentication, and contract changes

## Reference and behavior

- Existing client or contract to inspect: Figma node `350:153`, `apps/mac`, `contracts/signaling/v1.schema.json`
- Expected state transitions: broadcast state preserves `idle`, `connecting`, `live`, `stopping`, and `failed`; the initial UI demonstrates `idle` and `live` toggling only.

## Acceptance criteria

- [ ] WinUI app builds for x64.
- [ ] UI presents dual previews, scene/source controls, audio controls, and broadcast/record controls.
- [ ] No shared signaling or API contract is changed.

## Contract impact

- Impact: none
- Contracts/fixtures to update: none
- Compatibility requirement: existing signaling v1 remains unchanged.

## Verification

- Automated command: `dotnet build -c Debug -p:Platform=x64`
- Manual test path: launch the Windows app and toggle recording, broadcasting, and add a source.
- Failure paths: WebRTC/media adapters remain intentionally unimplemented.
