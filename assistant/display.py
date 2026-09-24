# -*- coding: utf-8 -*-
"""画面绘制：检测框 + 中文标签（类名/置信度/距离/方位）+ 顶部状态栏。"""
import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont

font_cn = ImageFont.truetype("C:/Windows/Fonts/msyh.ttc", 26)


def draw_frame(frame, detections, status):
    img = Image.fromarray(cv2.cvtColor(frame, cv2.COLOR_BGR2RGB))
    draw = ImageDraw.Draw(img)
    for x1, y1, x2, y2, cn, conf, dist, direction in detections:
        draw.rectangle([x1, y1, x2, y2], outline=(0, 255, 0), width=3)
        tag = f"{cn} {conf:.2f} {dist:.1f}米{direction}"
        draw.rectangle([x1, y1 - 32, x1 + 350, y1], fill=(0, 180, 0))
        draw.text((x1 + 4, y1 - 32), tag, font=font_cn, fill=(255, 255, 255))
    draw.rectangle([0, 0, frame.shape[1], 44], fill=(0, 0, 0))
    draw.text((10, 6), status, font=font_cn, fill=(0, 255, 255))
    return cv2.cvtColor(np.array(img), cv2.COLOR_RGB2BGR)
