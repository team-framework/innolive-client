import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';
import { transpileModule } from 'typescript';

// Exercise the production crop without opening a camera or uploading an image.
const source = readFileSync(new URL("./face-registration-modal.tsx", import.meta.url), 'utf8');
const crop = source.slice(source.indexOf('function drawVisibleCrop('), source.indexOf('function toJPEG('));
const drawVisibleCrop = runInNewContext(`${transpileModule(crop, {}).outputText}\ndrawVisibleCrop`, {
  cropSideLength: 500,
  FaceRegistrationError: Error,
});

test('영상 비율이 달라도 인식 크롭은 새 가이드보다 10% 큰 영역을 포함한다', () => {
  const recognitionWidth = 309 * 1.1;
  const recognitionHeight = 342 * 1.1;
  const halfWidth = recognitionWidth / 2;
  const halfHeight = recognitionHeight / 2;
  const left = 202.5 - halfWidth;
  const right = 202.5 + halfWidth;
  const top = 326 - halfHeight;
  const bottom = 326 + halfHeight;
  for (const [videoWidth, videoHeight] of [[1280, 720], [720, 1280], [720, 720]]) {
    for (const [previewWidth, previewHeight] of [[405, 720], [405, 584], [340, 460]]) {
      let drawn;
      const video = { videoWidth, videoHeight, getBoundingClientRect: () => ({ width: previewWidth, height: previewHeight }) };
      const canvas = { getContext: () => ({ drawImage: (...args) => { drawn = args; } }) };
      drawVisibleCrop(video, canvas, 'low resolution', 'no canvas');
      const [, x, y, width, height, dx, dy, dw, dh] = drawn;
      assert.equal(width, height);
      assert.ok(x >= 0 && y >= 0 && x + width <= videoWidth && y + height <= videoHeight);
      assert.deepEqual([canvas.width, canvas.height, dx, dy, dw, dh], [500, 500, 0, 0, 500, 500]);
      const scale = Math.max(previewWidth / videoWidth, previewHeight / videoHeight);
      const screenX = x * scale - (videoWidth * scale - previewWidth) / 2;
      const screenY = y * scale - (videoHeight * scale - previewHeight) / 2;
      const epsilon = 1e-9;
      const expectedSide = Math.max(
        previewWidth * recognitionWidth / 405,
        previewHeight * recognitionHeight / 720,
      );
      assert.ok(Math.abs(width * scale - expectedSide) <= epsilon);
      assert.ok(screenX <= previewWidth * left / 405 + epsilon);
      assert.ok(screenY <= previewHeight * top / 720 + epsilon);
      assert.ok(screenX + width * scale >= previewWidth * right / 405 - epsilon);
      assert.ok(screenY + height * scale >= previewHeight * bottom / 720 - epsilon);
    }
  }
});

test('영상을 표시하지 않는 레이아웃에서는 크롭을 거부한다', () => {
  const video = { videoWidth: 720, videoHeight: 720, getBoundingClientRect: () => ({ width: 0, height: 0 }) };
  assert.throws(() => drawVisibleCrop(video, { getContext: () => ({}) }, 'low resolution', 'no canvas'), /no canvas/);
});

test('낮은 입력 해상도와 없는 canvas context를 거부한다', () => {
  assert.throws(() => drawVisibleCrop({ videoWidth: 640, videoHeight: 480 }, {}, 'low resolution', 'no canvas'), /low resolution/);
  assert.throws(() => drawVisibleCrop({ videoWidth: 1280, videoHeight: 720 }, { getContext: () => null }, 'low resolution', 'no canvas'), /no canvas/);
});
