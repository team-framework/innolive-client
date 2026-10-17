# Plan and usage v1

The iOS and Android clients read the existing authenticated `GET /users/me/plan` and
`GET /users/me/usage` endpoints. Requests use the account Bearer token, without
a session owner token. Existing authentication refresh and account-generation
checks apply. Signaling is unchanged.

## Processing-specific budgets (2026-10-15)

Source: [server BM specification](https://app.notion.com/p/3faba097c65980fbaa2de8f8fa55e015).
This consumes additive response fields. Session creation also sends the existing `broadcast_resolution` field matching the selected capture preset. On iOS, a server session that receives `plan_resolution_not_allowed` for `fhd` retries once with `720p` ([AI processing](ai-processing-v1.md#ios-연결을-유지하는-전환)).

| Response | Server fields | On-device fields |
| --- | --- | --- |
| `/users/me/plan` | `monthly_broadcast_seconds`, `allowed_modes` | `monthly_device_seconds`, `device_modes` |
| `/users/me/usage` | `limit_seconds`, `used_seconds`, `remaining_seconds`, `available_by_mode` | `device_limit_seconds`, `device_used_seconds`, `device_remaining_seconds` |

`monthly_broadcast_seconds: 0` and empty `allowed_modes` mean **server streaming
is unavailable**, not unlimited. Spark and Glow usage returns null server limit
and remaining values. Do not display these as an unlimited server entitlement.
`max_per_broadcast_seconds` is the per-broadcast real-time cap for both locations.

| Plan | Server unit-hours | Device unit-hours | Per-broadcast hours | Device modes |
| --- | --- | --- | --- | --- |
| Spark | 0 | 5 | 1 | 720p single |
| Glow | 0 | 60 | 8 | 720p single, FHD single |
| Beam | 40 | 120 | 12 | 720p single, FHD single, 720p multi |
| Plasma | 80 | 300 | 12 | All four modes |

Beam and Plasma server modes match their device modes. Both locations charge
unit-seconds with multipliers `720p_single=1`, `fhd_single=2`, `720p_multi=2`,
`fhd_multi=3`. Server availability uses each `available_by_mode` entry's
`allowed`, `seconds`, and `multiplier`, plus `allowed_modes` membership.
Device availability uses `device_modes` membership and integer division of
`device_remaining_seconds` by the mode multiplier. The two balances must never
be combined or substituted for each other. Unknown modes do not add UI rows.

`broadcasts[].ai_processing` identifies the ledger location (`server` or
`on_device`). iOS renders history with month, start date, resolution, providers, actual broadcast seconds and charged seconds. Existing historical fixtures remain for backward-compatible decoding;
`plan-usage-processing.v1.json` covers all four current plans with exhausted
server budgets and independently available device budgets.

iOS tolerates missing additive device fields in older responses, displays
unknown values as pending, and delegates unknown-budget validation to the server.
Explicit null device limit/remaining values mean unlimited; absent keys remain unknown. Negative device values fail
decoding. Plan and usage are published together only when `plan` agrees; a failed
refresh preserves the last complete snapshot and offers retry. Account reset
rejects stale responses.

Billing follows the session's wire `ai_processing`, not a local inference
override on a server-egress session. Before session creation, use the selected
processing mode. Server-less plans and known exhausted/disallowed modes block
preparation, while a live broadcast is not stopped locally when a budget reaches
zero. The server enforces Spark's immediate monthly stop and all per-broadcast
stops. Server exhaustion must not block on-device preparation, and vice versa.

Errors: `plan_server_streaming_not_allowed` and server `monthly_limit_exhausted` offer an on-device preparation button. It deletes the prior session first, blocks on cleanup failure, discards account-reset responses, then invokes the ordinary consent/save/prepare flow without go-live; `plan_resolution_not_allowed` and
`plan_simulcast_not_allowed` explain mode restrictions; `monthly_limit_exhausted`
explains the current location's budget; `broadcast_limit_reached` requires a new
session. Existing HTTP statuses and error envelopes are unchanged.

While broadcasting, use session polling (server) or heartbeat (on-device)
`broadcast_remaining_seconds`,
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

The remaining value is the minimum of the current location's monthly balance
(divided by the active multiplier) and the per-broadcast balance. Notices
`monthly_usage_80`, `monthly_usage_100`, `monthly_limit_reached`,
`broadcast_limit_30m`, `broadcast_limit_10m`, `broadcast_limit_reached`, and
`no_input_stopped` share localized iOS messages. A heartbeat `stop_targets` list
remains the authority for stopping direct publishers; notices alone do not stop
paid-plan broadcasts.

Before preparation, each mode displays the minimum of monthly availability and `usage.max_per_broadcast_seconds` (explicit null means no cap). Older responses missing that key fall back to the plan cap. Server monthly availability uses API seconds directly; on-device uses integer division.
