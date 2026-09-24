# -*- coding: utf-8 -*-
"""SenseAudio 云端语音接口：TTS 文本合成、麦克风录音 + ASR 识别。"""
import io
import wave

import requests
import sounddevice as sd

from . import config


def synthesize(text):
    """文本转语音，返回 wav 字节；失败返回 None。"""
    body = {
        "model": config.TTS_MODEL, "text": text, "stream": False,
        "voice_setting": {"voice_id": config.VOICE_ID, "speed": 1,
                          "vol": 1, "pitch": 0},
        "audio_setting": {"format": "wav", "sample_rate": 32000,
                          "channel": 1},
    }
    try:
        r = requests.post(f"{config.BASE}/v1/t2a_v2",
                          headers=config.HEADERS, json=body, timeout=60)
        if r.status_code == 200:
            return bytes.fromhex(r.json()["data"]["audio"])
        print(f"[TTS失败] {r.text[:150]}")
    except Exception as e:
        print(f"[TTS异常] {e}")
    return None


def transcribe(fileobj):
    """把 wav 文件对象上传识别，返回文本；失败返回空串。"""
    files = {"file": ("speech.wav", fileobj, "audio/wav")}
    data = {"model": config.ASR_MODEL, "language": "zh",
            "response_format": "json"}
    try:
        r = requests.post(f"{config.BASE}/v1/audio/transcriptions",
                          headers=config.HEADERS, files=files,
                          data=data, timeout=60)
    except Exception as e:
        print(f"[ASR异常] {e}")
        return ""
    if r.status_code != 200:
        print(f"[ASR失败] {r.text[:150]}")
        return ""
    text = r.json().get("text", "").strip()
    print(f"听到: {text}")
    return text


def listen(seconds=config.RECORD_SEC, sample_rate=config.SAMPLE_RATE):
    """录制麦克风固定时长，转 wav 后上传 ASR，返回识别文本。"""
    print(f"录音中...（{seconds}秒）")
    audio = sd.rec(int(seconds * sample_rate), samplerate=sample_rate,
                   channels=1, dtype="int16")
    sd.wait()
    buf = io.BytesIO()
    with wave.open(buf, "wb") as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(sample_rate)
        wf.writeframes(audio.tobytes())
    buf.seek(0)
    return transcribe(buf)
