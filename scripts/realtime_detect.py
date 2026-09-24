# -*- coding: utf-8 -*-
"""YOLO26x 摄像头实时目标检测
按 Q 键或关闭窗口退出。
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import cv2
from ultralytics import YOLO

from assistant import config

model = YOLO(str(config.MODELS_DIR / "yolo26x.pt"))  # YOLO26 全量 x 版本

# 打开默认摄像头（0）；若打不开可尝试 1
cap = cv2.VideoCapture(0)
if not cap.isOpened():
    raise RuntimeError("无法打开摄像头，请检查设备是否被占用或权限设置")

import time

print("实时检测已启动，按 Q 键退出...")
prev = time.time()
while True:
    ret, frame = cap.read()
    if not ret:
        break

    # 流式推理（verbose=False 关闭终端刷屏）
    results = model(frame, conf=0.3, verbose=False)  # 降低阈值减少漏检
    annotated = results[0].plot()

    # 计算实时帧率
    now = time.time()
    fps = 1 / (now - prev)
    prev = now

    # 左上角显示检测到的物体名称和帧率
    names = [model.names[int(b.cls[0])] for b in results[0].boxes]
    label = ", ".join(sorted(set(names)))
    cv2.putText(annotated, label, (10, 30),
                cv2.FONT_HERSHEY_SIMPLEX, 0.8, (0, 255, 0), 2)
    cv2.putText(annotated, f"FPS: {fps:.1f}", (10, 60),
                cv2.FONT_HERSHEY_SIMPLEX, 0.7, (0, 255, 255), 2)

    cv2.imshow("YOLO26x Realtime - Press Q to quit", annotated)
    if cv2.waitKey(1) & 0xFF == ord("q"):
        break

cap.release()
cv2.destroyAllWindows()
