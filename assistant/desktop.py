# -*- coding: utf-8 -*-
"""桌面版主流程：
语音询问目标 -> 录音识别确认 -> 摄像头扫描(中文标注/测距/方位)
-> 语音播报；找不到时提示移动手机。

全程语音控制（无需按键）：
  说"找到了"          -> 停止播报，安静待命
  说"找XX / 换XX"     -> 切换目标；待命时说即恢复扫描
按 Q 退出。
"""
import threading
import time
import winsound

import cv2

from . import config
from .audio_api import listen
from .detector import Detector
from .display import draw_frame
from .geometry import estimate
from .labels import CLASS_CN, match_target
from .speaker import set_playback_hooks, speak
from .voice_command import VoiceListener


class DesktopApp:
    def __init__(self):
        self.detector = Detector()
        self.target_en = None
        self.target_id = None
        self.target_cn = None
        self.standby = False          # True=找到后安静待命
        self.listener = None
        self._state_lock = threading.Lock()
        self._unmute_timer = None

    def _class_id(self, en):
        return next(k for k, v in self.detector.model.names.items() if v == en)

    # ---------------- 语音指令回调（监听线程） ----------------
    def _on_command(self, kind, value):
        if kind == "found":
            with self._state_lock:
                self.standby = True
            self._confirm_beep()      # 轻提示音：知道了
        elif kind == "target":
            with self._state_lock:
                self.target_en = value
                self.target_id = self._class_id(value)
                self.target_cn = CLASS_CN[value]
                self.standby = False
            speak(f"好的，帮你寻找{self.target_cn}。")

    def _confirm_beep(self):
        # 提示音也可能触发 VAD，播报式屏蔽一下
        if self.listener:
            self.listener.mute()
        winsound.Beep(660, 180)
        if self.listener:
            self.listener.mute()
            self._schedule_unmute(0.3)

    def _schedule_unmute(self, delay=config.VAD_TAIL_SEC):
        if self._unmute_timer:
            self._unmute_timer.cancel()
        self._unmute_timer = threading.Timer(
            delay, self.listener.unmute)
        self._unmute_timer.daemon = True
        self._unmute_timer.start()

    # ---------------- 主流程 ----------------
    def run(self):
        # 1) 语音确认目标
        for _ in range(3):
            speak("请问你要寻找什么物品？", wait=True)
            said = listen()
            self.target_en = match_target(said) if said else None
            if self.target_en:
                break
            speak("没有听懂或不认识这个物品，请再说一个。", wait=True)

        if not self.target_en:
            speak("多次识别失败，程序退出。", wait=True)
            return

        self.target_id = self._class_id(self.target_en)
        self.target_cn = CLASS_CN[self.target_en]
        speak(f"好的，帮你寻找{self.target_cn}，请把摄像头对准四周。", wait=True)

        # 2) 启动语音指令监听（播报期间自动屏蔽麦克风）
        self.listener = VoiceListener(self._on_command)
        set_playback_hooks(self.listener.mute,
                           lambda: self._schedule_unmute())

        # 3) 摄像头扫描
        cap = cv2.VideoCapture(0)
        if not cap.isOpened():
            raise RuntimeError("无法打开摄像头")
        frame_w = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
        frame_h = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))
        fx = frame_w * config.FX_FACTOR

        search_start = time.time()
        last_lose_prompt = 0
        last_found_report = 0
        prev_t = time.time()

        print("扫描中，语音说“找到了”待命，说“找XX”换目标，按 Q 退出...")
        while True:
            ret, frame = cap.read()
            if not ret:
                break

            self.detector.submit(frame)
            now = time.time()

            with self._state_lock:
                target_id = self.target_id
                target_en = self.target_en
                target_cn = self.target_cn
                standby = self.standby

            detections = []
            for x1, y1, x2, y2, cls, conf in self.detector.latest():
                if cls != target_id:
                    continue
                dist, direction = estimate(
                    (x1, y1, x2, y2), fx, frame_w, target_en)
                detections.append(
                    (x1, y1, x2, y2, target_cn, conf, dist, direction))

            if standby:
                status = "已找到，安静待命。需要时请说：找XX"
            elif detections:
                search_start = now
                nearest = max(detections,
                              key=lambda d: (d[3] - d[1]) * (d[2] - d[0]))
                dist, direction = nearest[6], nearest[7]
                status = f"找到{target_cn}：{direction} 约{dist:.1f}米"
                if now - last_found_report > config.FOUND_REPORT_INTERVAL:
                    if dist < 0.5:
                        speak(f"{target_cn}就在面前，很近了，大约{dist:.1f}米。")
                    else:
                        speak(f"{target_cn}在{direction}，距离大约{dist:.1f}米。")
                    last_found_report = now
            else:
                status = f"寻找{target_cn}中..."
                if (now - search_start > 6 and
                        now - last_lose_prompt > config.LOSE_PROMPT_INTERVAL):
                    speak(f"没有看到{target_cn}，请移动手机寻找目标。")
                    last_lose_prompt = now

            fps = 1 / (now - prev_t)
            prev_t = now
            # 切换目标后重置搜索计时
            if target_en != getattr(self, "_last_target", None):
                self._last_target = target_en
                search_start = now
                last_lose_prompt = now
                last_found_report = now

            shown = draw_frame(frame, detections, f"{status}   {fps:.0f}FPS")
            cv2.imshow("盲人识物助手 - 语音控制 Q退出", shown)
            if cv2.waitKey(1) & 0xFF == ord("q"):
                break

        self.listener.stop()
        cap.release()
        cv2.destroyAllWindows()
        speak("已退出，再见。", wait=True)
