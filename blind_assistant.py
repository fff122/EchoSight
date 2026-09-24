# -*- coding: utf-8 -*-
"""盲人识物助手（桌面版入口）
流程：语音询问目标 -> 录音识别确认 -> 摄像头扫描(中文标注/测距/方位)
      -> 语音播报；找不到时提示移动手机。
操作：T/空格 = 语音切换目标，Q = 退出。
"""
from assistant.desktop import DesktopApp

if __name__ == "__main__":
    DesktopApp().run()
