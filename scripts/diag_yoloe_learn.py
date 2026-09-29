# -*- coding: utf-8 -*-
"""学习后不识别的对照诊断：

  Exp A：摄像头帧（正中绘制高对比测试图案）+ 局部框，原生 640x480
  Exp B：同一帧放大 2 倍（1280x960）再学再测        —— 隔离分辨率
  Exp C：bus.jpg 中心 40% 局部框                    —— 隔离内容
  Exp D：bus.jpg 全图框                             —— 已知 PASS 的对照

  每组学习后，用 conf=0.01 在同一帧上自检，打印全部检出与最高分。
  运行：buildenv/Scripts/python.exe scripts/diag_yoloe_learn.py
"""
import cv2
import numpy as np
from pathlib import Path
from ultralytics import YOLOE
from ultralytics.models.yolo.yoloe import YOLOEVPDetectPredictor

ROOT = Path(__file__).resolve().parent.parent


def draw_pattern(frame, box):
    x1, y1, x2, y2 = [int(v) for v in box]
    cv2.rectangle(frame, (x1, y1), (x2, y2), (60, 60, 60), -1)
    ccx, ccy = (x1 + x2) // 2, (y1 + y2) // 2
    r0 = min(x2 - x1, y2 - y1) // 4
    cv2.circle(frame, (ccx, ccy), r0, (0, 220, 255), -1)
    cv2.rectangle(frame, (ccx - r0, ccy - r0), (ccx, ccy), (255, 120, 0), -1)


def run_exp(tag, img, box):
    # 关键：每次实验用全新模型实例（set_classes 会重参数化，复用实例会污染）
    model = YOLOE("yoloe-11s-seg.pt")
    predictor = YOLOEVPDetectPredictor(overrides={
        "model": "yoloe-11s-seg.pt", "mode": "predict",
        "conf": 0.25, "verbose": False})
    predictor.setup_model(model=model.model)
    tmp = ROOT / "_diag_tmp.jpg"
    cv2.imwrite(str(tmp), img)
    predictor.set_prompts({"bboxes": np.array([box]), "cls": np.array([0])})
    pe = predictor.get_vpe(str(tmp))
    name = f"实验{tag}目标"
    model.set_classes([name], pe)
    model.save(str(ROOT / "models/_diag_baked.pt"))
    baked = YOLOE(str(ROOT / "models/_diag_baked.pt"))
    r = baked.predict(img, conf=0.01, verbose=False)[0]
    dets = [(round(float(b.conf), 3), [round(float(v)) for v in b.xyxy[0]])
            for b in r.boxes]
    dets.sort(reverse=True)
    top = dets[0] if dets else None
    print(f"  {tag}: 检出 {len(dets)} 个, 最高分 {top}  (pe范数 {float(pe.norm()):.1f})")
    return top








# 摄像头帧
cap = cv2.VideoCapture(0)
ret, cam = cap.read()
cap.release()
if not ret:
    print("摄像头读不到画面")
    sys.exit(1)
print(f"摄像头帧: {cam.shape[1]}x{cam.shape[0]}, 亮度 {cam.mean():.1f}")
cv2.imwrite(str(ROOT / "assets/_diag_cam.jpg"), cam)

h, w = cam.shape[:2]
box_cam = [w * 0.3, h * 0.3, w * 0.7, h * 0.7]
draw_pattern(cam, box_cam)
cv2.imwrite(str(ROOT / "assets/_diag_cam_pattern.jpg"), cam)

print("Exp A：摄像头帧（带测试图案）原生分辨率")
run_exp("A", cam, box_cam)

print("Exp B：同一帧放大 2 倍")
cam2 = cv2.resize(cam, (w * 2, h * 2))
box2 = [v * 2 for v in box_cam]
run_exp("B", cam2, box2)

print("Exp C：bus.jpg 中心 40% 局部框（真实内容）")
bus = cv2.imread(str(ROOT / "assets/bus.jpg"))
bh, bw = bus.shape[:2]
box_bus = [bw * 0.3, bh * 0.3, bw * 0.7, bh * 0.7]
draw_pattern(bus, box_bus)
run_exp("C", bus, box_bus)

print("Exp D：bus.jpg 全图框（已知 PASS 对照）")
run_exp("D", bus, [0, 0, w - 1, h - 1])
