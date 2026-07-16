# Architecture

## Product boundary

InnoLive is a live-broadcast studio with platform-native clients. Every client
can present a platform-appropriate studio, but all clients use the same server
and WebRTC signaling meaning.

```text
apps/mac | apps/ios | apps/android | apps/windows | apps/web
     Native UI, permissions, media, secure storage, client state
                                |
                                v
                  contracts (HTTP + signaling + errors)
                                |
                                v
                 authentication, broadcast, signaling services
```

## What is shared

- authentication and broadcast-session API schemas;
- signaling messages: offer, answer, ICE candidate, connection state, errors;
- broadcast state vocabulary: `idle`, `connecting`, `live`, `stopping`,
  `failed`;
- fixtures and compatibility tests;
- product behavior specifications.

## What is not shared

- SwiftUI, Compose, WinUI, and React UI code;
- platform camera, microphone, recording, permission, and secure-storage code;
- desktop and mobile navigation/layout decisions.

## Current reference

`../innolive-mac` is the current macOS behavioral reference. In particular,
its broadcast lifecycle, WebSocket signaling, WebRTC offer/answer/ICE flow,
camera switching, and studio state should be documented before equivalent
features are implemented in a new client.
