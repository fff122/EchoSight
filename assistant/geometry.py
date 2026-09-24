# -*- coding: utf-8 -*-
"""单目测距与方位判断：按物体真实高度和像素高度估算距离。"""
import numpy as np

from . import config
from .labels import REAL_HEIGHT


def estimate(box, fx, frame_w, target_en):
    """返回 (距离米, 方位文字)。

    fx：相机焦距（像素）；frame_w：画面宽度（像素）；target_en：英文类名。
    """
    x1, y1, x2, y2 = box
    h_px = max(y2 - y1, 1)
    dist = REAL_HEIGHT.get(target_en, config.DEFAULT_HEIGHT) * fx / h_px
    angle = np.degrees(np.arctan2((x1 + x2) / 2 - frame_w / 2, fx))
    direction = "左前方" if angle < -12 else ("右前方" if angle > 12 else "正前方")
    return dist, direction
