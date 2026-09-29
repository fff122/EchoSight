# -*- coding: utf-8 -*-
"""学习工作进程（由 yoloe_learn_demo.py 按子进程方式调用）。

为什么单独一个进程：实测同一进程里连续多次 get_vpe + set_classes 会互相污染
（第 4 次学习最高分从 0.95 掉到 0.077，连全新模型实例都躲不开），
而每次全新进程的单次学习稳定 0.95+。所以每次按 S 都用干净进程重烘焙。

职责：编码新裁块 → 与已学向量库合并 → set_classes → 保存烘焙模型 + 向量库。
"""
import argparse
from pathlib import Path

import cv2
import numpy as np
import torch
from ultralytics import YOLOE
from ultralytics.models.yolo.yoloe import YOLOEVPDetectPredictor

ROOT = Path(__file__).resolve().parent.parent


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--frame", required=True, help="学习帧图片路径")
    ap.add_argument("--box", required=True, help="x1,y1,x2,y2 像素坐标")
    ap.add_argument("--name", required=True)
    ap.add_argument("--bank", required=True, help="向量库 .pt 路径")
    ap.add_argument("--baked", required=True, help="输出的烘焙模型 .pt 路径")
    args = ap.parse_args()

    box = [float(v) for v in args.box.split(",")]

    print("[worker] 加载 YOLOE ...")
    model = YOLOE("yoloe-11s-seg.pt")
    predictor = YOLOEVPDetectPredictor(overrides={
        "model": "yoloe-11s-seg.pt", "mode": "predict",
        "conf": 0.25, "verbose": False})
    predictor.setup_model(model=model.model)

    print("[worker] 编码新物品 ...")
    predictor.set_prompts({"bboxes": np.array([box]), "cls": np.array([0])})
    pe = predictor.get_vpe(args.frame)                     # (1, 1, 512)

    if Path(args.bank).exists():
        bank = torch.load(args.bank, map_location="cpu", weights_only=False)
        names = list(bank["names"])
        pes = torch.cat([bank["pes"], pe], dim=1)          # (1, N+1, 512)
        if args.name in names:                             # 同名重学：覆盖
            idx = names.index(args.name)
            pes[0, idx] = pe[0, 0]
            pes = pes[:, : len(names)]
        else:
            names.append(args.name)
    else:
        names = [args.name]
        pes = pe

    print(f"[worker] 注册 {len(names)} 个类别并烘焙 ...")
    model.set_classes(names, pes)
    model.save(args.baked)
    torch.save({"names": names, "pes": pes}, args.bank)
    print("[worker] OK，类别：", names)


if __name__ == "__main__":
    main()
