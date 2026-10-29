import assert from "node:assert/strict";
import test from "node:test";
import {
  isInnoliveVideoEnabled,
  isVodMockEnabled,
  parseBooleanFlag,
} from "./features.ts";

test("플래그 파싱은 1/true만 허용", () => {
  assert.equal(parseBooleanFlag("1"), true);
  assert.equal(parseBooleanFlag("true"), true);
  assert.equal(parseBooleanFlag("TRUE"), true);
  assert.equal(parseBooleanFlag(" true "), true);
  assert.equal(parseBooleanFlag("0"), false);
  assert.equal(parseBooleanFlag("yes"), false);
  assert.equal(parseBooleanFlag(""), false);
  assert.equal(parseBooleanFlag(undefined), false);
  assert.equal(parseBooleanFlag(null), false);
});

test("비디오 플래그는 env를 반영", () => {
  const previous = process.env.NEXT_PUBLIC_INNOLIVE_VIDEO_ENABLED;
  process.env.NEXT_PUBLIC_INNOLIVE_VIDEO_ENABLED = "1";
  assert.equal(isInnoliveVideoEnabled(), true);
  process.env.NEXT_PUBLIC_INNOLIVE_VIDEO_ENABLED = "0";
  assert.equal(isInnoliveVideoEnabled(), false);
  delete process.env.NEXT_PUBLIC_INNOLIVE_VIDEO_ENABLED;
  assert.equal(isInnoliveVideoEnabled(), false);
  if (previous !== undefined) process.env.NEXT_PUBLIC_INNOLIVE_VIDEO_ENABLED = previous;
});

test("목업은 production에서 플래그와 무관하게 비활성", () => {
  // @types/node types NODE_ENV as read-only; this view lets the test flip it.
  const env = process.env as Record<string, string | undefined>;
  const prevNode = env.NODE_ENV;
  const prevVideo = process.env.NEXT_PUBLIC_INNOLIVE_VIDEO_ENABLED;
  const prevMock = process.env.NEXT_PUBLIC_VOD_MOCK;
  process.env.NEXT_PUBLIC_INNOLIVE_VIDEO_ENABLED = "1";
  process.env.NEXT_PUBLIC_VOD_MOCK = "1";

  env.NODE_ENV = "development";
  assert.equal(isVodMockEnabled(), true);
  env.NODE_ENV = "production";
  assert.equal(isVodMockEnabled(), false);

  // Mock also requires the video feature flag.
  env.NODE_ENV = "development";
  process.env.NEXT_PUBLIC_INNOLIVE_VIDEO_ENABLED = "0";
  assert.equal(isVodMockEnabled(), false);

  if (prevNode === undefined) delete env.NODE_ENV;
  else env.NODE_ENV = prevNode;
  if (prevVideo === undefined) delete process.env.NEXT_PUBLIC_INNOLIVE_VIDEO_ENABLED;
  else process.env.NEXT_PUBLIC_INNOLIVE_VIDEO_ENABLED = prevVideo;
  if (prevMock === undefined) delete process.env.NEXT_PUBLIC_VOD_MOCK;
  else process.env.NEXT_PUBLIC_VOD_MOCK = prevMock;
});
