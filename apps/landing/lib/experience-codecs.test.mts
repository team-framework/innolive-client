import assert from "node:assert/strict";
import { test } from "node:test";
import { preferExperienceVideoCodec } from "./experience-codecs.ts";

test("VP8 precedes H264 while fallback and repair codecs remain available", () => {
  const codecs = [
    { mimeType: "video/H264", clockRate: 90000 },
    { mimeType: "video/rtx", clockRate: 90000 },
    { mimeType: "video/VP8", clockRate: 90000 },
    { mimeType: "video/red", clockRate: 90000 },
  ];
  let selected: unknown;
  const pc = { getTransceivers: () => [
    { sender: { track: { kind: "video" } }, setCodecPreferences: (value: unknown) => { selected = value; } },
    { sender: { track: { kind: "audio" } }, setCodecPreferences: () => assert.fail("audio codec order changed") },
  ] };
  preferExperienceVideoCodec(pc as unknown as RTCPeerConnection, codecs);
  assert.deepEqual(selected, [codecs[2], codecs[0], codecs[1], codecs[3]]);
  assert.equal(codecs[0].mimeType, "video/H264");
});

test("missing capabilities or VP8 leaves the browser defaults unchanged", () => {
  const pc = { getTransceivers: () => assert.fail("codec preferences changed without VP8") };
  preferExperienceVideoCodec(pc as unknown as RTCPeerConnection, undefined);
  preferExperienceVideoCodec(pc as unknown as RTCPeerConnection, [{ mimeType: "video/H264", clockRate: 90000 }]);
});

test("browsers without setCodecPreferences keep their default negotiation", () => {
  const pc = { getTransceivers: () => [{ sender: { track: { kind: "video" } } }] };
  assert.doesNotThrow(() => preferExperienceVideoCodec(pc as unknown as RTCPeerConnection, [{ mimeType: "video/VP8", clockRate: 90000 }]));
});
