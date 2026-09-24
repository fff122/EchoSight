# -*- coding: utf-8 -*-
"""各环节延迟实测：TTS / ASR / YOLO26x 推理"""
import io
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import cv2
import requests
from ultralytics import YOLO

from assistant import config

# 1) TTS 延迟（短句）
t0 = time.time()
body = {"model": config.TTS_MODEL, "text": "测试延迟", "stream": False,
        "voice_setting": {"voice_id": config.VOICE_ID},
        "audio_setting": {"format": "wav", "sample_rate": 32000, "channel": 1}}
r = requests.post(f"{config.BASE}/v1/t2a_v2", headers=config.HEADERS,
                  json=body, timeout=60)
tts_ms = (time.time() - t0) * 1000
audio = bytes.fromhex(r.json()["data"]["audio"])
print(f"TTS 网络合成延迟: {tts_ms:.0f} ms（之后还有本地播放时间）")

# 2) ASR 延迟（用刚合成的音频回传）
t0 = time.time()
files = {"file": ("a.wav", io.BytesIO(audio), "audio/wav")}
r = requests.post(f"{config.BASE}/v1/audio/transcriptions",
                  headers=config.HEADERS, files=files,
                  data={"model": config.ASR_MODEL, "language": "zh"},
                  timeout=60)
asr_ms = (time.time() - t0) * 1000
print(f"ASR 网络识别延迟: {asr_ms:.0f} ms（短句；长句更久）")

# 3) YOLO26x 推理延迟（CPU，预热后取 10 帧平均）
m = YOLO(str(config.MODELS_DIR / "yolo26x.pt"))
img = cv2.imread(str(config.ASSETS_DIR / "bus.jpg"))
m(img, verbose=False)  # 预热
ts = []
for _ in range(10):
    t0 = time.time()
    m(img, verbose=False)
    ts.append((time.time() - t0) * 1000)
avg = sum(ts) / len(ts)
print(f"YOLO26x 单帧推理: {avg:.0f} ms  =>  约 {1000/avg:.1f} FPS（CPU）")

# 4) 麦克风录音时长是固定的
print(f"麦克风录音: 固定 {config.RECORD_SEC:.1f} 秒（config.RECORD_SEC，可改短）")
