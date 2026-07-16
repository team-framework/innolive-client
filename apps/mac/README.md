# macOS client

The existing SwiftUI/AppKit macOS client was migrated here from the committed
state of `../innolive-mac`. Its source, tests, Xcode project, and verification
script now live in this directory.

## Local server configuration

The app falls back to `http://127.0.0.1:8000` when no configuration is present.
To override it locally, copy `InnoLive/Config/Server.env.example` to
`InnoLive/Config/Server.env` and set the required values. `Server.env` is
ignored and must never contain a production endpoint or secret in Git.

## Verification

```bash
./scripts/verify_innolive.sh
swift test
```
