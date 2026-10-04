#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
从 Android VectorDrawable 导出 WhaleChat 启动图标的高清位图。

图形定义直接来自：
  res/drawable/ic_launcher_background.xml  —— 108 视口线性渐变底
  res/drawable/ic_launcher_foreground.xml  —— 白色对话气泡 + 三个蓝点
  res/mipmap-anydpi-v26/ic_launcher.xml    —— 自适应图标合成关系

输出：全幅方形 / 圆形（Wear 观感）/ 圆角方形（Android 标准）三种蒙版，
每种都是 2× 超采样后 LANCZOS 缩小，边缘干净。
"""
import math
import os

import numpy as np
from PIL import Image, ImageDraw

VB = 108.0                      # VectorDrawable viewport
INNER = 72.0 / 108.0            # 自适应图标可见区（中心 72/108）

# 渐变（ic_launcher_background.xml）
GRAD_START = (8.0, 4.0)
GRAD_END = (100.0, 106.0)
C_START = (0x60, 0x7C, 0xFF)
C_END = (0x3A, 0x54, 0xE8)

# 前景（ic_launcher_foreground.xml）
BUBBLE_FILL = (0xFF, 0xFF, 0xFF)
DOT_FILL = (0x3D, 0x57, 0xEC)
DOT_R = 3.2
DOT_CENTERS = ((45.0, 51.5), (55.0, 51.5), (65.0, 51.5))


def arc_points(cx, cy, r, a0_deg, a1_deg, steps=64):
    """按角度递增采样圆弧（屏幕坐标，y 向下）。"""
    out = []
    for i in range(steps + 1):
        a = math.radians(a0_deg + (a1_deg - a0_deg) * i / steps)
        out.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    return out


def bubble_points():
    """
    气泡闭合路径（原 pathData）：
      M39,33 L69,33 A9,9 0 0 1 78,42 L78,61 A9,9 0 0 1 69,70
      L52,70 L43,79 L43,70 L39,70 A9,9 0 0 1 30,61 L30,42 A9,9 0 0 1 39,33 Z
    四段都是 90° 圆角，圆心分别落在 (69,42)/(69,61)/(39,61)/(39,42)。
    """
    pts = [(39.0, 33.0), (69.0, 33.0)]
    pts += arc_points(69.0, 42.0, 9.0, -90.0, 0.0)      # 右上
    pts += [(78.0, 61.0)]
    pts += arc_points(69.0, 61.0, 9.0, 0.0, 90.0)       # 右下
    pts += [(52.0, 70.0), (43.0, 79.0), (43.0, 70.0), (39.0, 70.0)]
    pts += arc_points(39.0, 61.0, 9.0, 90.0, 180.0)     # 左下
    pts += [(30.0, 42.0)]
    pts += arc_points(39.0, 42.0, 9.0, 180.0, 270.0)    # 左上
    return pts


def gradient_image(size):
    """线性渐变底。渐变是线性的，用小图算完再放大，结果精确且省内存。"""
    small = 512
    y, x = np.mgrid[0:small, 0:small]
    ux = (x + 0.5) * (VB / small)
    uy = (y + 0.5) * (VB / small)
    dx = GRAD_END[0] - GRAD_START[0]
    dy = GRAD_END[1] - GRAD_START[1]
    t = ((ux - GRAD_START[0]) * dx + (uy - GRAD_START[1]) * dy) / (dx * dx + dy * dy)
    t = np.clip(t, 0.0, 1.0)[..., None].astype(np.float32)
    c0 = np.array(C_START, dtype=np.float32)
    c1 = np.array(C_END, dtype=np.float32)
    rgb = c0 * (1.0 - t) + c1 * t
    arr = np.zeros((small, small, 4), dtype=np.uint8)
    arr[..., :3] = np.round(rgb).astype(np.uint8)
    arr[..., 3] = 255
    return Image.fromarray(arr, "RGBA").resize((size, size), Image.BICUBIC)


def masks(size, ss):
    """返回 (气泡蒙版, 圆点蒙版)，按 viewport→像素 缩放。"""
    px = size * ss
    s = px / VB
    bubble = Image.new("L", (px, px), 0)
    ImageDraw.Draw(bubble).polygon([(p[0] * s, p[1] * s) for p in bubble_points()], fill=255)
    dots = Image.new("L", (px, px), 0)
    dd = ImageDraw.Draw(dots)
    for cx, cy in DOT_CENTERS:
        dd.ellipse(
            [((cx - DOT_R) * s, (cy - DOT_R) * s), ((cx + DOT_R) * s, (cy + DOT_R) * s)],
            fill=255,
        )
    return bubble, dots


def render_base(size, ss=2):
    """渲染完整 108 视口方形图标。"""
    base = gradient_image(size)
    bubble, dots = masks(size, ss)
    for mask, color in ((bubble, BUBBLE_FILL), (dots, DOT_FILL)):
        layer = Image.new("RGBA", (base.size), color + (255,))
        base = Image.composite(layer, base, mask.resize(base.size, Image.LANCZOS))
    return base


def masked_variant(base, kind, out_size, ss=2):
    """按自适应图标规则取中心 72/108，套指定蒙版后输出。"""
    s = base.size[0]
    crop = int(round(s * INNER))
    off = (s - crop) // 2
    ic = base.crop((off, off, off + crop, off + crop))

    px = out_size * ss
    big = ic.resize((px, px), Image.LANCZOS)
    m = Image.new("L", (px, px), 0)
    d = ImageDraw.Draw(m)
    box = [0, 0, px - 1, px - 1]
    if kind == "circle":
        d.ellipse(box, fill=255)
    elif kind == "rounded":
        d.rounded_rectangle(box, radius=int(px * 0.22), fill=255)
    else:
        d.rectangle(box, fill=255)

    out = Image.new("RGBA", (px, px), (0, 0, 0, 0))
    out.paste(big, (0, 0), m)
    return out.resize((out_size, out_size), Image.LANCZOS)


SVG = """<svg xmlns="http://www.w3.org/2000/svg" width="108" height="108" viewBox="0 0 108 108">
  <defs>
    <linearGradient id="bg" gradientUnits="userSpaceOnUse" x1="8" y1="4" x2="100" y2="106">
      <stop offset="0" stop-color="#607CFF"/>
      <stop offset="1" stop-color="#3A54E8"/>
    </linearGradient>
    <clipPath id="inner"><circle cx="54" cy="54" r="36"/></clipPath>
  </defs>
  <rect width="108" height="108" fill="url(#bg)"/>
  <path fill="#FFFFFF" d="M39,33 L69,33 A9,9 0 0 1 78,42 L78,61 A9,9 0 0 1 69,70 L52,70 L43,79 L43,70 L39,70 A9,9 0 0 1 30,61 L30,42 A9,9 0 0 1 39,33 Z"/>
  <circle cx="45" cy="51.5" r="3.2" fill="#3D57EC"/>
  <circle cx="55" cy="51.5" r="3.2" fill="#3D57EC"/>
  <circle cx="65" cy="51.5" r="3.2" fill="#3D57EC"/>
</svg>
"""


def main():
    import sys
    if len(sys.argv) > 1:
        out_dir = os.path.abspath(sys.argv[1])
    else:
        out_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "icon")
    os.makedirs(out_dir, exist_ok=True)

    base = render_base(4096, ss=2)

    jobs = [
        ("whalechat-icon-circle-2048.png", "circle", 2048),
        ("whalechat-icon-rounded-2048.png", "rounded", 2048),
        ("whalechat-icon-square-2048.png", "square", 2048),
        ("whalechat-icon-circle-1024.png", "circle", 1024),
        ("whalechat-icon-rounded-512.png", "rounded", 512),
        ("whalechat-icon-circle-512.png", "circle", 512),
    ]
    for name, kind, size in jobs:
        img = masked_variant(base, kind, size)
        path = os.path.join(out_dir, name)
        img.save(path)
        print("  %-42s %dx%d" % (name, size, size))

    svg_path = os.path.join(out_dir, "whalechat-icon.svg")
    with open(svg_path, "w", encoding="utf-8") as f:
        f.write(SVG)
    print("  whalechat-icon.svg                       (矢量母版)")

    # 预览拼图，方便一眼看三种蒙版
    prev = Image.new("RGBA", (3 * 512 + 4 * 24, 512 + 2 * 24), (0, 0, 0, 0))
    for i, kind in enumerate(("square", "rounded", "circle")):
        prev.paste(masked_variant(base, kind, 512), (24 + i * (512 + 24), 24), masked_variant(base, kind, 512))
    prev.save(os.path.join(out_dir, "whalechat-icon-preview.png"))
    print("  whalechat-icon-preview.png               三种蒙版对照图")


if __name__ == "__main__":
    main()
