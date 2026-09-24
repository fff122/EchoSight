# -*- coding: utf-8 -*-
"""语音指令监听：后台持续听麦克风，本地能量检测发现有人说话时
才录音上传 ASR，识别出口令后回调。播报 TTS 期间自动屏蔽，防自激。

口令：
  找到了 / 找着了          -> on_found()，进入安静待命
  找XX / 换XX / 帮我找XX   -> on_target(英文名)，切换目标/恢复扫描
"""
import threading

import numpy as np
import sounddevice as sd

from . import config
from .audio_api import transcribe
from .labels import match_target

FOUND_WORDS = ("找到了", "找着了", "找到了谢谢")
# 目标指令里可能出现的引导词，剥掉后再交给别名匹配
TARGET_PREFIXES = ("帮我找一个", "帮我找下", "帮我找", "我要找一个",
                   "我要找下", "我要找", "我想找一个", "我想找下",
                   "我想找", "请找一个", "请找下", "请找", "找一个",
                   "找下", "找", "换成", "换一个", "换个", "换")

_block = int(config.VAD_BLOCK_SEC * config.SAMPLE_RATE)
_start_blocks = config.VAD_START_BLOCKS
_end_blocks = config.VAD_END_BLOCKS
_min_len = int(config.VAD_MIN_SEC / config.VAD_BLOCK_SEC)
_max_len = int(config.VAD_MAX_SEC / config.VAD_BLOCK_SEC)


def parse_command(text):
    """解析 ASR 文本，返回 ('found', None) 或 ('target', 英文名) 或 None。"""
    if not text:
        return None
    if any(w in text for w in FOUND_WORDS):
        return ("found", None)
    for p in sorted(TARGET_PREFIXES, key=len, reverse=True):
        if p in text:
            rest = text.replace(p, "", 1)
            en = match_target(rest) or match_target(text)
            return ("target", en) if en else None
    # 没有引导词但整句里直接带了物品名，也算切换指令
    en = match_target(text)
    return ("target", en) if en else None


class VoiceListener:
    """能量 VAD + ASR 指令识别。on_command(kind, value) 在监听线程中回调。"""

    def __init__(self, on_command):
        self.on_command = on_command
        self.active = True
        self._muted = threading.Event()
        self.threshold = config.VAD_MIN_RMS
        threading.Thread(target=self._loop, daemon=True).start()

    def stop(self):
        self.active = False

    def mute(self):
        """屏蔽监听（TTS 播报期间）。"""
        self._muted.set()

    def unmute(self):
        """解除屏蔽，尾部再留 VAD_TAIL_SEC 防回声（由调用方定时调用）。"""
        self._muted.clear()

    # ---------------- 内部实现 ----------------
    def _calibrate(self):
        n = int(config.VAD_CALIBRATE_SEC * config.SAMPLE_RATE)
        rec = sd.rec(n, samplerate=config.SAMPLE_RATE,
                     channels=1, dtype="int16")
        sd.wait()
        x = rec[:, 0].astype(np.float32)
        rms = float(np.sqrt(np.mean(x * x)))
        self.threshold = max(config.VAD_MIN_RMS,
                             rms * config.VAD_NOISE_FACTOR)
        print(f"[监听] 环境噪声 RMS={rms:.0f}，触发阈值={self.threshold:.0f}")

    def _record_utterance(self, first_blocks):
        """已确认说话开始，继续录音直到停顿或超时，返回 int16 一维数组。"""
        chunks = list(first_blocks)
        quiet = 0
        length = len(chunks)
        while length < _max_len:
            b = sd.rec(_block, samplerate=config.SAMPLE_RATE,
                       channels=1, dtype="int16")
            sd.wait()
            x = b[:, 0]
            chunks.append(x)
            length += 1
            rms = float(np.sqrt(np.mean(x.astype(np.float32) ** 2)))
            if rms < self.threshold:
                quiet += 1
                if quiet >= _end_blocks:
                    break
            else:
                quiet = 0
        audio = np.concatenate(chunks)
        return audio if length >= _min_len else None

    def _loop(self):
        self._calibrate()
        loud = 0
        pending = []
        while self.active:
            b = sd.rec(_block, samplerate=config.SAMPLE_RATE,
                       channels=1, dtype="int16")
            sd.wait()
            x = b[:, 0]
            rms = float(np.sqrt(np.mean(x.astype(np.float32) ** 2)))
            if rms >= self.threshold:
                loud += 1
                pending.append(x)
                if loud == _start_blocks:
                    # 确认说话开始；屏蔽期间直接丢弃，不进入识别
                    if self._muted.is_set():
                        loud, pending = 0, []
                        continue
                    audio = self._record_utterance(pending)
                    loud, pending = 0, []
                    if audio is not None:
                        self._handle(audio)
            else:
                if loud > 0:
                    # 说话前的短停顿，先缓存可能是开头的部分
                    pending.append(x)
                loud = 0
                if len(pending) > _start_blocks + _end_blocks:
                    pending = pending[-_start_blocks:]

    def _handle(self, audio):
        import io
        import wave
        buf = io.BytesIO()
        with wave.open(buf, "wb") as wf:
            wf.setnchannels(1)
            wf.setsampwidth(2)
            wf.setframerate(config.SAMPLE_RATE)
            wf.writeframes(audio.tobytes())
        buf.seek(0)
        text = transcribe(buf)
        cmd = parse_command(text)
        if cmd:
            self.on_command(*cmd)
