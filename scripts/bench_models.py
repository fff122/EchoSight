# -*- coding: utf-8 -*-
"""实测 yolo26 各型号在不同分辨率下的 CPU 推理速度"""
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import cv2
from ultralytics import YOLO

from assistant import config

img = cv2.imread(str(config.ASSETS_DIR / "bus.jpg"))

for size in (640, 416, 320):
    print(f"\n--- imgsz={size} ---")
    for v in ("n", "s", "m", "x"):
        m = YOLO(str(config.MODELS_DIR / f"yolo26{v}.pt"))
        m(img, imgsz=size, verbose=False)  # 预热
        ts = []
        for _ in range(8):
            t0 = time.time()
            m(img, imgsz=size, verbose=False)
            ts.append((time.time() - t0) * 1000)
        avg = sum(ts) / len(ts)
        print(f"  yolo26{v}: {avg:6.0f} ms  => {1000/avg:5.1f} FPS")
