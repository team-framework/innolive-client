# Account deletion API v1

## Endpoint

```http
DELETE /auth/me
Authorization: Bearer <access_token>
```

The request has no body. A client treats the account deletion as successful
only when the server returns `204 No Content`.

## Errors and retry

The server uses the shared JSON error envelope for failures:

| HTTP | Code | Client behavior |
| --- | --- | --- |
| 401 | `unauthorized` | Refresh authentication and retry this request once |
| 409 | `withdrawal_in_progress` | Preserve the local session and offer retry after the current deletion finishes |
| 503 | `withdrawal_unavailable` | Preserve the local session and offer retry |
| 502 | `withdrawal_failed` | Preserve the local session and offer retry |

Native clients must keep the current login and retry ability when a network
request, server failure, or transient authentication refresh fails. A refresh
failure caused by an invalid refresh token follows the existing session
expiration behavior.

## Client cleanup after `204`

After a successful response, the client removes authentication secrets,
clears the local YouTube connection and broadcast preferences, and resets
active media state. Camera and audio preferences remain on the device.

## Server cleanup boundary

Server builds containing the cleanup for
[innolive-server#182](https://github.com/team-framework/innolive-server/issues/182)
complete these stages before returning `204`:

- Close the user's sessions and wait for media egress to stop.
- Delete prepared broadcasts, end live broadcasts, clean up reusable YouTube
  streams, and revoke stored Google and Apple provider credentials.
- Clear the user's AI whitelist and reference-face metadata, including the
  optional metadata file.
- Delete the user's `email_accounts`, `oauth_accounts`, `streaming_accounts`,
  `refresh_sessions`, and `users` rows in one database transaction. This
  removes password hashes, provider subjects, encrypted streaming credentials,
  refresh-token hashes, IP addresses, and User-Agent values in those rows.
- Clear the user's cached YouTube access token.

When a provider reports that its saved authorization is no longer valid, the
server can finish deleting its own data without authenticating further remote
cleanup requests. Previously published YouTube videos remain subject to the
YouTube account's controls; this endpoint does not delete the channel's video
history.

A temporary provider, AI, file, or database failure prevents `204`. Earlier
cleanup stages may already have completed. The server preserves account rows
for retry, stores completed reusable-stream cleanup in the database, and keeps
failed session-broadcast cleanup in process memory. The latter retry state does
not survive a server restart.

## Compatibility

Earlier server builds only cleared selected account fields and revoked refresh
sessions. Confirm that the server cleanup is deployed before relying on the
expanded deletion boundary.

`withdrawal_in_progress` is an additive error code. It can also be returned by
authenticated session, signaling, reference-face, and streaming-account
operations while deletion holds the user's operation gate. Clients keep their
local session until deletion succeeds and avoid starting competing operations.
The existing JSON fields, successful `204` response, and WebRTC signaling
schema are unchanged. Older clients may display this as a generic retryable
failure.

Examples: [HTTP conflict](../fixtures/account-deletion-conflict.v1.json) and
[signaling error](../fixtures/signaling-withdrawal-error.v2.json).
