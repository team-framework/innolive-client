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

## Server retention boundary

The endpoint currently revokes the encrypted Apple provider refresh token,
revokes authentication refresh sessions, clears selected user and OAuth
fields, and closes active stream sessions. This contract does not promise that
email account rows or password hashes, OAuth provider subjects, streaming
credentials, or reference-face records are removed. Those records require a
separate server cleanup review, so clients must not present this endpoint as a
guarantee that every server-side record has been erased.

See the follow-up server cleanup issue: [innolive-server#182](https://github.com/team-framework/innolive-server/issues/182).

This is an additive client contract. It does not change API fields or WebRTC
signaling payloads.
