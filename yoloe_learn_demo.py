# -*- coding: utf-8 -*-
"""YOLOE 实时学习演示（桌面版）

把要学的东西放进画面正中的白框里，按 S 学会它——之后本地每帧实时画框，
报方位和距离。可以连续学多个物品（向量库持久化，本会话内有效）。

按键：
  S = 学习画面正中白框里的物品（自动命名：目标1、目标2…）
  C = 清空全部已学物品
  Q = 退出

运行：buildenv\\Scripts\\python.exe yoloe_learn_demo.py
自检：buildenv\\Scripts\\python.exe yoloe_learn_demo.py --selftest

实现说明：每次学习都通过子进程调用 scripts/learn_worker.py 完成编码+烘焙
（实测同一进程连续学习会状态污染：第 4 次学习最高分 0.95→0.077），
主程序只负责摄像头与实时检测。
"""
import subprocess
import sys
from pathlib import Path

import cv2
import numpy as np

ROOT = Path(__file__).resolve().parent   # 脚本在项目根目录，只跳一级
BAKED = ROOT / "models/yoloe-live-baked.pt"
BANK = ROOT / "models/live_bank.pt"
TMP_FRAME = ROOT / "_learn_tmp.jpg"
WORKER = ROOT / "scripts/learn_worker.py"

SELFTEST = "--selftest" in sys.argv
SHOW_CONF = 0.2          # 出框阈值：相似度超过它才画框（原来 0.35 太严）


def enhance(frame):
    """暗画面自动增亮（伽马校正）。学习与检测必须用同一套增亮，
    保证编码特征一致；画面足够亮时原样返回。"""
    m = float(frame.mean())
    if m >= 85:
        return frame
    gamma = min(2.2, max(0.6, np.log(0.45) / np.log(max(m, 1) / 255.0)))
    lut = np.array([((i / 255.0) ** gamma) * 255 for i in range(256)]).astype("uint8")
    return cv2.LUT(frame, lut)


def run_worker(frame, box, name, bank=None, baked=None):
    """子进程学习：编码当前帧 box 区域并重建本地模型。返回是否成功。"""
    bank = bank or BANK
    baked = baked or BAKED
    cv2.imwrite(str(TMP_FRAME), enhance(frame))   # 增亮后保存，与检测侧一致
    r = subprocess.run(
        [sys.executable, str(WORKER),
         "--frame", str(TMP_FRAME),
         "--box", ",".join(str(v) for v in box),
         "--name", name,
         "--bank", str(bank),
         "--baked", str(baked)],
        cwd=str(ROOT), capture_output=True, text=True)
    ok = "[worker] OK" in (r.stdout or "")
    if not ok:
        print(r.stdout[-500:] if r.stdout else "", r.stderr[-500:] if r.stderr else "")
    return ok


def main():
    import numpy as np
    import torch
    from ultralytics import YOLOE

    cap = cv2.VideoCapture(0)
    if not cap.isOpened():
        print("无法打开摄像头（索引 0）")
        return 1
    frame_w = int(cap.get(cv2.CAP_PROP_FRAME_WIDTH))
    frame_h = int(cap.get(cv2.CAP_PROP_FRAME_HEIGHT))

    def center_box(w, h):
        bw, bh = int(w * 0.4), int(h * 0.4)
        return [(w - bw) / 2, (h - bh) / 2, (w + bw) / 2, (h + bh) / 2]

    learned_names = []
    baked = None
    if BANK.exists() and BAKED.exists():
        try:
            import torch as _t
            learned_names = list(_t.load(BANK, map_location="cpu",
                                         weights_only=False)["names"])
            baked = YOLOE(str(BAKED))
            print(f"已加载历史学习：{learned_names}")
        except Exception as ex:
            print("加载历史学习失败：", ex)

    if SELFTEST:
        ret, frame = cap.read()
        if not ret:
            print("自检失败：读不到画面")
            return 1
        cb = center_box(frame_w, frame_h)
        if frame.mean() < 40:
            print("摄像头画面几乎全黑（可能盖着盖子）：在画面中绘制测试图案后继续")
            cv2.rectangle(frame, (int(cb[0]), int(cb[1])),
                          (int(cb[2]), int(cb[3])), (45, 45, 45), -1)
            ccx, ccy = int((cb[0] + cb[2]) / 2), int((cb[1] + cb[3]) / 2)
            r0 = int(min(cb[2] - cb[0], cb[3] - cb[1]) / 4)
            cv2.circle(frame, (ccx, ccy), r0, (0, 220, 255), -1)
            cv2.rectangle(frame, (ccx - r0, ccy - r0),
                          (ccx, ccy), (255, 120, 0), -1)

        print("自检：走子进程学习流程 ...")
        st_bank = ROOT / "models/_selftest_bank.pt"
        st_baked = ROOT / "models/_selftest_baked.pt"
        if not run_worker(frame, cb, "自检目标", st_bank, st_baked):
            print("自检结果: FAIL —— 子进程学习失败")
            cap.release()
            return 1
        baked = YOLOE(str(st_baked))

        r = baked.predict(frame, conf=0.2, verbose=False)[0]
        d1 = [(baked.names[int(b.cls[0])], round(float(b.conf), 2))
              for b in r.boxes]
        hit1 = any(n == "自检目标" for n, _ in d1)

        x1, y1, x2, y2 = [int(v) for v in cb]
        crop = frame[y1:y2, x1:x2]
        ch, cw = crop.shape[:2]
        small = cv2.resize(crop, (int(cw * 0.6), int(ch * 0.6)))
        canvas = np.full((640, 640, 3), 235, np.uint8)
        canvas[40:40 + small.shape[0], 60:60 + small.shape[1]] = small
        r2 = baked.predict(canvas, conf=0.2, verbose=False)[0]
        d2 = [(baked.names[int(b.cls[0])], round(float(b.conf), 2))
              for b in r2.boxes]
        hit2 = any(n == "自检目标" for n, _ in d2)

        print(f"原图自检: {'PASS' if hit1 else 'MISS'} {d1[:3]}")
        print(f"贴图泛化: {'PASS' if hit2 else 'FAIL'} {d2[:3]}")
        cap.release()
        if hit1 or hit2:
            print("自检结果: PASS —— 学习链路可用")
            return 0
        print("自检结果: FAIL —— 请确认摄像头盖已打开、光线充足后重试")
        return 1

    print()
    print("=== YOLOE 实时学习演示 ===")
    print("把要学的物品放进画面正中白框，按 S 学会它（学习时画面会停几秒，正常）")
    print("按 C 清空已学，按 Q 退出")
    print()

    pending_name = None
    learn_count = len(learned_names)
    while True:
        ret, frame = cap.read()
        if not ret:
            break

        display = frame.copy()
        h, w = display.shape[:2]

        if pending_name is not None:
            name = pending_name
            pending_name = None
            cv2.rectangle(display, (0, 0), (w, h), (255, 255, 255), -1)
            cv2.putText(display, "learning...", (w // 3, h // 2),
                        cv2.FONT_HERSHEY_SIMPLEX, 1.2, (40, 40, 40), 3)
            cv2.imshow("YOLOE live learning", display)
            cv2.waitKey(1)
            if run_worker(frame, center_box(w, h), name):
                learn_count += 1
                try:
                    baked = YOLOE(str(BAKED))
                    learned_names.append(name)
                except Exception as ex:
                    print("烘焙模型加载失败：", ex)
                speak(f"已学会{name}。")
                print(f"[学习成功] {name}（共 {len(learned_names)} 个）")
            else:
                speak("学习失败，请再试一次。")
                print("[学习失败] 见上方 worker 输出")

        if baked is not None and learned_names:
            # 用低阈值跑检测，自己按 SHOW_CONF 过滤：屏幕上能看到最高分，
            # 方便判断"差一点没出框"还是"完全没看到"
            model_in = enhance(frame)          # 与学习侧同一套增亮
            results = baked.predict(model_in, conf=0.05, verbose=False)[0]
            best = 0.0
            for b in results.boxes:
                cn = baked.names[int(b.cls[0])]
                x1, y1, x2, y2 = [float(v) for v in b.xyxy[0]]
                conf = float(b.conf[0])
                if conf < SHOW_CONF:
                    continue
                best = max(best, conf)
                dist, direction = estimate(
                    (x1, y1, x2, y2), frame_w * 0.85, frame_w,
                    "learned")  # 未登记真实高度，按默认 0.3 米估距
                cv2.rectangle(display, (int(x1), int(y1)),
                              (int(x2), int(y2)), (0, 220, 0), 3)
                cv2.putText(display,
                            f"{cn} {conf:.2f} {direction} ~{dist:.1f}m",
                            (int(x1), max(int(y1) - 12, 24)),
                            cv2.FONT_HERSHEY_SIMPLEX, 0.9, (0, 220, 0), 2)
            if best > 0:
                status = f"识别中 最高相似度 {best:.2f}"
            else:
                best_all = max((float(b.conf[0]) for b in results.boxes),
                               default=0.0)
                status = (f"看到疑似目标 {best_all:.2f}（<{SHOW_CONF} 未出框）"
                          if best_all > 0.05 else "视野内没有已学物品")
        else:
            bw, bh = int(w * 0.4), int(h * 0.4)
            cv2.rectangle(display, ((w - bw) // 2, (h - bh) // 2),
                          ((w + bw) // 2, (h + bh) // 2), (255, 255, 255), 2)
            cv2.putText(display, "put object here, press S",
                        ((w - bw) // 2, (h - bh) // 2 - 12),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.8, (255, 255, 255), 2)
            status = "把物品放进白框，按 S 学习"

        cv2.putText(display, status, (16, 36),
                    cv2.FONT_HERSHEY_SIMPLEX, 1.0, (255, 255, 255), 2)
        cv2.imshow("YOLOE live learning  (S=learn C=clear Q=quit)", display)
        key = cv2.waitKey(1) & 0xFF
        if key == ord("q"):
            break
        if key == ord("s"):
            learn_count_hint = learn_count + 1
            pending_name = f"目标{learn_count_hint}"
            print(f"[开始学习] {pending_name}（正中白框内的物品）")
        elif key == ord("c"):
            learned_names.clear()
            baked = None
            for f in (BANK, BAKED):
                Path(f).unlink(missing_ok=True)
            speak("已清空。")
            print("[清空] 已学物品全部移除")

    cap.release()
    cv2.destroyAllWindows()
    return 0


if __name__ == "__main__":
    from assistant.geometry import estimate
    from assistant.speaker import speak
    sys.exit(main())
