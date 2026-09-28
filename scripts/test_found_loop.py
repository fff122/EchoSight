# -*- coding: utf-8 -*-
"""found_loop 播报状态机模拟测试：虚拟时钟，不需要摄像头/网络/麦克风。

复现用户反馈的场景并逐条断言（30fps 假设帧间隔 1/30 秒）：
  1 检测闪烁 3 秒        -> 零播报（旧逻辑会闪一下就报）
  2 稳定命中             -> 恰好 1 次完整报告
  3 原地不动 10 秒       -> 零播报（旧逻辑每 5 秒念一遍）
  4 走近 0.9 米          -> 恰好 1 次简报
  5 目标拿走 6 秒        -> 第 4 秒 1 次丢失提示，不重复
  6 重新出现 3 秒        -> 1 次完整报告
  7 从未找到 20 秒       -> 3 次"移动手机"提示（4s/11s/18s）

运行：buildenv/Scripts/python.exe scripts/test_found_loop.py
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from assistant.found_loop import FoundLostSpeaker  # noqa: E402

DT = 1 / 30
FAILS = []


def check(name, cond, detail=""):
    print(("  PASS  " if cond else "  FAIL  ") + name +
          (f"   [{detail}]" if detail else ""))
    if not cond:
        FAILS.append(name)


print("场景1：闪烁检测（命中/丢失逐帧交替 3 秒）")
sp = FoundLostSpeaker()
evs, t = [], 0.0
for i in range(90):
    t += DT
    ev = sp.update(t, i % 2 == 0, 2.0, "正前方")
    if ev:
        evs.append(ev)
check("闪烁 3 秒零播报", len(evs) == 0, str(evs))

print("场景2：稳定命中 2 秒")
evs = []
for i in range(60):
    t += DT
    ev = sp.update(t, True, 2.0, "正前方")
    if ev:
        evs.append(ev)
check("恰好 1 次完整报告", len(evs) == 1 and evs[0][0] == "full", str(evs))

print("场景3：原地不动 10 秒")
evs = []
for i in range(300):
    t += DT
    ev = sp.update(t, True, 2.0, "正前方")
    if ev:
        evs.append(ev)
check("静止 10 秒零播报", len(evs) == 0, str(evs))

print("场景4：走近 0.9 米（5 秒内 2.0 -> 1.1）")
evs = []
for i in range(150):
    t += DT
    ev = sp.update(t, True, 2.0 - i * 0.006, "正前方")
    if ev:
        evs.append(ev)
check("恰好 1 次简报", len(evs) == 1 and evs[0][0] == "brief", str(evs))

print("场景5：目标拿走 6 秒（最后见到于 t=20.0，丢失提示应在 t=24.0）")
evs = []
for i in range(180):
    t += DT
    ev = sp.update(t, False)
    if ev:
        evs.append((round(t, 1), ev[0]))
check("恰好 1 次丢失提示", len(evs) == 1 and evs[0][1] == "lose", str(evs))
check("丢失提示在最后见到 4 秒后出现",
      abs(evs[0][0] - 24.0) < 0.2 if evs else False, str(evs))

print("场景6：重新出现 3 秒")
evs = []
for i in range(90):
    t += DT
    ev = sp.update(t, True, 1.1, "正前方")
    if ev:
        evs.append(ev)
check("重新找到播 1 次完整报告", len(evs) == 1 and evs[0][0] == "full",
      str(evs))

print("场景7：从未找到，空扫 20 秒")
sp2 = FoundLostSpeaker()
sp2.reset(0.0)          # 虚拟时钟从 0 起步，显式声明搜索开始时刻
evs, t2 = [], 0.0
for i in range(600):
    t2 += DT
    ev = sp2.update(t2, False)
    if ev:
        evs.append((round(t2, 1), ev[0]))
hints = [e for e in evs if e[1] == "hint"]
check("20 秒恰好 3 次提示", len(hints) == 3, str(evs))
check("提示时刻约 4/11/18 秒（7 秒节奏，±0.2 容差）",
      len(hints) == 3 and all(abs(a - b) < 0.2 for a, b in
                              zip([h[0] for h in hints], [4.0, 11.0, 18.0])),
      str(hints))

print()
if FAILS:
    print(f"结果：{len(FAILS)} 项未通过 -> {FAILS}")
    sys.exit(1)
print("结果：全部 9 项断言通过")
