# Plan and usage v1

The iOS and Android clients read the existing authenticated `GET /users/me/plan` and
`GET /users/me/usage` endpoints. Requests use the account Bearer token, without
a session owner token. Existing authentication refresh and account-generation
checks apply. No request or signaling fields change.

`plan` returns `plan` (an extensible string), `allowed_modes` (string array),
`monthly_broadcast_seconds`, and `max_per_broadcast_seconds`. The two plan
limits are seconds; **0 means unlimited**.

`usage` returns `plan`, `used_seconds` (charged unit-seconds),
`remaining_seconds` (charged unit-seconds or null), and `available_by_mode`.
Each entry contains `mode`, `allowed`, `seconds`, and `multiplier`.
`seconds` is the actual monthly time available for that mode, already divided
by the server. **null means unlimited; 0 means exhausted**. It does not include
the separate per-broadcast cap. Missing required time fields fail decoding;
they must not silently become unlimited. Additional fields are ignored.

Known modes: `720p_single`, `fhd_single`, `720p_multi`, `fhd_multi`.
Reference multipliers: 1, 2, 2, 3. The client uses the response multiplier and
requires both `allowed_modes` membership and the entry's `allowed` value.
Unknown modes are retained by decoding but do not create a selectable UI row.
Missing entries remain unavailable. Plan and usage are published together only
when their `plan` values agree; an error preserves the last complete snapshot.

While broadcasting, use the existing session `broadcast_remaining_seconds`,
not monthly usage or the elapsed clock. Polling remains every 2 seconds; the
server computes its remaining value approximately every 15 seconds. Preserve
missing/null/zero semantics from [session state](broadcast-session-state-v1.md).
Android also checks plan limits before displaying active null as unlimited: a
prepared/inactive null can survive the initial go-live response. Until a numeric
session time arrives, finite plans use the mode preview and per-broadcast cap.
Settings interpolate charged usage each second while live, pause this estimate
when paused, and reconcile with authenticated usage every 15 seconds. This is
display-only; the server remains authoritative for billing and broadcast limits.
A failed poll retains its
last value and marks it stale until the next successful poll.

Compatibility: additive client consumption of already deployed server fields.
No server changes are required. Fixtures cover allowed,
locked, exhausted, and unlimited usage. Authentication, transport and account
reset behavior use each platform's existing API client.
