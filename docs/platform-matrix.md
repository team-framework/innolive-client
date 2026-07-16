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

