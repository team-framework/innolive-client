# Platform Matrix

| Concern | macOS | iOS | Android | Windows | Web |
| --- | --- | --- | --- | --- | --- |
| UI | SwiftUI + AppKit | SwiftUI | Jetpack Compose | WinUI 3 | React |
| Camera/media | AVFoundation | AVFoundation | CameraX/media APIs | Windows media APIs | Browser MediaDevices |
| Permissions | macOS privacy | iOS privacy | Runtime permissions | Windows privacy/capabilities | Browser permission model |
| Secure secrets | Keychain | Keychain | Android Keystore | Credential Locker | HttpOnly session / Web Crypto as needed |
| Primary shape | Desktop studio | Mobile studio | Mobile studio | Desktop studio | Browser control/viewer studio |

## Common behavior

- The same session and signaling contracts must be used by all five clients.
- A broadcast state has the same semantic meaning everywhere.
- Permission and connection failures map to shared error codes, then each
  platform explains recovery using its native UI.
- Stream keys never leave platform-appropriate secure storage.

## UX freedom

Mobile and Web clients may use focused, responsive workflows rather than
replicating the macOS desktop layout. Feature parity should be explicit in a
task specification, not assumed from visual similarity.

### Connection timing (2026-09-30)

Android and iOS both wait until the user confirms broadcast preparation before
creating a session or starting WebRTC. Launch, home entry, and opening settings
do not connect. Android keeps the server connection when preparation is
cancelled. iOS stops prepared targets, tears down WebRTC and the session, and
returns to the local camera preview. A failed session delete stays in the
Keychain for the next preparation. See
[iOS connection flow](ios-connection-feedback.md) and
[Android broadcast preparation](android-broadcast-preparation.md).

### Android broadcast orientation (2026-09-25)

Android locks its Activity and CameraX output to the display direction accepted at
`goLive`, then releases the lock on start failure, successful stop, or terminal
connection failure. See [Android broadcast orientation](android-broadcast-orientation.md)
for state transitions and verification. Network recovery preserves the captured
rotation until the existing WebRTC session reconnects or fails terminally.
