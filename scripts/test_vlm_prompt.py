# -*- coding: utf-8 -*-
"""验证识图兜底新提示词的 JSON 契约：真调 SiliconFlow API，用 bus.jpg 问 bus。

通过标准：模型输出可解析的 JSON，found=true，box 为 4 个 0~1000 的坐标。
这就是 MainActivity.processFindSnapshot 画框+测距所依赖的约定。

运行：buildenv/Scripts/python.exe scripts/test_vlm_prompt.py
"""
import base64
import json
import sys
from pathlib import Path

import requests

ROOT = Path(__file__).resolve().parent.parent
KEY = (ROOT / "android/app/siliconflow_key.txt").read_text().strip()
IMG = base64.b64encode((ROOT / "assets/bus.jpg").read_bytes()).decode()

# 与 SiliconFlowApi.FIND_JSON_RULE 保持一致（改提示词两端同步）
RULE = ("只输出一个 JSON 对象，不要输出任何其他文字："
        '{"found":true或false,"pos":"左上/右上/中间/左下/右下/无",'
        '"box":[x1,y1,x2,y2],"note":"一句话依据，15字以内"}。'
        "box 是目标的外接框，坐标 0 到 1000 归一化，x1y1 是左上角，x2y2 是右下角；"
        '没有找到时 box 给 []，pos 给"无"，note 用一句话说明画面里有什么相近的东西。')

PROMPT = "这是一部手机摄像头看到的画面。请仔细看画面里有没有「公交车」。" + RULE

body = {
    "model": "Qwen/Qwen3-VL-30B-A3B-Instruct",
    "messages": [{"role": "user", "content": [
        {"type": "image_url",
         "image_url": {"url": f"data:image/jpeg;base64,{IMG}"}},
        {"type": "text", "text": PROMPT},
    ]}],
    "stream": False,
    "max_tokens": 200,
}
r = requests.post("https://api.siliconflow.cn/v1/chat/completions",
                  headers={"Authorization": f"Bearer {KEY}"},
                  json=body, timeout=90)
r.raise_for_status()
text = r.json()["choices"][0]["message"]["content"].strip()
print("模型原话:", text[:200])

s, e = text.find("{"), text.rfind("}")
ok = True
if s < 0 or e <= s:
    print("FAIL: 输出里没有 JSON")
    sys.exit(1)
try:
    o = json.loads(text[s:e + 1])
except json.JSONDecodeError as ex:
    print("FAIL: JSON 解析失败", ex)
    sys.exit(1)

ok &= o.get("found") is True
print("found:", o.get("found"))
box = o.get("box")
box_ok = isinstance(box, list) and len(box) == 4 and \
    all(isinstance(v, (int, float)) and 0 <= v <= 1000 for v in box)
print("pos:", o.get("pos"), "| box:", box, "| note:", o.get("note", ""))
ok &= box_ok
if not box_ok:
    print("FAIL: box 不是 4 个 0~1000 的数值")
print()
print("结果：", "PASS —— JSON 契约成立，可以画框测距" if ok else "FAIL —— 需要调整提示词")
sys.exit(0 if ok else 1)
