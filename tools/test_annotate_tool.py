# -*- coding: utf-8 -*-
"""annotate_tool 无头单测（不启动界面）：

  T1 像素↔YOLO 往返转换（含越界钳位、反向拖框）
  T2 save/load 标签往返
  T3 export_dataset 输出结构（train/val 划分、yaml、classes、跳过未标注）

运行：buildenv/Scripts/python.exe tools/test_annotate_tool.py
"""
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from annotate_tool import (px_to_yolo, yolo_to_px, save_label, load_label,
                           export_dataset, labels_dir)  # noqa: E402

fails = []


def check(name, cond, detail=""):
    print(("PASS  " if cond else "FAIL  ") + name + (f"  [{detail}]" if detail else ""))
    if not cond:
        fails.append(name)


print("T1 像素↔YOLO 转换")
line = px_to_yolo(3, 100, 50, 300, 250, 640, 480)
check("标准转换", line == "3 0.312500 0.312500 0.312500 0.416667", line)
back = yolo_to_px(line, 640, 480)
check("往返一致", back is not None and
      abs(back[1] - 100) < 0.01 and abs(back[2] - 50) < 0.01 and
      abs(back[3] - 300) < 0.01 and abs(back[4] - 250) < 0.01, str(back))
line2 = px_to_yolo(0, -10, -20, 700, 500, 640, 480)   # 越界钳位
b2 = yolo_to_px(line2, 640, 480)
check("越界钳到图内", b2 is not None and b2[1] >= -0.01 and b2[2] >= -0.01
      and b2[3] <= 640.01 and b2[4] <= 480.01, str(b2))
line3 = px_to_yolo(1, 300, 250, 100, 50, 640, 480)    # 反向拖框
b3 = yolo_to_px(line3, 640, 480)
check("反向拖框自动校正", b3 is not None and b3[1] == 100 and b3[3] == 300,
      str(b3))
check("坏行返回 None", yolo_to_px("abc", 640, 480) is None and
      yolo_to_px("1 2 3", 100, 100) is None)

print("T2 标签存取")
with tempfile.TemporaryDirectory() as d:
    save_label(d, "img1", ["0 0.5 0.5 0.2 0.2"])
    boxes = load_label(d, "img1", 640, 480)
    check("存取往返", boxes is not None and len(boxes) == 1 and
          boxes[0][0] == 0, str(boxes))
    save_label(d, "img2", [])                          # 空文件=已标完无目标
    check("空文件读回空列表", load_label(d, "img2", 640, 480) == [])
    check("未标注返回 None", load_label(d, "img3", 640, 480) is None)

print("T3 导出数据集")
from PIL import Image  # noqa: E402
with tempfile.TemporaryDirectory() as imgd, \
        tempfile.TemporaryDirectory() as outd:
    for i in range(10):
        p = Path(imgd) / f"pic{i}.jpg"
        Image.new("RGB", (640, 480), (i * 20, 100, 150)).save(p)
        if i < 7:                                      # 7 张已标注，3 张没有
            save_label(imgd, p.stem,
                       [px_to_yolo(i % 2, 10, 20, 300, 200, 640, 480)])
    train_n, val_n, skipped = export_dataset(
        imgd, outd, ["medicine box", "earphones"])
    out = Path(outd)
    total_imgs = len(list((out / "images/train").glob("*"))) + \
        len(list((out / "images/val").glob("*")))
    total_lbls = len(list((out / "labels/train").glob("*.txt"))) + \
        len(list((out / "labels/val").glob("*.txt")))
    check("计数正确", train_n + val_n == 7 and skipped == 3,
          f"train {train_n} val {val_n} skipped {skipped}")
    check("图与标签一一对应", total_imgs == 7 and total_lbls == 7,
          f"img {total_imgs} lbl {total_lbls}")
    yaml = (out / "data.yaml").read_text(encoding="utf-8")
    check("data.yaml 内容", "images/train" in yaml and
          "0: medicine box" in yaml and "1: earphones" in yaml, yaml[:120])
    check("classes.txt 内容",
          (out / "classes.txt").read_text(encoding="utf-8")
          .strip().splitlines() == ["medicine box", "earphones"])
    # 划分确定性
    t2, v2, s2 = export_dataset(imgd, outd, ["medicine box", "earphones"])
    check("重跑划分一致", (t2, v2, s2) == (train_n, val_n, skipped),
          f"{(t2, v2, s2)} vs {(train_n, val_n, skipped)}")

print()
if fails:
    print(f"结果：{len(fails)} 项未通过 -> {fails}")
    sys.exit(1)
print("结果：全部通过 —— 标注工具核心逻辑可用")
