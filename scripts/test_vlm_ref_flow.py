# -*- coding: utf-8 -*-
"""端到端验证"Qwen 打框 → 裁框存参考照片 → 照片比对"整条链路（真调 API）：

  A. 单图识图（新 JSON 契约）        -> 拿到 found + 0~1000 外接框
  B. 按框裁剪参考照片（模拟 App 内 saveReferenceCrops）
  C. 双图照片比对（findReference 契约）-> 参考照片 + 原图 => found=true
  D. 负例：参考照片 + 不相干的图      -> found=false（防止"比对永远为真"）

通过 = A~D 全部符合预期，App 里的自动注册功能可以直接依赖这个契约。

运行：buildenv/Scripts/python.exe scripts/test_vlm_ref_flow.py
"""
import base64
import json
import sys
from pathlib import Path

import cv2
import requests

ROOT = Path(__file__).resolve().parent.parent
KEY = (ROOT / "android/app/siliconflow_key.txt").read_text().strip()
URL = "https://api.siliconflow.cn/v1/chat/completions"
MODEL = "Qwen/Qwen3-VL-30B-A3B-Instruct"

# 与 SiliconFlowApi.FIND_JSON_RULE 保持一致
RULE = ("只输出一个 JSON 对象，不要输出任何其他文字："
        '{"found":true或false,"pos":"左上/右上/中间/左下/右下/无",'
        '"box":[x1,y1,x2,y2],"note":"一句话依据，15字以内"}。'
        "box 是目标的外接框，坐标 0 到 1000 归一化，x1y1 是左上角，x2y2 是右下角；"
        '没有找到时 box 给 []，pos 给"无"，note 用一句话说明画面里有什么相近的东西。')


def b64(path_or_bytes):
    data = path_or_bytes if isinstance(path_or_bytes, bytes) else Path(path_or_bytes).read_bytes()
    return base64.b64encode(data).decode()


def chat(content):
    r = requests.post(URL, headers={"Authorization": f"Bearer {KEY}"},
                      json={"model": MODEL,
                            "messages": [{"role": "user", "content": content}],
                            "stream": False, "max_tokens": 200},
                      timeout=90)
    r.raise_for_status()
    return r.json()["choices"][0]["message"]["content"].strip()


def parse(text):
    s, e = text.find("{"), text.rfind("}")
    o = json.loads(text[s:e + 1])
    box = o.get("box")
    if isinstance(box, list) and box:
        box = [v * 1000 if isinstance(v, (int, float)) and v <= 2 else v for v in box]
    return o.get("found", False), o.get("pos", ""), box, o.get("note", "")


def single(img_b64, item):
    return chat([
        {"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{img_b64}"}},
        {"type": "text", "text": "这是一部手机摄像头看到的画面。请仔细看画面里有没有「" + item + "」。" + RULE},
    ])


def two(ref_b64, frame_b64, item):
    return chat([
        {"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{ref_b64}"}},
        {"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{frame_b64}"}},
        {"type": "text", "text": "第一张图是登记过的「" + item + "」的照片。第二张是现在的画面。"
         "画面里有没有和第一张相同或同款的" + item + "？" + RULE},
    ])


fails = []


def check(name, cond, detail=""):
    print(("PASS  " if cond else "FAIL  ") + name + (f"   [{detail}]" if detail else ""))
    if not cond:
        fails.append(name)


# ---- A. 单图识图拿框 ----
img_b64 = b64(ROOT / "assets/bus.jpg")
text = single(img_b64, "公交车")
found, pos, box, note = parse(text)
check("A1 单图识图 found=true", found is True, str(text[:120]))
check("A2 返回合法外接框", isinstance(box, list) and len(box) == 4
      and all(isinstance(v, (int, float)) and 0 <= v <= 1000 for v in box), str(box))

# ---- B. 按框裁参考照片（saveReferenceCrops 的等价逻辑）----
import numpy as np
raw = base64.b64decode(img_b64)
arr = cv2.imdecode(np.frombuffer(raw, np.uint8), 1)
h, w = arr.shape[:2]
x1, y1, x2, y2 = [int(v / 1000 * d) for v, d in zip(box, (w, h, w, h))]
crop = arr[max(y1, 0):y2, max(x1, 0):x2]
okc, enc = cv2.imencode(".jpg", crop, [cv2.IMWRITE_JPEG_QUALITY, 85])
check("B1 裁出参考照片", okc is True and enc.size > 1000, f"{crop.shape}")
crop_b64 = base64.b64encode(enc.tobytes()).decode()

# ---- C. 照片比对正例：参考照片 + 原图 ----
found2, _, _, note2 = parse(two(crop_b64, img_b64, "公交车"))
check("C1 照片比对正例 found=true", found2 is True, str(note2))

# ---- D. 照片比对负例：参考照片 + 纯色不相干图 ----
blank = cv2.imencode(".jpg", np.full((300, 300, 3), 240, np.uint8))[1].tobytes()
found3, _, _, note3 = parse(two(crop_b64, base64.b64encode(blank).decode(), "公交车"))
check("D1 照片比对负例 found=false", found3 is False, str(note3))

print()
if fails:
    print(f"结果：{len(fails)} 项未通过 -> {fails}")
    sys.exit(1)
print("结果：全部通过 —— 'Qwen 打框→裁参考照片→照片比对'链路契约成立")
