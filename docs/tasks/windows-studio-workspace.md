# Windows studio workspace

## Request

Implement the selected InnoLive Figma desktop studio in the Windows client.
Track local studio editing parity in GitHub issue #2.

## Scope

- Target platform(s): windows
- Directories allowed to change: `apps/windows/`, `docs/tasks/`
- Explicitly out of scope: media capture, WebRTC transport, authentication, and contract changes

## Reference and behavior

- Existing client or contract to inspect: Figma node `350:153`, `apps/mac`, `contracts/signaling/v1.schema.json`
- Expected state transitions: broadcast state preserves `idle`, `connecting`, `live`, `stopping`, and `failed`; the initial UI demonstrates `idle` and `live` toggling only.

## Acceptance criteria

- [x] WinUI app builds for x64.
- [x] UI presents dual previews, scene/source controls, audio controls, and broadcast/record controls.
- [x] Scene add/select/duplicate/remove and source add/select/show/lock/reorder/remove work locally.
- [x] No shared signaling or API contract is changed.

## Contract impact

- Impact: none
- Contracts/fixtures to update: none
- Compatibility requirement: existing signaling v1 remains unchanged.

## Verification

- Automated command: `dotnet build -c Debug -p:Platform=x64`
- Manual test path: launch the Windows app and operate scene/source controls, audio toggles, recording, and broadcasting.
- Failure paths: WebRTC/media adapters and Windows Media Capture remain intentionally unimplemented.
