# -*- coding: utf-8 -*-
"""在真实视频序列上验证跟踪算法：

优先下载 OpenCV 官方行人视频 vtest.avi（真实行人走动）；
网络不可达时，用真实照片（assets/zidane.jpg 的狗）生成
"平移 + 缓慢变焦"的视频序列——每帧的目标真值框可解析计算。

指标：跟踪成功率、目标中心误差（像素）、平均 NCC。
运行：buildenv/Scripts/python.exe scripts/test_tracker_video.py
"""
import sys
from pathlib import Path

import cv2
import numpy as np
import requests
from ultralytics import YOLOE
from ultralytics.models.yolo.yoloe import YOLOEVPDetectPredictor

ROOT = Path(__file__).resolve().parent.parent
KEY = (ROOT / "android/app/siliconflow_key.txt").read_text().strip()

fails = []


def check(name, cond, detail=""):
    print(("PASS  " if cond else "FAIL  ") + name + (f"  [{detail}]" if detail else ""))
    if not cond:
        fails.append(name)


def qwen_box(img_bgr, item):
    import base64
    b64 = base64.b64encode(cv2.imencode(".jpg", img_bgr)[1].tobytes()).decode()
    r = requests.post("https://api.siliconflow.cn/v1/chat/completions",
                      headers={"Authorization": f"Bearer {KEY}"},
                      json={"model": "Qwen/Qwen3-VL-30B-A3B-Instruct",
                            "stream": False, "max_tokens": 200,
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


import json  # noqa: E402

# ---- 拿视频帧序列 ----
video_path = ROOT / "assets/vtest.avi"
frames = []
if not video_path.exists():
    print("尝试下载 OpenCV 官方行人视频 ...")
    import urllib3
    urllib3.disable_warnings()
    for attempt in (1, 2):
        try:
            r = requests.get("https://raw.githubusercontent.com/opencv/opencv_extra/"
                             "master/testdata/cv/video/vtest.avi",
                             timeout=120, verify=False)
            r.raise_for_status()
            video_path.write_bytes(r.content)
            print("  下载成功", len(r.content) // 1024, "KB")
            break
        except Exception as ex:
            print(f"  第 {attempt} 次失败：{type(ex).__name__}")

if video_path.exists():
    cap = cv2.VideoCapture(str(video_path))
    for _ in range(150):          # 跳到行人较多的段落
        cap.read()
    for _ in range(200):
        ret, f = cap.read()
        if not ret:
            break
        frames.append(f)
    cap.release()
    src_mode = "vtest 行人视频（真值：无，看连续性）"
else:
    print("视频下载不可达：用真实照片生成平移+变焦序列（真值可算）")
    dog = cv2.imread(str(ROOT / "assets/zidane.jpg"))
    dh, dw = dog.shape[:2]
    box_dog = [126, 37, 765, 704]           # Qwen 实测框（图内坐标）
    for t in range(300):
        s = 0.45 + 0.35 * t / 299           # 缓慢放大 0.45 → 0.8
        px = 40 + int(430 * t / 299)        # 从左往右平移
        py = 40 + int(300 * (0.5 - 0.5 * np.cos(2 * np.pi * t / 299)))
        frame = np.full((960, 1280, 3), 200, np.uint8)
        sized = cv2.resize(dog, (int(dw * s), int(dh * s)))
        frame[py:py + sized.shape[0], px:px + sized.shape[1]] = sized
        gt = [px + box_dog[0] * s, py + box_dog[1] * s,
              px + box_dog[2] * s, py + box_dog[3] * s]
        frames.append((frame, gt))
    src_mode = "生成序列（真值框已知）"

print(f"帧序列：{len(frames)} 帧 —— {src_mode}")

# ---- 学习：第 0 帧 Qwen 打框 ----
f0 = frames[0]
if isinstance(f0, tuple):
    f0_img, gt0 = f0
else:
    f0_img, gt0 = f0, None
print("Qwen 打框：第 0 帧的「行人」..." if src_mode.startswith("vtest")
      else "Qwen 打框：第 0 帧的「狗」...")
item = "行人" if src_mode.startswith("vtest") else "狗"
box0 = qwen_box(f0_img, item)
print("  框:", [round(v) for v in box0])

# ---- 用跟踪器镜像连续跟踪 ----
sys.path.insert(0, str(ROOT / "scripts"))
from test_tracker_pc import Tracker, build_pyramid, NCC_MIN  # noqa: E402

tracker_frames = []
if src_mode.startswith("vtest"):
    tr = Tracker(f0_img, np.array([box0]))
    for f in frames:
        tracker_frames.append((tr.update(f), None))
else:
    # 生成序列：Qwen 的框就是 1280x960 帧坐标系里的像素框，直接用于学习
    tr = Tracker(f0_img, np.array([box0]))
    for f in frames:
        img, gt = f
        r = tr.update(img)
        tracker_frames.append((r, gt))

tracked = sum(1 for r, _ in tracker_frames if r is not None)
print(f"跟踪成功率：{tracked}/{len(tracker_frames)}")

if src_mode.startswith("vtest"):
    nccs = [c for r, c in tracker_frames if r is not None]
    jumps = 0
    prev = None
    for r, _ in tracker_frames:
        if r is not None:
            c = ((r[0] + r[2]) / 2, (r[1] + r[3]) / 2)
            if prev is not None:
                jump = max(abs(c[0] - prev[0]), abs(c[1] - prev[1]))
                if jump > 60:
                    jumps += 1
            prev = c
    check("跟踪率 > 70%", tracked / max(len(tracker_frames), 1) > 0.7,
          f"{tracked}/{len(tracker_frames)}")
    check("框连续（无 60px 以上跳变）", jumps == 0, f"跳变 {jumps} 次")
else:
    errs = []
    for r, gt in tracker_frames:
        if r is None or gt is None:
            continue
        gc = ((r[0] + r[2]) / 2 * 1280, (r[1] + r[3]) / 2 * 960)
        gc_gt = ((gt[0] + gt[2]) / 2, (gt[1] + gt[3]) / 2)
        errs.append(max(abs(gc[0] - gc_gt[0]), abs(gc[1] - gc_gt[1])))
    check("跟踪成功率 > 80%", tracked / max(len(tracker_frames), 1) > 0.8,
          f"{tracked}/{len(tracker_frames)}")
    if errs:
        import statistics
        med = statistics.median(errs)
        check("中心中位误差 < 40px", med < 40, f"中位 {med:.0f}px 最大 {max(errs):.0f}px")

print()
if fails:
    print(f"结果：{len(fails)} 项未通过 -> {fails}")
    sys.exit(1)
print("结果：全部通过")
