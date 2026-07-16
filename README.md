# InnoLive Client

InnoLive's native multi-platform client monorepo.

## What is InnoLive?

InnoLive is a live-broadcast studio that helps a broadcaster prepare, start,
and monitor a stream through real-time WebRTC communication with an AI
processing server.
It is designed for privacy-aware live broadcasting: the broadcaster can manage
reference faces and send a mosaic policy to the server so that faces requiring
protection can be processed before the stream is delivered to its destination.

The client workflow is:

1. Select a camera, audio settings, scenes, and stream destinations.
2. Create a broadcast session and establish a WebRTC connection with the server.
3. Send media to the AI processing server over WebRTC and monitor its processed
   video preview.
4. Deliver the processed live stream to configured platforms such as YouTube,
   CHZZK, and SOOP.

This repository will provide that workflow through platform-native clients for
macOS, iOS, Android, Windows, and Web.

## Applications

| App | Stack | Status |
| --- | --- | --- |
| `apps/mac` | Swift, SwiftUI, AppKit/AVFoundation | Migrated baseline |
| `apps/ios` | Swift, SwiftUI, AVFoundation | Scaffold |
| `apps/android` | Kotlin, Jetpack Compose, CameraX/WebRTC | Scaffold |
| `apps/windows` | C#, WinUI 3, Windows media APIs/WebRTC | Scaffold |
| `apps/web` | TypeScript, React, Vite | Scaffold |

The applications remain native to their platforms. Shared behavior belongs in
`contracts/`, not in a shared UI layer.

## Repository layout

```text
apps/             Platform-specific clients
contracts/        Versioned API and WebRTC signaling contracts
docs/             Architecture and agent task specifications
scripts/          Contract verification scripts
.github/          CI workflows
```

## Start here

1. Read `AGENTS.md` before assigning work to a coding agent.
2. Read `docs/architecture.md` and `docs/platform-matrix.md` before adding a
   platform feature.
3. Use `docs/tasks/TEMPLATE.md` for substantial work.
4. Run `./scripts/verify-contracts.sh` after modifying `contracts/`.

## Migration note

The macOS client was migrated into `apps/mac` from the committed baseline of
`../innolive-mac`. The original repository remains preserved; make future macOS
changes in this monorepo.
