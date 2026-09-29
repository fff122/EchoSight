# -*- coding: utf-8 -*-
"""YOLOE 视觉学习多图/多框测试矩阵：

  图1 zidane.jpg（两个人）——Qwen 打框（真调 API）
  图2 bus.jpg（跨图泛化目标）

  矩阵：
    T1 学习（像素坐标框）→ 同图检出
    T2 学习（归一化坐标框）→ 同图检出   （隔离坐标约定问题）
    T3 贴图泛化（换位置+缩小）
    T4 跨图：在 bus.jpg 上检出"zidane 里学的人"
    T5 部分框隔离：bus.jpg 用"中心 40% 小框"学习 → 同图检出
       （已知全图框 PASS；若小框 FAIL 即坐实局部框 bug）

运行：buildenv/Scripts/python.exe scripts/test_yoloe_multi.py
"""
import base64
import json
import sys
from pathlib import Path

import cv2
import numpy as np
import requests
from ultralytics import YOLOE
from ultralytics.models.yolo.yoloe import YOLOEVPDetectPredictor

ROOT = Path(__file__).resolve().parent.parent
KEY = (ROOT / "android/app/siliconflow_key.txt").read_text().strip()
URL = "https://api.siliconflow.cn/v1/chat/completions"
MODEL = "Qwen/Qwen3-VL-30B-A3B-Instruct"

print("加载 YOLOE-11s-seg ...")
model = YOLOE("yoloe-11s-seg.pt")
predictor = YOLOEVPDetectPredictor(overrides={
    "model": "yoloe-11s-seg.pt", "mode": "predict",
    "conf": 0.25, "verbose": False})
predictor.setup_model(model=model.model)

RULE = ("只输出一个 JSON 对象，不要输出任何其他文字："
        '{"found":true或false,"pos":"左上/右上/中间/左下/右下/无",'
        '"box":[x1,y1,x2,y2],"note":"一句话依据，15字以内"}。'
        "box 是目标的外接框，坐标 0 到 1000 归一化，x1y1 是左上角，x2y2 是右下角；"
        '没有找到时 box 给 []，pos 给"无"，note 用一句话说明画面里有什么相近的东西。')

fails = []


def check(name, cond, detail=""):
    print(("PASS  " if cond else "FAIL  ") + name + (f"   [{detail}]" if detail else ""))
    if not cond:
        fails.append(name)


def qwen_box(img_path, item):
    img = base64.b64encode(Path(img_path).read_bytes()).decode()
    r = requests.post(URL, headers={"Authorization": f"Bearer {KEY}"},
                      json={"model": MODEL, "stream": False, "max_tokens": 200,
                            "messages": [{"role": "user", "content": [
                                {"type": "image_url", "image_url": {
                                    "url": f"data:image/jpeg;base64,{img}"}},
                                {"type": "text", "text": "这是一部手机摄像头看到的画面。"
                                 f"请仔细看画面里有没有「{item}」。" + RULE}]}]},
                      timeout=90)
    r.raise_for_status()
    text = r.json()["choices"][0]["message"]["content"].strip()
    s, e = text.find("{"), text.rfind("}")
    o = json.loads(text[s:e + 1])
    w, h = cv2.imread(str(img_path)).shape[1], cv2.imread(str(img_path)).shape[0]
    b = o["box"]
    px = [b[0] / 1000 * w, b[1] / 1000 * h, b[2] / 1000 * w, b[3] / 1000 * h]
    print(f"  Qwen: {o.get('note','')}  框(像素): {[round(v) for v in px]}")
    return np.array([px])


def learn(model, predictor, img, box_px, name):
    tmp = ROOT / "_learn_tmp.jpg"
    cv2.imwrite(str(tmp), img)
    predictor.set_prompts({"bboxes": box_px, "cls": np.array([0])})
    pe = predictor.get_vpe(str(tmp))
    model.set_classes([name], pe)
    model.save(str(ROOT / "models/yoloe-live-baked.pt"))
    return YOLOE(str(ROOT / "models/yoloe-live-baked.pt"))


def detect(baked, img, conf=0.2):
    r = baked.predict(img, conf=conf, verbose=False)[0]
    return [(baked.names[int(b.cls[0])], round(float(b.conf), 2),
             [round(float(v)) for v in b.xyxy[0]]) for b in r.boxes]


def paste_variant(img, box_px, scale=0.6):
    x1, y1, x2, y2 = [int(v) for v in box_px[0]]
    crop = img[y1:y2, x1:x2]
    ch, cw = crop.shape[:2]
    small = cv2.resize(crop, (int(cw * scale), int(ch * scale)))
    canvas = np.full((720, 720, 3), 235, np.uint8)
    canvas[40:40 + small.shape[0], 60:60 + small.shape[1]] = small
    return canvas


# ---- 获取另一张真实测试图（picsum 的狗照片，确定性 ID）----
import urllib3
urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)
zidane = ROOT / "assets/zidane.jpg"      # 复用文件名，内容是狗照片
item = "狗"
if zidane.exists():
    zidane.unlink()                       # 每次重新下载，避免内容与 item 错配
print("下载测试图 ...")
r = requests.get("https://picsum.photos/id/237/960/720", timeout=60, verify=False)
r.raise_for_status()
zidane.write_bytes(r.content)
print(f"  成功：{r.headers.get('content-length','?')} 字节")
zidane_img = cv2.imread(str(zidane)) if zidane.exists() else None
busimg = cv2.imread(str(ROOT / "assets/bus.jpg"))
if zidane_img is not None:
    learn_img, target_name = zidane_img, f"目标{item}"
else:
    learn_img, target_name = busimg, "目标中心物"
h, w = learn_img.shape[:2]
print(f"学习图: {w}x{h}  目标名: {target_name}")

# ---- Qwen 打框（真调 API）----
learn_path = (ROOT / "assets/zidane.jpg") if zidane_img is not None \
    else (ROOT / "assets/bus.jpg")
print(f"Qwen 打框：学习图里的「{item}」")
box_px = qwen_box(learn_path, item)

# ---- T1 像素坐标学习 → 同图检出 ----
print("T1 学习（像素坐标框）→ 同图检出")
baked = learn(model, predictor, learn_img, box_px, target_name)
d = detect(baked, learn_img)
check("T1 同图检出目标", any(n == target_name for n, _, _ in d), str(d[:3]))

# ---- T2 坐标契约（文档化）：get_vpe 只认像素坐标，归一化无效 ----
print("T2 坐标契约：归一化坐标应无效（必须像素坐标）")
box01 = np.array([[v / 1000 for v in box_px[0]]])
baked2 = learn(model, predictor, learn_img, box01, target_name)
d2 = detect(baked2, learn_img)
check("T2 归一化坐标不产生检出（契约）",
      not any(n == target_name for n, _, _ in d2), str(d2[:3]))

# ---- T3 贴图泛化 ----
print("T3 贴图泛化（换位置 + 缩小 60%）")
canvas = paste_variant(learn_img, box_px)
d3 = detect(baked, canvas)
check("T3 贴图检出目标", any(n == target_name for n, _, _ in d3), str(d3[:3]))

# ---- T4 跨图（信息项）：在另一张图上找学过的目标 ----
other = busimg if zidane_img is not None else None
if other is not None:
    print("T4 跨图（信息项）：在 bus.jpg 上找学过的目标")
    d4 = detect(baked, other)
    hits = [x for x in d4 if x[0] == target_name]
    print(f"  信息：bus.jpg 上{'检出' if hits else '未检出'}{target_name} {d4[:4]}")
else:
    print("T4 跳过（无第二张图）")

# ---- T5 空区域预期行为：框内无语义主体时学不出东西（不是 bug）----
print("T5 信息项：bus.jpg 正中 40%（车内无主体区域）学习")
bh, bw = int(busimg.shape[0] * 0.4), int(busimg.shape[1] * 0.4)
small_box = np.array([[(busimg.shape[1] - bw) / 2, (busimg.shape[0] - bh) / 2,
                       (busimg.shape[1] + bw) / 2, (busimg.shape[0] + bh) / 2]])
baked5 = learn(model, predictor, busimg, small_box, "中心物体")
d5 = detect(baked5, busimg)
hits5 = [x for x in d5 if x[0] == "中心物体"]
print(f"  信息：{'意外检出' if hits5 else '符合预期：无主体区域学不到东西'} {d5[:3]}")

print()
if fails:
    print(f"结果：{len(fails)} 项未通过 -> {fails}")
    sys.exit(1)
print("结果：全部通过")
