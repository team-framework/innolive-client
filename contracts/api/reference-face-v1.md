# Reference Face API v1

## Purpose

Reference faces identify authenticated users who should not be anonymized by
the video-processing pipeline. Reference-face ownership is account-scoped, not
broadcast-session-scoped.

## Authentication

All endpoints require the current access token.

```http
Authorization: Bearer <access_token>
```

Clients must not send or derive a `client_id`. The server derives the AI client
scope from the authenticated user. Reference-face endpoints do not require a
session owner token or `session_id`.

After a `401`, native clients refresh authentication and retry the same request
at most once.

## Register or replace one face

```http
POST /reference-face
Content-Type: multipart/form-data; boundary=...
```

The multipart body contains one JPEG file:

| Field | Filename | Meaning |
| --- | --- | --- |
| `image` | `reference-face.jpg` | Replace the authenticated user's current reference-face set |

Client image preparation:

- show, detect, and upload the same centered square crop;
- require a source whose shorter edge is at least 500 pixels;
- resize the crop to 500 x 500 pixels;
- encode it as JPEG at quality 0.9;
- detect exactly one stable face locally before uploading;
- block duplicate uploads while a request is in flight.

The server also accepts JPEG, PNG, or WebP uploads up to 10 MiB each. The
broader server limits are defensive and do not replace the client preparation
rules above.

Success returns `201 Created` and a reference-face status. Clients treat the
operation as successful only when the response is 2xx and `registered` is
`true`.

## Append multiple faces

The multipart field `images` appends files to the current set. The server
accepts at most 20 files in one request. Mobile automatic registration uses the
single `image` replacement contract unless a multi-exemplar flow is explicitly
designed.

## Read status

```http
GET /reference-face
```

```json
{
  "registered": true,
  "source": "api",
  "registered_at": "2026-08-30T00:00:00Z",
  "client_id": "server-derived-value",
  "count": 1,
  "faces": [
    {
      "face_id": "uuid",
      "registered_at": "2026-08-30T00:00:00Z"
    }
  ]
}
```

`source` and `registered_at` may be `null`. Worker restarts can clear the
in-memory AI whitelist before persisted server metadata is reconciled, so this
status is not end-to-end proof that every worker currently holds the face.

## Delete

```http
DELETE /reference-face
DELETE /reference-face/{face_id}
```

Successful deletion returns `204 No Content`. A missing individual face returns
`404 not_found`.

## Errors

Errors use the shared JSON error envelope. Relevant codes include:

| HTTP | Code | Client behavior |
| --- | --- | --- |
| 400 | `face_not_detected` | Keep or restart capture and ask for one centered face |
| 400 | `invalid_image` | Capture and encode a new image |
| 400 | `reference_rejected` | Ask the user to center one clear face and retry |
| 400 | `bad_request` | Inspect details such as `ai_disabled` |
| 401 | authentication error | Refresh and retry once |
| 404 | `not_found` | Refresh the displayed face list |
| 502 | `ai_unavailable` | Preserve the current UI state and offer retry |
