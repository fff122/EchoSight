# -*- coding: utf-8 -*-
"""YOLO-World 开放词汇接入：COCO 80 + 家居扩展词表 → 烧词表 → 导 ONNX → 端上离线寻物。

为什么不是 yolov8n-world：Ultralytics 没有 n 版 YOLO-World（最小 s 版），
旧脚本下载 yolov8n-worldv2.pt 失败，根目录只留下 9 字节残件。本脚本改用
yolov8s-worldv2（s 版），并把 COCO 80 类 + 40 个常用家居物品烧进词表。

流程：
  1) 下载 yolov8s-worldv2，用 bus.jpg 验证开放词汇（零样本检出 bus/person）
  2) COCO 80 + EXT 扩展词表 set_classes → save 烧死（推理不再需要文本编码器）
  3) 导出 ONNX（320，替换安卓端 yolo26n.onnx），onnxruntime 实测形状/速度/检出
  4) 生成 world_vocab.json 与 Labels 同步用的 Kotlin/Python 片段

用法（项目根目录）：
  buildenv/Scripts/python.exe scripts/make_world_onnx.py

换词表：改 EXT 后重跑本脚本，再把新 onnx 拷进 android assets 重新打包即可。
"""
import json
import sys
import time
from pathlib import Path

import cv2
import numpy as np
import onnxruntime as ort
from ultralytics import YOLO

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
from assistant.labels import CLASS_CN as COCO_CN  # noqa: E402

BUS = str(ROOT / "assets/bus.jpg")
WORLD_PT = "yolov8s-worldv2.pt"          # Ultralytics 官方最小版本就是 s
BAKED_PT = ROOT / "models/yolov8s-world-vocab.pt"
ONNX_OUT = ROOT / "models/yolov8s-world-vocab-320.onnx"

# ---- 家居扩展词表：(英文提示词, 中文名, 真实高度米, 中文口语别名) ----
# 英文提示词要具体（YOLO-World 文本编码器按英文理解）；中文仅用于播报与指令匹配。
EXT = [
    ("earphones",       "耳机",   0.06, ["耳机", "蓝牙耳机", "耳麦"]),
    ("medicine box",    "药盒",   0.08, ["药盒", "药箱"]),
    ("keys",            "钥匙",   0.03, ["钥匙", "钥匙串"]),
    ("glasses",         "眼镜",   0.04, ["眼镜", "老花镜", "太阳镜"]),
    ("charger",         "充电器", 0.05, ["充电器", "充电头"]),
    ("power bank",      "充电宝", 0.10, ["充电宝", "移动电源"]),
    ("charging cable",  "数据线", 0.05, ["数据线", "充电线"]),
    ("thermos cup",     "保温杯", 0.18, ["保温杯", "保温壶"]),
    ("slippers",        "拖鞋",   0.08, ["拖鞋"]),
    ("tissue box",      "纸巾盒", 0.12, ["纸巾盒", "抽纸"]),
    ("comb",            "梳子",   0.03, ["梳子"]),
    ("wallet",          "钱包",   0.03, ["钱包"]),
    ("wristwatch",      "手表",   0.03, ["手表", "腕表"]),
    ("pen",             "笔",     0.02, ["笔", "钢笔", "圆珠笔"]),
    ("notebook",        "记事本", 0.02, ["记事本", "本子"]),
    ("kettle",          "水壶",   0.20, ["水壶", "烧水壶", "电水壶"]),
    ("rice cooker",     "电饭煲", 0.25, ["电饭煲", "电饭锅"]),
    ("electric fan",    "电风扇", 0.40, ["电风扇", "风扇"]),
    ("pill bottle",     "药瓶",   0.08, ["药瓶", "药罐"]),
    ("towel",           "毛巾",   0.05, ["毛巾", "浴巾"]),
    ("soap",            "肥皂",   0.03, ["肥皂", "香皂"]),
    ("shampoo bottle",  "洗发水", 0.20, ["洗发水", "洗发露"]),
    ("toothpaste",      "牙膏",   0.04, ["牙膏"]),
    ("lunch box",       "饭盒",   0.08, ["饭盒", "便当盒"]),
    ("milk carton",     "牛奶",   0.18, ["牛奶", "牛奶盒"]),
    ("eggs",            "鸡蛋",   0.05, ["鸡蛋"]),
    ("desk lamp",       "台灯",   0.35, ["台灯", "桌灯"]),
    ("alarm clock",     "闹钟",   0.10, ["闹钟"]),
    ("power strip",     "插线板", 0.05, ["插线板", "排插"]),
    ("walking cane",    "拐杖",   0.85, ["拐杖", "拐棍", "手杖"]),
    ("wheelchair",      "轮椅",   0.90, ["轮椅"]),
    ("walker",          "助行器", 0.80, ["助行器"]),
    ("radio",           "收音机", 0.15, ["收音机"]),
    ("calculator",      "计算器", 0.03, ["计算器"]),
    ("hat",             "帽子",   0.15, ["帽子"]),
    ("scarf",           "围巾",   0.10, ["围巾"]),
    ("gloves",          "手套",   0.03, ["手套"]),
    ("trash bin",       "垃圾桶", 0.40, ["垃圾桶", "纸篓"]),
    ("flashlight",      "手电筒", 0.15, ["手电筒"]),
    ("battery",         "电池",   0.03, ["电池"]),
]


def main():
    # ---- 1) 开放词汇验证：词表随口给，零样本检出 ----
    m = YOLO(WORLD_PT)
    m.set_classes(["bus", "person"])
    r = m.predict(BUS, conf=0.25, verbose=False)
    print("开放词汇检出：", [(m.names[int(b.cls[0])], round(float(b.conf[0]), 2))
                          for b in r[0].boxes])

    # ---- 2) COCO 80 + EXT 词表烧死 ----
    # 前一段必须是标准 COCO 80 类、标准顺序（与两端 Labels 的 id 对齐）
    coco = YOLO(str(ROOT / "models/yolov8n.pt"))
    coco_en = [coco.names[i] for i in range(80)]
    ext_en = [e[0] for e in EXT]
    vocab_en = coco_en + ext_en
    assert len(set(vocab_en)) == len(vocab_en), "词表有重复英文名"

    m.set_classes(vocab_en)
    m.save(str(BAKED_PT))
    baked = YOLO(str(BAKED_PT))
    print(f"重参数化完成：{len(baked.names)} 类（COCO 80 + 扩展 {len(EXT)}），"
          f"已保存 {BAKED_PT.name}")

    # ---- 3) 导出 ONNX 并实测 ----
    baked.export(format="onnx", imgsz=320, dynamic=False)
    exported = BAKED_PT.with_suffix(".onnx")
    exported.replace(ONNX_OUT)
    sess = ort.InferenceSession(str(ONNX_OUT),
                                providers=["CPUExecutionProvider"])
    inp = sess.get_inputs()[0]
    out = sess.run(None, {inp.name: np.zeros((1, 3, 320, 320), np.float32)})[0]
    channels, anchors = out.shape[1], out.shape[2]
    print(f"ONNX：输入{tuple(inp.shape)} 输出{out.shape} "
          f"（4+{channels - 4}类×{anchors}锚点） "
          f"{ONNX_OUT.stat().st_size / 1e6:.1f}MB")

    # 真图 letterbox 推理 + CPU 计时
    img0 = cv2.imread(BUS)
    ih, iw = img0.shape[:2]
    scale = min(320 / iw, 320 / ih)
    nw, nh = int(iw * scale), int(ih * scale)
    canvas = np.zeros((320, 320, 3), np.uint8)
    resized = cv2.resize(img0, (nw, nh))
    y0, x0 = (320 - nh) // 2, (320 - nw) // 2
    canvas[y0:y0 + nh, x0:x0 + nw] = resized
    blob = np.ascontiguousarray(
        canvas[:, :, ::-1].transpose(2, 0, 1)[None].astype(np.float32) / 255.0)
    t0 = time.perf_counter()
    for _ in range(30):
        o = sess.run(None, {inp.name: blob})[0]
    dt = (time.perf_counter() - t0) / 30 * 1000
    scores = o[0, 4:, :]
    cls_ids = scores.argmax(0)
    confs = scores.max(0)
    keep = confs > 0.25
    det = sorted(zip(cls_ids[keep], confs[keep]), key=lambda x: -x[1])[:8]
    print(f"CPU {dt:.0f}ms/帧 | 检出{int(keep.sum())}个 "
          f"{[(vocab_en[int(c)], round(float(f), 2)) for c, f in det]}")

    # ---- 4) 生成词表 JSON 与 Labels 同步片段 ----
    vocab = [{"id": i, "en": en, "cn": COCO_CN[en] if i < 80 else EXT[i - 80][1],
              "ext": i >= 80} for i, en in enumerate(vocab_en)]
    (ROOT / "models/world_vocab.json").write_text(
        json.dumps({"model": BAKED_PT.name, "onnx": ONNX_OUT.name,
                    "vocab": vocab}, ensure_ascii=False, indent=1),
        encoding="utf-8")

    kt_cn = ",\n        ".join('"%s"' % v["cn"] for v in vocab[80:])
    kt_alias = "\n        ".join(
        "put(\"%s\", %d)" % (a, 80 + i)
        for i, e in enumerate(EXT) for a in e[3])
    kt_height = ", ".join("%d to %.2ff" % (80 + i, e[2])
                          for i, e in enumerate(EXT))
    (ROOT / "models/world_labels_kotlin.txt").write_text(
        "// 追加到 Labels.kt CLASS_CN 尾部：\n" + kt_cn +
        "\n\n// 追加到 ALIASES：\n" + kt_alias +
        "\n\n// 追加到 REAL_HEIGHT：\n" + kt_height + "\n", encoding="utf-8")

    py_cn = "\n".join('    "%s": "%s",' % (e[0], e[1]) for e in EXT)
    py_alias = "\n".join(
        '    "%s": "%s",' % (a, e[0]) for e in EXT for a in e[3])
    py_height = "\n".join('    "%s": %s,' % (e[0], e[2]) for e in EXT)
    (ROOT / "models/world_labels_py.txt").write_text(
        "# 追加到 labels.py CLASS_CN：\n" + py_cn +
        "\n\n# 追加到 ALIASES：\n" + py_alias +
        "\n\n# 追加到 REAL_HEIGHT：\n" + py_height + "\n", encoding="utf-8")
    print("词表与 Labels 片段已写入 models/world_vocab.json / "
          "world_labels_kotlin.txt / world_labels_py.txt")


if __name__ == "__main__":
    main()
