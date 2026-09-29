# -*- coding: utf-8 -*-
"""跟踪算法 PC 压力测试（镜像 VisionTracker.kt 的"区域缩放匹配"方案）：

  T1 物品平移 60 帧 → 不越界 + 框跟着物品走
  T2 物品缩小 0.7 倍 → 0.8 尺度档接住
  T3 物品移出画面 → NCC 跌破阈值 → 自动停用（不乱报）

运行：buildenv/Scripts/python.exe scripts/test_tracker_pc.py
"""
import sys
from pathlib import Path

import cv2
import numpy as np

ROOT = Path(__file__).resolve().parent.parent
NCC_MIN = 0.42


def to_gray(img):
    g = img.astype(np.float32)
    return (g[:, :, 2] * 3 + g[:, :, 1] * 6 + g[:, :, 0]) / 2550.0


class Tracker:                     # 镜像 VisionTracker.kt
    def __init__(self, frame, box):
        work = cv2.resize(frame, (320, 240))
        fw, fh = frame.shape[1], frame.shape[0]
        nb = [box[0] / fw, box[1] / fh, box[2] / fw, box[3] / fh]
        self.base_wn = nb[2] - nb[0]
        self.base_hn = nb[3] - nb[1]
        x1 = int(nb[0] * 320); y1 = int(nb[1] * 240)
        crop = work[y1:y1 + max(8, int(self.base_hn * 240)),
                    x1:x1 + max(8, int(self.base_wn * 320))]

        long_side = max(crop.shape[0], crop.shape[1])
        s = 48.0 / long_side if long_side > 48 else 1.0
        tmpl = cv2.resize(crop, (max(8, int(crop.shape[1] * s)),
                                 max(8, int(crop.shape[0] * s))))
        self.th, self.tw = tmpl.shape[:2]
        g = to_gray(tmpl)
        self.tm = float(g.mean())
        self.tsd = max(1e-3, float(np.sqrt(((g - self.tm) ** 2).mean())))
        self.tmpl = g.reshape(self.th, self.tw)

        self.last = [nb]
        self.active = True

    def update(self, frame):
        if not self.active:
            return None
        work = cv2.resize(frame, (320, 240))
        gray = to_gray(work)
        nb = self.last[-1]
        cx = (nb[0] + nb[2]) / 2 * 320
        cy = (nb[1] + nb[3]) / 2 * 240

        best, bp = -1.0, None
        for s in (1.0, 1.25, 0.8):
            region_w = int(self.base_wn * 320 * s) + 24
            region_h = int(self.base_hn * 240 * s) + 24
            if region_w < self.tw or region_h < self.th:
                continue
            rx0 = max(0, min(int(cx - region_w / 2), 320 - region_w))
            ry0 = max(0, min(int(cy - region_h / 2), 240 - region_h))
            region = work[ry0:ry0 + region_h, rx0:rx0 + region_w]

            # 关键：区域缩放到固定匹配尺寸（目标恢复到模板大小）
            mw, mh = self.tw + 24, self.th + 24
            scaled = cv2.resize(region, (mw, mh))
            g = to_gray(scaled)
            k = region_w / mw                       # 匹配空间 → 工作坐标

            local_best, lbp = -1.0, None
            for oy in range(0, mh - self.th + 1, 2):
                for ox in range(0, mw - self.tw + 1, 2):
                    win = g[oy:oy + self.th, ox:ox + self.tw]
                    m = float(win.mean())
                    sd = max(1e-3, float(np.sqrt(((win - m) ** 2).mean())))
                    t = self.tmpl - self.tm
                    sc = float(((win - m) * t).sum()) / (self.tw * self.th * self.tsd * sd)
                    if sc > local_best:
                        local_best, lbp = sc, (ox, oy)
            if local_best > best:
                best = local_best
                mcx = rx0 + (lbp[0] + self.tw / 2) * k
                mcy = ry0 + (lbp[1] + self.th / 2) * k
                bp = (mcx, mcy, s)

        if best < NCC_MIN:
            self.active = False
            return None
        mcx, mcy, s = bp
        half_w = self.base_wn * 320 * s / 2 / 320
        half_h = self.base_hn * 240 * s / 2 / 240
        nb = (max(0.0, mcx / 320 - half_w), max(0.0, mcy / 240 - half_h),
              min(1.0, mcx / 320 + half_w), min(1.0, mcy / 240 + half_h))
        self.last.append(nb)
        w, h = frame.shape[1], frame.shape[0]
        return (nb[0] * w, nb[1] * h, nb[2] * w, nb[3] * h, best)


fails = []


def check(name, cond, detail=""):
    print(("PASS  " if cond else "FAIL  ") + name + (f"  [{detail}]" if detail else ""))
    if not cond:
        fails.append(name)


dog = cv2.imread(str(ROOT / "assets/zidane.jpg"))
if dog is None:
    print("缺少测试图 assets/zidane.jpg")
    sys.exit(1)
dh, dw = dog.shape[:2]
box = [126, 37, 765, 704]                     # Qwen 实测的狗框
obj_cx = (box[0] + box[2]) / 2
obj_cy = (box[1] + box[3]) / 2

print("T1 物品平移 60 帧")
canvas0 = np.full((960, 1600, 3), 200, np.uint8)
base_off = (160, 120)
canvas0[base_off[1]:base_off[1] + dh, base_off[0]:base_off[0] + dw] = dog
learn_box = box + np.array([base_off[0], base_off[1],
                            base_off[0], base_off[1]])
tr = Tracker(canvas0, learn_box)
ok_track, last_err = True, 0.0
for i in range(60):
    dx, dy = i * 4, (i * 3) % 120
    frame = np.full((960, 1600, 3), 200, np.uint8)
    frame[base_off[1] + dy:base_off[1] + dy + dh,
          base_off[0] + dx:base_off[0] + dx + dw] = dog
    r = tr.update(frame)
    if r is not None:
        gcx = (r[0] + r[2]) / 2
        gcy = (r[1] + r[3]) / 2
        want = (base_off[0] + dx + obj_cx, base_off[1] + dy + obj_cy)
        last_err = max(abs(gcx - want[0]), abs(gcy - want[1]))
        if last_err > 80:
            ok_track = False
    elif i > 5:
        ok_track = False
check("T1 平移跟踪不越界且跟住物品", ok_track, f"最大中心偏差 {last_err:.0f}px")

print("T2 物品缩小 0.7 倍")
frame2 = np.full((960, 1600, 3), 200, np.uint8)
small = cv2.resize(dog, (int(dw * 0.7), int(dh * 0.7)))
frame2[100:100 + small.shape[0], 500:500 + small.shape[1]] = small
r2 = tr.update(frame2)
check("T2 缩小后仍跟踪到", r2 is not None, str(r2))

print("T3 物品移出画面（应自动停用，不乱报）")
frame3 = np.full((960, 1600, 3), 200, np.uint8)
for _ in range(3):
    r3 = tr.update(frame3)
check("T3 跟丢后自动停用", tr.active is False and r3 is None)

print()
if fails:
    print(f"结果：{len(fails)} 项未通过 -> {fails}")
    sys.exit(1)
print("结果：全部通过 —— 跟踪算法无越界、跟得住、丢得干净")
