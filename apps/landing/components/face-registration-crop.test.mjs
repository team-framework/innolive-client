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

test('가로·세로·정사각형 영상의 등록 영역이 Figma 가이드를 왜곡 없이 포함한다', () => {
  for (const [videoWidth, videoHeight] of [[1280, 720], [720, 1280], [1024, 1024]]) {
    let drawn;
    const video = { videoWidth, videoHeight };
    const canvas = { getContext: () => ({ drawImage: (...args) => { drawn = args; } }) };
    drawVisibleCrop(video, canvas, 'low resolution', 'no canvas');
    const [, x, y, width, height, dx, dy, dw, dh] = drawn;
    assert.equal(width, height);
    assert.ok(x >= 0 && y >= 0 && x + width <= videoWidth && y + height <= videoHeight);
    assert.deepEqual([canvas.width, canvas.height, dx, dy, dw, dh], [500, 500, 0, 0, 500, 500]);
    const scale = Math.max(405 / videoWidth, 720 / videoHeight);
    const screenX = x * scale - (videoWidth * scale - 405) / 2;
    const screenY = y * scale - (videoHeight * scale - 720) / 2;
    assert.ok(Math.abs(screenX - 44.5) < 1e-9);
    assert.ok(Math.abs(screenY - 59) < 1e-9);
    assert.ok(Math.abs(width * scale - 316) < 1e-9);
  }
});

test('낮은 입력 해상도와 없는 canvas context를 거부한다', () => {
  assert.throws(() => drawVisibleCrop({ videoWidth: 640, videoHeight: 480 }, {}, 'low resolution', 'no canvas'), /low resolution/);
  assert.throws(() => drawVisibleCrop({ videoWidth: 1280, videoHeight: 720 }, { getContext: () => null }, 'low resolution', 'no canvas'), /no canvas/);
});
