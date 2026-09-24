# -*- coding: utf-8 -*-
"""异步语音播报：后台单线程依次合成并播放，调用方不阻塞。"""
import queue
import threading
import winsound

from . import config
from .audio_api import synthesize

_speech_q = queue.Queue()


def _speech_worker():
    while True:
        text, done = _speech_q.get()
        try:
            audio = synthesize(text)
            if audio:
                with open(config.TMP_WAV, "wb") as f:
                    f.write(audio)
                if _on_play_start:
                    _on_play_start()
                winsound.PlaySound(str(config.TMP_WAV),
                                   winsound.SND_FILENAME)
                if _on_play_end:
                    _on_play_end()
        finally:
            _speech_q.task_done()
            if done is not None:
                done.set()


# 播放开始/结束钩子（用于在播报期间屏蔽语音指令监听，防自激）
_on_play_start = None
_on_play_end = None


def set_playback_hooks(on_start, on_end):
    global _on_play_start, _on_play_end
    _on_play_start, _on_play_end = on_start, on_end


threading.Thread(target=_speech_worker, daemon=True).start()


def speak(text, wait=False):
    """排队播报；wait=True 时阻塞到这句话完整播完。"""
    done = threading.Event() if wait else None
    _speech_q.put((text, done))
    if wait:
        done.wait()
