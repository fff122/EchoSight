# -*- coding: utf-8 -*-
"""SenseAudio API 连通性测试：鉴权 -> TTS -> ASR"""
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import requests

from assistant import config

out_mp3 = config.ROOT / "tts_test.mp3"

# ---------- 1. 鉴权：查询可用模型 ----------
print("=" * 50)
print("[1/3] 查询可用模型（验证 API Key）...")
r = requests.get(f"{config.BASE}/v1/models",
                 headers=config.HEADERS, timeout=30)
print(f"HTTP {r.status_code}")
if r.status_code != 200:
    print(r.text[:500])
    raise SystemExit("鉴权失败，终止测试")
models = r.json()["data"]
for m in models:
    print(f"  [{m['mode']:>10}] {m['id']}")

# ---------- 2. TTS：文本转语音 ----------
print("=" * 50)
print("[2/3] 语音合成 TTS...")
tts_body = {
    "model": config.TTS_MODEL,
    "text": "你好，我是你的盲人识物助手，请问你要寻找什么物品？",
    "stream": False,
    "voice_setting": {"voice_id": config.VOICE_ID,
                      "speed": 1, "vol": 1, "pitch": 0},
    "audio_setting": {"format": "mp3", "sample_rate": 32000,
                      "bitrate": 128000, "channel": 2},
}
r = requests.post(f"{config.BASE}/v1/t2a_v2", headers=config.HEADERS,
                  json=tts_body, timeout=60)
print(f"HTTP {r.status_code}")
if r.status_code != 200:
    print(r.text[:500])
    raise SystemExit("TTS 失败")
resp = r.json()
audio_hex = resp["data"]["audio"]
with open(out_mp3, "wb") as f:
    f.write(bytes.fromhex(audio_hex))
print(f"  音频已保存 {out_mp3.name}，"
      f"字符数: {resp.get('extra_info', {}).get('word_count')}")

# ---------- 3. ASR：语音识别（把刚合成的音频回传） ----------
print("=" * 50)
print(f"[3/3] 语音识别 ASR（回传 {out_mp3.name}）...")
with open(out_mp3, "rb") as f:
    files = {"file": (out_mp3.name, f, "audio/mpeg")}
    data = {"model": config.ASR_MODEL, "language": "zh",
            "response_format": "json"}
    r = requests.post(f"{config.BASE}/v1/audio/transcriptions",
                      headers=config.HEADERS, files=files,
                      data=data, timeout=60)
print(f"HTTP {r.status_code}")
if r.status_code == 200:
    print(f"  识别结果: {r.json().get('text')}")
else:
    print(r.text[:500])

print("=" * 50)
print("测试完成，用系统默认播放器播放音频...")
os.startfile(out_mp3)
