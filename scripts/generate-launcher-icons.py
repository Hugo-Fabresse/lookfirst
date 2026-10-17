#!/usr/bin/env python3
"""Regenerate the pre-API-26 launcher PNGs (mdpi..xxxhdpi) from the same shapes as the adaptive icon.

Standard library + numpy only. Usage: python3 scripts/generate-launcher-icons.py
"""
import struct
import zlib
from pathlib import Path

import numpy as np

RES = Path(__file__).resolve().parent.parent / "app/src/main/res"
BLUE = (0x1F, 0x4E, 0x79)
WHITE = (255, 255, 255)
SS = 4  # supersampling
CROP = (12.0, 96.0)  # part of the 108 viewport shown by a legacy icon


def bezier(p0, p1, p2, p3, n=60):
    t = np.linspace(0, 1, n)[:, None]
    p0, p1, p2, p3 = map(np.array, (p0, p1, p2, p3))
    return (1 - t) ** 3 * p0 + 3 * (1 - t) ** 2 * t * p1 + 3 * (1 - t) * t**2 * p2 + t**3 * p3


ALMOND = np.vstack([bezier((24, 54), (38, 36), (70, 36), (84, 54)), bezier((84, 54), (70, 72), (38, 72), (24, 54))])
MODULES = [(46.5, 46.5), (56.5, 46.5), (51.5, 51.5), (46.5, 56.5), (56.5, 56.5)]


def in_polygon(x, y, poly):
    inside = np.zeros(x.shape, bool)
    n = len(poly)
    for i in range(n):
        x1, y1 = poly[i]
        x2, y2 = poly[(i + 1) % n]
        if y1 == y2:
            continue
        cond = (y1 > y) != (y2 > y)
        xi = (x2 - x1) * (y - y1) / (y2 - y1) + x1
        inside ^= cond & (x < xi)
    return inside


def render(size, round_shape):
    px = size * SS
    c = CROP[0] + (np.arange(px) + 0.5) * (CROP[1] - CROP[0]) / px
    x, y = np.meshgrid(c, c)
    img = np.zeros((px, px, 3), float)
    img[:] = BLUE
    img[in_polygon(x, y, ALMOND)] = WHITE
    img[(x - 54) ** 2 + (y - 54) ** 2 <= 14**2] = BLUE
    for mx, my in MODULES:
        img[(x >= mx) & (x < mx + 5) & (y >= my) & (y < my + 5)] = WHITE
    u = (np.arange(px) + 0.5) / px
    ux, uy = np.meshgrid(u, u)
    if round_shape:
        mask = (ux - 0.5) ** 2 + (uy - 0.5) ** 2 <= 0.5**2
    else:
        r = 0.18
        dx = np.maximum(np.abs(ux - 0.5) - (0.5 - r), 0)
        dy = np.maximum(np.abs(uy - 0.5) - (0.5 - r), 0)
        mask = dx**2 + dy**2 <= r**2
    rgba = np.dstack([img, mask * 255.0])
    rgba = rgba.reshape(size, SS, size, SS, 4).mean(axis=(1, 3))
    return np.rint(rgba).astype(np.uint8)


def write_png(path, arr):
    h, w, _ = arr.shape
    raw = b"".join(b"\x00" + arr[r].tobytes() for r in range(h))

    def chunk(tag, data):
        body = tag + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body))

    path.write_bytes(
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(raw, 9))
        + chunk(b"IEND", b"")
    )


for density, size in {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}.items():
    folder = RES / f"mipmap-{density}"
    folder.mkdir(exist_ok=True)
    write_png(folder / "ic_launcher.png", render(size, False))
    write_png(folder / "ic_launcher_round.png", render(size, True))
