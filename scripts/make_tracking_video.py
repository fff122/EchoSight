# -*- coding: utf-8 -*-
"""生成"实时学习跟踪"演示视频，输出到桌面：

  优先用 OpenCV 官方行人视频 vtest.avi（真实行人走动）；
  网络不可达时用真实照片生成"平移+变焦"序列（真值可算）。

  流程与 App 一致：第 0 帧 Qwen 打框 → 视觉模板学习 → 逐帧 NCC 跟踪，
  跟丢时用最后位置重锚定（对应 App 里的重新定位）。

  输出：C:\\Users\\JIA\\Desktop\\YOLOE跟踪演示.mp4
运行：buildenv/Scripts/python.exe scripts/make_tracking_video.py
"""
import base64
import json
import sys
from pathlib import Path

import cv2
import numpy as np
import requests

ROOT = Path(__file__).resolve().parent.parent
KEY = (ROOT / "android/app/siliconflow_key.txt").read_text().strip()
OUT = Path(r"C:\Users\JIA\Desktop\YOLOE跟踪演示.mp4")

fails = []


def check(name, cond, detail=""):
    print(("PASS  " if cond else "FAIL  ") + name + (f"  [{detail}]" if detail else ""))
    if not cond:
        fails.append(name)


def qwen_box(img_bgr, item):
    b64 = base64.b64encode(cv2.imencode(".jpg", img_bgr)[1].tobytes()).decode()
    r = requests.post("https://api.siliconflow.cn/v1/chat/completions",
                      headers={"Authorization": f"Bearer {KEY}"},
                      json={"model": "Qwen/Qwen3-VL-30B-A3B-Instruct",
                            "stream": False, "max_tokens": 150,
                            "messages": [{"role": "user", "content": [
                                {"type": "image_url", "image_url": {
                                    "url": f"data:image/jpeg;base64,{b64}"}},
                                {"type": "text", "text": "这是一部手机摄像头看到的画面。"
                                 f"请仔细看画面里有没有「{item}」。" +
                                 '只输出一个 JSON 对象：{"found":true或false,'
                                 '"box":[x1,y1,x2,y2]}，box 为 0~1000 归一化外接框，'
                                 "没有找到时 box 给 []。"}]}]},
                      timeout=90)
    r.raise_for_status()
    text = r.json()["choices"][0]["message"]["content"]
    o = json.loads(text[text.find("{"):text.rfind("}") + 1])
    h, w = img_bgr.shape[:2]
    b = o["box"]
    return [b[0] / 1000 * w, b[1] / 1000 * h, b[2] / 1000 * w, b[3] / 1000 * h]


# ---- 跟踪器（与 VisionTracker.kt 同规则）----
WORK_W, WORK_H = 320, 240
SCALES = (1.0, 1.3, 0.75)
NCC_MIN = 0.42


def to_gray(img):
    g = img.astype(np.float32)
    return ((g[:, :, 2] * 3 + g[:, :, 1] * 6 + g[:, :, 0]) / 2550.0).reshape(-1)


class Tracker:
    def __init__(self, frame, box):
        work = cv2.resize(frame, (WORK_W, WORK_H))
        fw, fh = frame.shape[1], frame.shape[0]
        # box 是原图像素坐标 → 换算到工作分辨率（此前直接当工作坐标用是错位的根因）
        x1 = int(box[0] / fw * WORK_W); y1 = int(box[1] / fh * WORK_H)
        x2 = int(box[2] / fw * WORK_W); y2 = int(box[3] / fh * WORK_H)
        x1 = max(0, min(x1, WORK_W - 8)); y1 = max(0, min(y1, WORK_H - 8))
        x2 = max(x1 + 4, min(x2, WORK_W)); y2 = max(y1 + 4, min(y2, WORK_H))
        crop = work[y1:y2, x1:x2]              # 保持学习时的原始尺度
        self.pyramid = []
        for s in SCALES:
            sW = max(8, int(crop.shape[1] * s))
            sH = max(8, int(crop.shape[0] * s))
            scaled = cv2.resize(crop, (sW, sH))
            g = to_gray(scaled).reshape(sH, sW)
            m = float(g.mean())
            sd = max(1e-3, float(np.sqrt(((g - m) ** 2).mean())))
            self.pyramid.append((sW, sH, g, m, sd))
        self.last = None
        self.active = True
        self.age = 0

    def update(self, frame):
        if not self.active:
            return None
        work = cv2.resize(frame, (WORK_W, WORK_H))
        gray = to_gray(work).reshape(WORK_H, WORK_W)
        if self.last is None:
            cx, cy = WORK_W / 2, WORK_H / 2          # 无先验：全域搜索
            self.last = (0.2, 0.2, 0.8, 0.8)
        else:
            nb = self.last
            cx = (nb[0] + nb[2]) / 2 * WORK_W
            cy = (nb[1] + nb[3]) / 2 * WORK_H
        best, bp = -1.0, None
        for (tw, th, tg, tm, tsd) in self.pyramid:
            if tw > WORK_W - 4 or th > WORK_H - 4:
                continue                        # 超界尺度跳过
            winX = min(int(tw * 2.0), WORK_W // 2)
            winY = min(int(th * 2.0), WORK_H // 2)
            ox0 = max(0, min(int(cx - winX), WORK_W - tw))
            oy0 = max(0, min(int(cy - winY), WORK_H - th))
            ox1 = max(0, min(int(cx + winX), WORK_W - tw))
            oy1 = max(0, min(int(cy + winY), WORK_H - th))
            for oy in range(oy0, oy1 + 1, 2):
                for ox in range(ox0, ox1 + 1, 2):
                    win = gray[oy:oy + th, ox:ox + tw]
                    m = float(win.mean())
                    sd = max(1e-3, float(np.sqrt(((win - m) ** 2).mean())))
                    t = tg - tm
                    s = float(((win - m) * t).sum()) / (tw * th * tsd * sd)
                    if s > best:
                        best, bp = s, (ox, oy, tw, th)
        self.age += 1
        if best < NCC_MIN or self.age > 400:
            self.active = False
            return None
        ox, oy, tw, th = bp
        w, h = frame.shape[1], frame.shape[0]
        self.last = (ox / WORK_W, oy / WORK_H,
                     (ox + tw) / WORK_W, (oy + th) / WORK_H)
        return (ox * w / WORK_W, oy * h / WORK_H,
                (ox + tw) * w / WORK_W, (oy + th) * h / WORK_H, best)

    def reanchor(self, frame, box):
        """用最后位置重新编码（对应 App 的重新定位）。"""
        self.__init__(frame, box)


# ---- 取帧序列 ----
vtest = ROOT / "assets/vtest.avi"
frames = []
if not vtest.exists():
    print("尝试下载 OpenCV 官方行人视频 ...")
    import urllib3
    urllib3.disable_warnings()
    for attempt in (1, 2):
        try:
            r = requests.get("https://raw.githubusercontent.com/opencv/opencv_extra/"
                             "master/testdata/cv/video/vtest.avi",
                             timeout=120, verify=False)
            r.raise_for_status()
            vtest.write_bytes(r.content)
            print("  下载成功", len(r.content) // 1024, "KB")
            break
        except Exception as ex:
            print(f"  第 {attempt} 次失败：{type(ex).__name__}")
if vtest.exists():
    cap = cv2.VideoCapture(str(vtest))
    for _ in range(150):
        cap.read()
    for _ in range(250):
        ret, f = cap.read()
        if not ret:
            break
        frames.append(f)
    cap.release()
    item = "行人"
    src_mode = "vtest 行人视频"
else:
    print("视频下载不可达：用真实照片生成平移+变焦序列")
    dog = cv2.imread(str(ROOT / "assets/zidane.jpg"))
    dh, dw = dog.shape[:2]
    box_dog = [126, 37, 765, 704]
    for t in range(300):
        s = 0.45 + 0.35 * t / 299
        px = 40 + int(430 * t / 299)
        py = 40 + int(300 * (0.5 - 0.5 * np.cos(2 * np.pi * t / 299)))
        frame = np.full((960, 1280, 3), 200, np.uint8)
        sized = cv2.resize(dog, (int(dw * s), int(dh * s)))
        frame[py:py + sized.shape[0], px:px + sized.shape[1]] = sized
        frames.append(frame)
    item = "狗"
    src_mode = "生成序列（平移+变焦）"

print(f"帧序列：{len(frames)} 帧 —— {src_mode}")

# ---- Qwen 打框学习 ----
box0 = qwen_box(frames[0], item)
print("Qwen 学习框:", [round(v) for v in box0])
tracker = Tracker(frames[0], box0)

# ---- 渲染输出视频 ----
H, W = frames[0].shape[:2]
fourcc = cv2.VideoWriter_fourcc(*"mp4v")
out = cv2.VideoWriter(str(OUT), fourcc, 25, (W, H))
if not out.isOpened():
    OUT = OUT.with_suffix(".avi")
    out = cv2.VideoWriter(str(OUT), cv2.VideoWriter_fourcc(*"XVID"), 25, (W, H))
assert out.isOpened(), "VideoWriter 打不开"

label = "learned target"
tracked_n = 0
reanchors = 0
hold = 18                                   # 第 0 帧的"学习"定格展示
for i, f in enumerate(frames):
    show = f.copy()
    r = tracker.update(f)
    if r is None and tracker.active is False and i > 0:
        # 跟丢：用最后位置重锚定（对应 App 的重新定位）
        last = tracker.last
        if last is not None:
            rb = [last[0] * W, last[1] * H, last[2] * W, last[3] * H]
            tracker.reanchor(f, rb)
            reanchors += 1
            r = tracker.update(f)
    if r is not None:
        tracked_n += 1
        x1, y1, x2, y2, conf = r[0], r[1], r[2], r[3], r[4]
        cv2.rectangle(show, (int(x1), int(y1)), (int(x2), int(y2)), (0, 230, 0), 3)
        cv2.rectangle(show, (int(x1), int(y1) - 40),
                      (int(x1) + 330, int(y1)), (0, 230, 0), -1)
        cv2.putText(show, f"{label} {conf:.2f}", (int(x1) + 8, int(y1) - 10),
                    cv2.FONT_HERSHEY_SIMPLEX, 1.0, (255, 255, 255), 2)
    else:
        cv2.putText(show, "RE-ANCHOR...", (W // 3, H // 2),
                    cv2.FONT_HERSHEY_SIMPLEX, 1.2, (0, 200, 255), 3)
    if i < hold:                            # 学习定格：画 Qwen 的黄框
        cv2.rectangle(show, (int(box0[0]), int(box0[1])),
                      (int(box0[2]), int(box0[3])), (0, 240, 255), 4)
        cv2.putText(show, "LEARN (Qwen box)", (int(box0[0]),
                    max(int(box0[1]) - 14, 30)),
                    cv2.FONT_HERSHEY_SIMPLEX, 1.0, (0, 240, 255), 2)
    cv2.putText(show, f"frame {i}", (16, 40),
                cv2.FONT_HERSHEY_SIMPLEX, 1.0, (255, 255, 255), 2)
    out.write(show)
out.release()

rate = tracked_n / max(len(frames), 1)
check("跟踪率 > 70%", rate > 0.7, f"{tracked_n}/{len(frames)} = {rate:.0%}")
check("输出视频已生成", OUT.exists() and OUT.stat().st_size > 10000,
      f"{OUT.stat().st_size // 1024}KB" if OUT.exists() else "")
print(f"重锚定次数：{reanchors}")
print()
if fails:
    print(f"结果：{len(fails)} 项未通过 -> {fails}")
    sys.exit(1)
print(f"结果：全部通过 —— 演示视频已保存到桌面：{OUT}")
