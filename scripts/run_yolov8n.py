# -*- coding: utf-8 -*-
"""运行 YOLOv8n 模型：对示例图片进行目标检测"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from ultralytics import YOLO

from assistant import config

# 1. 加载本地模型
model = YOLO(str(config.MODELS_DIR / "yolov8n.pt"))

# 2. 使用示例图片进行预测
results = model(
    source=str(config.ASSETS_DIR / "bus.jpg"),
    save=True,        # 保存标注后的结果图
    conf=0.25,        # 置信度阈值
)

# 3. 打印检测结果
for r in results:
    print(f"\n检测到 {len(r.boxes)} 个目标：")
    for box in r.boxes:
        cls_id = int(box.cls[0])
        conf = float(box.conf[0])
        name = model.names[cls_id]
        print(f"  - {name}: {conf:.2f}")
    print(f"\n结果图已保存至: {r.save_dir}")
