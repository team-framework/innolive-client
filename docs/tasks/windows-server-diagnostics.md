# Windows server diagnostics

## Request

Connect the Windows client to `innolive.duckdns.org` and verify the same server-session flow used by the macOS client.

## Scope

- Target platform(s): windows
- Directories allowed to change: `apps/windows/`, `docs/tasks/`
- Explicitly out of scope: WebRTC media uplink, camera/microphone capture, authentication, and contract changes

## Reference and behavior

- Existing client or contract to inspect: `apps/mac/InnoLive/ServerEnvironment.swift`, `BroadcastManager.swift`, signaling v1
- Expected state transitions: idle → connecting → diagnostic success or failure; temporary server session is always deleted after a successful create.

## Acceptance criteria

- [x] Server URL is configurable with `INNOLIVE_SERVER_URL` and `INNOLIVE_SIGNALING_URL`.
- [x] Windows UI exposes a server diagnostic action and status message.
- [x] `https://innolive.duckdns.org/health` and a temporary `/sessions` create/delete flow succeed.

## Contract impact

- Impact: none
- Contracts/fixtures to update: none
- Compatibility requirement: existing signaling v1 remains unchanged.

## Verification

- Automated command: `dotnet build -c Debug -p:Platform=x64`
- Manual test path: click `서버 테스트` and confirm the health and session-cleanup result.
- Failure paths: show the HTTP or transport failure without starting a broadcast.
