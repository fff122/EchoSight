# -*- coding: utf-8 -*-
"""后台推理线程：主线程投递最新帧，推理线程异步产出检测框，画面不卡顿。"""
import threading

from ultralytics import YOLO

from . import config


class Detector:
    def __init__(self, model_path=config.MODEL_PATH):
        self.model = YOLO(str(model_path))
        self.in_frame = None
        self.out_boxes = []
        self.evt = threading.Event()
        self.lock = threading.Lock()
        threading.Thread(target=self._loop, daemon=True).start()

    def submit(self, frame):
        with self.lock:
            self.in_frame = frame
        self.evt.set()

    def latest(self):
        with self.lock:
            return list(self.out_boxes)

    def _loop(self):
        while True:
            self.evt.wait()
            self.evt.clear()
            with self.lock:
                frame = None if self.in_frame is None else self.in_frame.copy()
            if frame is None:
                continue
            res = self.model(frame, conf=config.CONF,
                             imgsz=config.IMGSZ, verbose=False)
            boxes = [(float(b.xyxy[0][0]), float(b.xyxy[0][1]),
                      float(b.xyxy[0][2]), float(b.xyxy[0][3]),
                      int(b.cls[0]), float(b.conf[0]))
                     for b in res[0].boxes]
            with self.lock:
                self.out_boxes = boxes
