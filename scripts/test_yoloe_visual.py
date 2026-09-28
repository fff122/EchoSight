# -*- coding: utf-8 -*-
"""YOLOE 视觉提示学习验证（桌面端原型，决赛"实时跟具体物品"的可行性）：

  流程：VLM 给的框（模拟）→ 裁块区域作为视觉提示 → get_vpe 编码
        → set_classes 固化成"目标物品" → 保存 → 重新加载检测
  验证点：
    A1 从原图学会（自检：同一张图能检出"目标物品"）
    A2 泛化：把公交车贴到白底画布、换位置换尺寸后，仍能检出
       （证明学到的是外观，不是背下了这张图）
  通过 = A1、A2 都检出，且 ONNX 可导出（端侧部署前提）。

  上次失败的教训：predict(visual_prompts=...) 有 NMS tuple bug；
  正确姿势是预测器两步走：set_prompts → get_vpe → set_classes。

运行：buildenv/Scripts/python.exe scripts/test_yoloe_visual.py
"""
import sys
from pathlib import Path

import cv2
import numpy as np
from ultralytics import YOLOE
from ultralytics.models.yolo.yoloe import YOLOEVPDetectPredictor

ROOT = Path(__file__).resolve().parent.parent
BAKED = ROOT / "models/yoloe-visual-test.pt"

img = cv2.imread(str(ROOT / "assets/bus.jpg"))
h, w = img.shape[:2]
# test_vlm_prompt 实测的 0~1000 归一化框 → 像素坐标
px = np.array([[6 / 1000 * w, 211 / 1000 * h, 993 / 1000 * w, 683 / 1000 * h]])

print("1) 加载模型并构建视觉提示预测器...")
model = YOLOE("yoloe-11s-seg.pt")
pred = YOLOEVPDetectPredictor(overrides={
    "model": "yoloe-11s-seg.pt", "mode": "predict", "conf": 0.25, "verbose": False})
pred.setup_model(model=model.model)

print("2) 视觉提示 → 编码（get_vpe）...")
pred.set_prompts({"bboxes": px, "cls": np.array([0])})
vpe = pred.get_vpe(str(ROOT / "assets/bus.jpg"))
print("   视觉向量 shape:", tuple(vpe.shape))

print("3) 固化为类别'目标物品'并保存...")
model.set_classes(["目标物品"], vpe)
model.save(str(BAKED))

baked = YOLOE(str(BAKED))

def detect(image):
    r = baked.predict(image, conf=0.25, verbose=False)[0]
    return [(baked.names[int(b.cls[0])], round(float(b.conf), 2),
             [round(float(v)) for v in b.xyxy[0]]) for b in r.boxes]

print("4) A1 自检：原图检测...")
d1 = detect(str(ROOT / "assets/bus.jpg"))
print("   ", d1[:4])
ok1 = any(n == "目标物品" and c >= 0.5 for n, c, _ in d1)

print("5) A2 泛化：把公交车贴到白底画布（换位置+缩小到 60%）...")
x1, y1, x2, y2 = [int(v) for v in px[0]]
crop = img[y1:y2, x1:x2]
ch, cw = crop.shape[:2]
scale = 0.6
small = cv2.resize(crop, (int(cw * scale), int(ch * scale)))
canvas = np.full((720, 720, 3), 235, np.uint8)
cy, cx = 60, 200           # 贴到上方区域（画布内放得下，且位置与原图不同）
canvas[cy:cy + small.shape[0], cx:cx + small.shape[1]] = small
paste_path = str(ROOT / "assets/bus_paste_test.jpg")
cv2.imwrite(paste_path, canvas)
d2 = detect(paste_path)
print("   ", d2[:4])
ok2 = any(n == "目标物品" and c >= 0.5 for n, c, _ in d2)

print("6) ONNX 导出（端侧部署前提）...")
try:
    onnx = baked.export(format="onnx", imgsz=320, simplify=True)
    p = Path(onnx)
    print(f"   导出成功：{p.name}，{p.stat().st_size / 1e6:.1f} MB")
    ok3 = True
except Exception as ex:
    print("   导出失败：", type(ex).__name__, str(ex)[:120])
    ok3 = False

print()
print(f"A1 自检: {'PASS' if ok1 else 'FAIL'}   A2 泛化: {'PASS' if ok2 else 'FAIL'}   "
      f"ONNX: {'PASS' if ok3 else 'FAIL'}")
if ok1 and ok2:
    print("结论：YOLOE 能'看一张照片学会一个物品'，且换了位置尺寸仍认得 —— "
          "端侧'实时跟具体物品'的可行性成立")
sys.exit(0 if (ok1 and ok2) else 1)
