package com.framework.innolive.feature.live
import org.json.JSONObject
import org.webrtc.RtpTransceiver

/** Absent or malformed capability keeps compatibility with legacy return-only servers. */
internal fun parseSendOnlyVideoSupport(payload: JSONObject): Boolean =
    payload.optJSONObject("features")?.opt("send_only_video") == true

internal fun videoDirectionForAI(onDevice: Boolean, sendOnlySupported: Boolean) =
    if (onDevice && sendOnlySupported) RtpTransceiver.RtpTransceiverDirection.SEND_ONLY
    else RtpTransceiver.RtpTransceiverDirection.SEND_RECV

/** Release authentication uses HTTPS; the debug manifest already permits IPv4 loopback HTTP. */
internal fun isAllowedWebRtcServer(scheme: String, host: String, debug: Boolean): Boolean =
    scheme == "https" || (debug && scheme == "http" && host == "127.0.0.1")
