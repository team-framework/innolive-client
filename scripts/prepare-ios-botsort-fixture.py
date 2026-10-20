#!/usr/bin/env python3
"""Generate deterministic tracker/Kalman parity evidence using server-pinned Ultralytics.

Run in an isolated environment with ultralytics==8.4.104 and lap>=0.5.12.
No model weights, user images, or GPU are required. GMC is supplied explicitly so
this fixture tests assignment, track lifecycle and Kalman math independently of
OpenCV's platform-dependent feature extraction/RANSAC.
"""
import json
from pathlib import Path
from types import SimpleNamespace

import numpy as np
import ultralytics
from ultralytics.engine.results import Boxes
from ultralytics.trackers.bot_sort import BOTSORT
from ultralytics.trackers.utils.stracks import multi_gmc

assert ultralytics.__version__ == "8.4.104", ultralytics.__version__


class Tracker(BOTSORT):
    warp = np.eye(2, 3)

    def _pre_first_associate(self, pool, unconfirmed, image, results):
        multi_gmc(pool, self.warp)
        multi_gmc(unconfirmed, self.warp)


args = SimpleNamespace(track_high_thresh=0.25, track_low_thresh=0.05,
                       new_track_thresh=0.25, track_buffer=30, match_thresh=0.8,
                       fuse_score=True, gmc_method="none", with_reid=False,
                       proximity_thresh=0.5, appearance_thresh=0.8, model="auto")
tracker = Tracker(args)
frames = []
for frame in range(24):
    pan = 100 if frame >= 12 else 0
    detections = []
    if frame not in (7, 8, 17):
        score = 0.1 if frame in (4, 5, 16) else 0.9
        x = 50 + frame * 3 + pan
        detections.append([x, 100, x + 80, 180, score, 0])
    if frame >= 2 and frame not in (10, 11):
        x = 400 - frame * 2 + pan
        detections.append([x, 300, x + 60, 340, 0.85, 1])
    # Background low-score detections must never create identities.
    detections.append([10, 500, 40, 530, 0.08, 0])
    if frame % 2:
        detections.reverse()
    tracker.warp = np.array([[1, 0, 100 if frame == 12 else 0], [0, 1, 0]], dtype=float)
    rows = tracker.update(Boxes(np.array(detections, dtype=np.float32), (640, 640)))
    frames.append({"detections": detections, "motion": tracker.warp.flatten().tolist(),
                   "expected": rows.tolist()})

# Exercise the exact buffer boundary: a high-score detection may re-activate
# a lost track on the frame where unmatched tracks would expire.
last_detection = [d for d in frames[-1]["detections"] if d[5] == 0 and d[4] > 0.25][0]
for index in range(31):
    detections = [last_detection] if index == 30 else []
    tracker.warp = np.eye(2, 3)
    rows = tracker.update(Boxes(np.array(detections, dtype=np.float32).reshape(-1, 6), (640, 640)))
    frames.append({"detections": detections, "motion": tracker.warp.flatten().tolist(),
                   "expected": rows.tolist()})

payload = {"ultralytics": ultralytics.__version__, "server_commit": "3fca099",
           "description": "XYWH/assignment parity with explicit GMC; confirmed tracks only",
           "frames": frames}
destination = Path(__file__).resolve().parents[1] / "apps/ios/InnoLive/InnoLiveTests/Fixtures/privacy-botsort-reference.json"
destination.write_text(json.dumps(payload, indent=2) + "\n")
print(f"Wrote {len(frames)} frames to {destination}")
