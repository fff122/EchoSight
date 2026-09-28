# -*- coding: utf-8 -*-
"""找到/丢失播报状态机（FoundLostSpeaker）。

解决的问题（用户实测反馈"找到之后闪一下位置，又开始找"）：
检测闪烁 + 每 5 秒无条件重播 + 丢失提示三者叠加，状态看起来永远定不下来。

规则（Android 端 processFrame 内有同一套 Kotlin 镜像实现，改逻辑需两端同步）：
  1) 去抖：连续 STABLE_STREAK 帧命中才算"稳定找到"，单帧闪烁不播报；
  2) 事件驱动：稳定找到那一刻播一次完整报告；之后只有距离变化超过
     CHANGE_DELTA 或方位词变化才播简报，且间隔不小于 found_interval 秒
     ——原地不动就闭嘴，状态栏照常刷新；
  3) 丢失：稳定找到后连续 lose_after 秒看不见才提示，提示过即复位状态，
     重新找到时会再给一次完整报告；
  4) 从未找到时的"请移动手机"提示保留原节奏（lose_after 后每
     lose_interval 秒一次），不受去抖影响。
"""
import time

STABLE_STREAK = 3          # 连续命中多少帧算稳定（分析帧率约 10-30fps）
CHANGE_DELTA = 0.3         # 距离变化超过这个米数才值得再播报


class FoundLostSpeaker:
    def __init__(self, found_interval=5.0, lose_after=4.0, lose_interval=7.0):
        self.found_interval = found_interval
        self.lose_after = lose_after
        self.lose_interval = lose_interval
        self.reset()

    def reset(self, now=None):
        """切换目标时调用，now 传当前虚拟/真实时钟；缺省 0 表示"计时未开始"。"""
        self.started = now if now is not None else 0.0
        self.streak = 0
        self.stable = False
        self.last_seen = 0.0        # 最近一次命中的时刻；0=自重置以来从未命中
        self.last_report = 0.0
        self.last_lose = None       # None=从未提示过丢失
        self.last_dist = None       # 最近一次"播报过"的距离
        self.last_dir = None
        self.seen_dist = None       # 最近一次"看见"的实际距离/方位
        self.seen_dir = None

    def update(self, now, detected, dist=None, direction=None):
        """喂入一帧结果，返回需要播报的事件或 None。

        事件：(kind, dist, direction)，
        kind ∈ {"full" 完整报告, "brief" 简报, "lose" 丢失提示, "hint" 未找到提示}。
        """
        if detected:
            self.streak += 1
            self.last_seen = now
            self.seen_dist = dist
            self.seen_dir = direction
            if not self.stable:
                if self.streak >= STABLE_STREAK:
                    self.stable = True
                    self.last_report = now
                    self.last_dist = dist
                    self.last_dir = direction
                    return ("full", dist, direction)
                return None
            moved = self.last_dist is None or dist is None or \
                    abs(dist - self.last_dist) > CHANGE_DELTA
            turned = direction is not None and direction != self.last_dir
            if (moved or turned) and \
                    now - self.last_report >= self.found_interval:
                self.last_report = now
                self.last_dist = dist
                self.last_dir = direction
                return ("brief", dist, direction)
            return None

        # 未命中帧
        self.streak = 0
        # 首次提示从搜索开始计时；之后按 lose_interval 节流
        if self.last_lose is None:
            due = now - self.started >= self.lose_after
        else:
            due = now - self.last_lose >= self.lose_interval
        if not due:
            return None
        if self.stable:
            if now - self.last_seen >= self.lose_after:
                self.stable = False
                self.last_lose = now
                return ("lose", self.seen_dist, self.seen_dir)
            return None
        if self.last_seen == 0.0:
            self.last_lose = now
            return ("hint", None, None)
        # 丢失后的周期性提醒：重复"刚才还在哪"引导用户往回找
        self.last_lose = now
        return ("lose", self.seen_dist, self.seen_dir)
