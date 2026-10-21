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

iOS one-tap registration from the broadcast screen keeps the 500 x 500 JPEG rules
but uploads a crop around the face the user tapped instead of the centered crop:

- only faces whose shorter side is at least 100 source pixels are selectable;
- the square crop is centered on the face box and twice its longer side, with
  black padding outside the frame;
- every other face detected in the frame or in the enlarged crop is filled with
  black, and the selected face stays unmasked;
- the client checks for exactly one face in the final crop before uploading.

The server also accepts JPEG, PNG, or WebP uploads up to 10 MiB each. The
broader server limits are defensive and do not replace the client preparation
rules above.

Success returns `201 Created` and a reference-face status. Clients treat the
operation as successful only when the response is 2xx and `registered` is
`true`.

## Append multiple faces

The multipart field `images` appends files to the current set. The server
accepts at most 20 files in one request. iOS multiple-person registration sends one `images` file per registration, preserving
previously registered people. The original 500 x 500 crop and stable-face detection
flow still applies; iOS one-tap registration uses the tapped-face crop above.
The singular `image` field remains a replacement operation.

An optional text field `name` labels a single image. The server trims whitespace,
accepts at most 40 Unicode scalars, and rejects CR, LF, NUL, invalid UTF-8, or a
non-empty name combined with multiple files before contacting the AI worker.
Responses include optional `faces[].name`; old records without names remain valid.
Local registration uses a separate device store; see [AI processing](ai-processing-v1.md).

## Re-register one face

AI workers keep reference faces only in memory, and the server does not keep the
uploaded images. After a worker restart the server marks lost faces with
`reregistration_required` (see Read status). The user restores a marked face by
uploading a new photo for the same face:

| Field | Value |
| --- | --- |
| `face_id` | The `faces[].face_id` to restore |
| `images` | Exactly one JPEG file, prepared like a new registration |

The server registers the new photo, removes the previous worker entries, keeps
the same `face_id` and `name`, and clears `reregistration_required`. A non-empty
`name` also renames the face; native clients omit it to keep the current name.
Clients send the file as `images`, not `image`: a server without `face_id`
support then appends a face instead of replacing the whole set.

Success returns `201 Created` with the full status. An unknown `face_id` returns
`404 not_found`; clients refresh the list. Anything other than one image returns
`400 bad_request`.

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
  "reregistration_required": true,
  "faces": [
    {
      "face_id": "uuid",
      "registered_at": "2026-08-30T00:00:00Z",
      "reregistration_required": true
    }
  ]
}
```

`source` and `registered_at` may be `null`. Worker restarts can clear the
in-memory AI whitelist before persisted server metadata is reconciled, so this
status is not end-to-end proof that every worker currently holds the face.

The server compares its records with the AI workers about once a minute.
`faces[].reregistration_required` is `true` when a worker has lost that face,
and the top-level `reregistration_required` is `true` when any face is marked.
A marked face is blurred until the user re-registers it, so clients show the
mark on the face list and before broadcast preparation. Servers older than this
field omit it; clients treat a missing value as `false`.

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
| 409 | `withdrawal_in_progress` | Preserve local state and wait for the account deletion result |
| 502 | `ai_unavailable` | Preserve the current UI state and offer retry |
