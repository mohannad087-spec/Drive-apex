#!/usr/bin/env python3
"""Rasterise the DriveApex mark into the classic launcher icon densities.

The app ships an adaptive icon, which every launcher from Android 8 is meant to
handle. This head unit's launcher does not draw it, and shows the platform
placeholder instead -- so the same mark is also rendered here as ordinary PNGs,
which no launcher can refuse.

Drawn with numpy and written with zlib rather than an imaging library, because
the build machine has none and the mark is four arcs and five bars.
"""

import math
import os
import struct
import zlib

import numpy as np

BG = (0x0A, 0x0F, 0x16)
BLUE = (0x1D, 0x9B, 0xF0)
RED = (0xFF, 0x52, 0x52)
CYAN = (0x22, 0xB8, 0xCF)
PURPLE = (0x7C, 0x5C, 0xFF)

# Four times the output size, averaged down at the end: the cheapest honest
# antialiasing there is, and these shapes are all curves.
SS = 4


def blend(canvas, mask, colour):
    for c in range(3):
        canvas[:, :, c] = np.where(mask, colour[c], canvas[:, :, c])


def arc_mask(size, cx, cy, radius, width, start_deg, end_deg):
    """Points within `width` of the circle, between two angles measured
    clockwise from east, which is how screen angles run."""
    y, x = np.mgrid[0:size, 0:size]
    dx, dy = x - cx, y - cy
    r = np.sqrt(dx * dx + dy * dy)
    ang = (np.degrees(np.arctan2(dy, dx))) % 360
    start, end = start_deg % 360, end_deg % 360
    within = (ang >= start) & (ang <= end) if start <= end else (ang >= start) | (ang <= end)
    return (np.abs(r - radius) <= width / 2) & within


def bar_mask(size, cx, cy, half_height, width):
    y, x = np.mgrid[0:size, 0:size]
    return (np.abs(x - cx) <= width / 2) & (np.abs(y - cy) <= half_height)


def round_caps(size, points, radius):
    y, x = np.mgrid[0:size, 0:size]
    mask = np.zeros((size, size), dtype=bool)
    for px, py in points:
        mask |= ((x - px) ** 2 + (y - py) ** 2) <= radius ** 2
    return mask


def render(px, circular):
    size = px * SS
    canvas = np.zeros((size, size, 3), dtype=np.uint8)
    canvas[:, :] = BG

    cx = cy = size / 2
    radius = size * 0.30
    width = size * 0.075

    if circular:
        y, x = np.mgrid[0:size, 0:size]
        outside = ((x - cx) ** 2 + (y - cy) ** 2) > (size / 2) ** 2
    else:
        outside = np.zeros((size, size), dtype=bool)

    # The dial, then the redline over its last quarter.
    blend(canvas, arc_mask(size, cx, cy, radius, width, 135, 45), BLUE)
    blend(canvas, arc_mask(size, cx, cy, radius, width, 315, 45), RED)
    ends = [
        (cx + radius * math.cos(math.radians(a)), cy + radius * math.sin(math.radians(a)))
        for a in (135, 45)
    ]
    blend(canvas, round_caps(size, ends, width / 2), BLUE)

    # The sound, five bars across the middle.
    # Half-heights, so the tallest bar stays inside the dial rather than
    # growing out through the bottom of it.
    bars = [(-0.130, 0.055, CYAN), (-0.065, 0.105, BLUE), (0.0, 0.155, PURPLE),
            (0.065, 0.105, BLUE), (0.130, 0.055, CYAN)]
    bw = size * 0.045
    for offset, half, colour in bars:
        bx = cx + offset * size
        hh = half * size
        blend(canvas, bar_mask(size, bx, cy, hh, bw), colour)
        blend(canvas, round_caps(size, [(bx, cy - hh), (bx, cy + hh)], bw / 2), colour)

    alpha = np.full((size, size), 255, dtype=np.uint8)
    if circular:
        canvas[outside] = 0
        alpha[outside] = 0

    rgba = np.dstack([canvas, alpha])
    # Average the supersampled image down.
    rgba = rgba.reshape(px, SS, px, SS, 4).mean(axis=(1, 3)).astype(np.uint8)
    return rgba


def write_png(path, rgba):
    height, width, _ = rgba.shape
    raw = b"".join(b"\x00" + rgba[row].tobytes() for row in range(height))

    def chunk(tag, data):
        body = tag + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body))

    png = (b"\x89PNG\r\n\x1a\n"
           + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
           + chunk(b"IDAT", zlib.compress(raw, 9))
           + chunk(b"IEND", b""))
    with open(path, "wb") as handle:
        handle.write(png)


def main():
    densities = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    root = "app/src/main/res"
    for name, px in densities.items():
        directory = os.path.join(root, f"mipmap-{name}")
        os.makedirs(directory, exist_ok=True)
        write_png(os.path.join(directory, "ic_launcher.png"), render(px, False))
        write_png(os.path.join(directory, "ic_launcher_round.png"), render(px, True))
        print(f"  mipmap-{name}: {px}x{px}")


if __name__ == "__main__":
    main()
