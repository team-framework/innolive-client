# WebRTC config capability compatibility

GET /webrtc/config retains iceServers and recovery. The additive optional object
features may include send_only_video as a Boolean. Only explicit true authorizes
an on-device client to offer sendonly video and select its verified hardware H.264
path. Missing, null, false, or malformed values keep legacy sendrecv/VP8 behavior.
Server-AI clients keep their existing sendrecv policy.

The server answers sendonly video with recvonly and retains ingress privacy
processing, egress fanout and RTCP feedback. No local protection policy is inferred
from SDP direction. The existing ai_processing=on_device direct RTMP session is a
separate contract and is not enabled by this feature.

A client reads capability before its initial offer and keeps direction/codec fixed
through ICE restart. A changed feature requires a new connection. Old config
responses and signaling messages remain valid; old clients may ignore features.

Examples: ../fixtures/webrtc-config-legacy.v1.json and
../fixtures/webrtc-config-send-only.v1.json. Synthetic fixtures contain no ICE
credentials or production addresses.

Schema: webrtc-config-v1.schema.json. Both capability-free and capability-enabled
fixtures are valid; send_only_video as a string is invalid and ignored by Android.
