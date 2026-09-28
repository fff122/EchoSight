# -*- coding: utf-8 -*-
"""YOLOE 视觉提示链路可行性验证（决赛"实时跟具体物品"的电脑端原型）：

  裁一块目标区域（模拟 Qwen 确认后的 crop）
    → get_visual_pe 编码成向量
    → set_classes 注册成"我的公交车"
    → save 烧死 → 检测验证：模型认出了"这一个"

全程零训练、零文字描述——看一张照片就学会。
通过 = 最后一步检出公交车；同时验证 ONNX 可导出（端侧部署的前提）。

运行：buildenv/Scripts/python.exe scripts/test_yoloe_visual.py
"""
import sys
from pathlib import Path

import cv2
import numpy as np
from ultralytics import YOLOE

ROOT = Path(__file__).resolve().parent.parent
IMG_PATH = str(ROOT / "assets/bus.jpg")
BAKED = ROOT / "models/yoloe-visual-test.pt"

img = cv2.imread(IMG_PATH)
h, w = img.shape[:2]

# bus.jpg 中公交车的框（test_vlm_prompt.py 实测输出的 0~1000 归一化坐标）
box1000 = [6.0, 211.0, 993.0, 683.0]
box01 = [v / 1000.0 for v in box1000]

print("1) 加载 YOLOE-11s-seg（首次会下载权重）...")
model = YOLOE("yoloe-11s-seg.pt")

print("2) 计算视觉提示向量（裁块 → 编码）...")
pe = None
for label, visual in [("归一化坐标", [box01]),
                      ("像素坐标", [box1000]),
                      ("bboxes 字典", {"bboxes": np.array([box01]),
                                       "cls": np.array([0])})]:
    try:
        pe = model.get_visual_pe(IMG_PATH, visual)
        print(f"   get_visual_pe 成功（{label}），向量维度:",
              getattr(pe, "shape", None) or np.asarray(pe).shape)
        break
    except Exception as ex:
        print(f"   {label} 格式不行：{type(ex).__name__}: {str(ex)[:80]}")
if pe is None:
    print("FAIL：所有视觉提示格式都失败")
    sys.exit(1)

print("3) 注册为'公交车'并烧死保存...")
model.set_classes(["公交车"], pe)
model.save(str(BAKED))

print("4) 重新加载并检测同一张图...")
baked = YOLOE(str(BAKED))
r = baked.predict(IMG_PATH, conf=0.25, verbose=False)[0]
dets = [(baked.names[int(b.cls[0])], round(float(b.conf), 2))
        for b in r.boxes]
print("   检出:", dets)
hit = [d for d in dets if d[0] == "公交车" and d[1] >= 0.5]
if not hit:
    print("FAIL：没有检出'公交车'")
    sys.exit(1)

print("5) 导出 ONNX（端侧部署前提）...")
onnx = baked.export(format="onnx", imgsz=320, simplify=True)
p = Path(onnx)
print(f"   导出成功：{p.name}，{p.stat().st_size / 1e6:.1f} MB")

print()
print(f"结果：PASS —— YOLOE 视觉提示链路成立（{hit[0][0]} conf={hit[0][1]}），"
      f"端侧部署可行")
