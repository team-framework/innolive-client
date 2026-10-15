# YouTube multi-channel v1

Source: [server request, 2026-10-12](https://app.notion.com/p/3f7ba097c659808a978cc00a3ce15c95).

- GET `/auth/streaming/accounts`: optional `id` is the streaming account UUID; preserve all YouTube entries, channel_id, channel_title and reconnect_required.
- POST `/auth/youtube/connect`: unchanged native authorization code payload. Reconnecting the same channel updates it; another channel adds it, up to five.
- DELETE `/auth/streaming/accounts/youtube/{id}`: remove only that connection. A live target's account cannot be removed; server `streaming_account_in_use` remains authoritative.
- POST `/sessions/{id}/stream/prepare`: optional `account_id` selects YouTube connection. Omit for CHZZK. Multiple YouTube channels require selection.
- GET `/sessions/{id}/broadcast/defaults`: `provider=youtube&account_id={id}`. Settings draft and response generation are scoped to the selected account/channel. Ignore previous channel responses after selection changes.
- PUT `/sessions/{id}/broadcast-mode`: optional `account_id` only when adding YouTube. Existing YouTube target keeps its original channel.
- Session `targets[]`: optional `account_id`, `channel_title`; missing fields remain compatible with earlier servers.

## Compatibility

New fields are additive. Legacy one-channel responses without `id` retain provider-only prepare/defaults/delete. Multi-channel responses must supply distinct account UUIDs. Authentication v2 is outside this change. No server API fields are renamed.
