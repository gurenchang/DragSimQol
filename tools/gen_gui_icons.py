"""Generates the GUI icon textures (white on transparent, tinted in game).

One PNG per icon per GUI scale (1x..4x of a 10-unit icon), drawn at 8x and
downsampled, so icons are crisp and anti-aliased at every Minecraft GUI scale.
Run: python tools/gen_gui_icons.py
"""
import math
from pathlib import Path
from PIL import Image, ImageDraw

OUT = Path(__file__).resolve().parents[1] / "src/main/resources/assets/dragsimmacro/textures/gui/icons"
BASE = 10          # icon size in GUI units
SS = 8             # supersampling
W = 255

def canvas(px):
    s = px * SS
    return Image.new("L", (s, s), 0), s

def line(d, s, pts, w):
    pts = [(x * s, y * s) for x, y in pts]
    d.line(pts, fill=W, width=max(1, int(w * s)), joint="curve")
    r = w * s / 2
    for x, y in (pts[0], pts[-1]):
        d.ellipse((x - r, y - r, x + r, y + r), fill=W)

def circle(d, s, cx, cy, r, w=None):
    box = ((cx - r) * s, (cy - r) * s, (cx + r) * s, (cy + r) * s)
    if w is None:
        d.ellipse(box, fill=W)
    else:
        d.ellipse(box, outline=W, width=max(1, int(w * s)))

def poly(d, s, pts):
    d.polygon([(x * s, y * s) for x, y in pts], fill=W)

def pickaxe(d, s, sw):
    line(d, s, [(0.16, 0.86), (0.62, 0.40)], sw * 1.1)          # handle
    r = 0.40                                                    # curved head across the handle end
    cx, cy = 0.34, 0.68
    d.arc(((cx - r) * s, (cy - r) * s, (cx + r) * s, (cy + r) * s), -108, 18, fill=W, width=int(sw * 1.6 * s))
    for a in (-108, 18):
        x = cx + math.cos(math.radians(a)) * r
        y = cy + math.sin(math.radians(a)) * r
        circle(d, s, x, y, sw * 0.8)

def route(d, s, sw):
    line(d, s, [(0.18, 0.78), (0.18, 0.50), (0.50, 0.50), (0.50, 0.22), (0.82, 0.22)], sw)
    circle(d, s, 0.18, 0.80, 0.11)
    circle(d, s, 0.82, 0.22, 0.11)

def sword(d, s, sw):
    line(d, s, [(0.25, 0.75), (0.82, 0.18)], sw * 1.1)
    line(d, s, [(0.18, 0.56), (0.44, 0.82)], sw)
    line(d, s, [(0.15, 0.85), (0.27, 0.73)], sw * 1.2)

def eye(d, s, sw):
    d.arc((0.05 * s, 0.22 * s, 0.95 * s, 1.10 * s), 205, 335, fill=W, width=int(sw * s))
    d.arc((0.05 * s, -0.10 * s, 0.95 * s, 0.78 * s), 25, 155, fill=W, width=int(sw * s))
    circle(d, s, 0.5, 0.5, 0.14)

def bell(d, s, sw):
    d.arc((0.20 * s, 0.15 * s, 0.80 * s, 0.85 * s), 180, 360, fill=W, width=int(sw * s))
    line(d, s, [(0.20, 0.50), (0.20, 0.66), (0.12, 0.74), (0.88, 0.74), (0.80, 0.66), (0.80, 0.50)], sw)
    circle(d, s, 0.5, 0.86, 0.08)

def gear(d, s, sw):
    cx = cy = 0.5
    for i in range(8):
        a = i * math.pi / 4
        line(d, s, [(cx + math.cos(a) * 0.30, cy + math.sin(a) * 0.30),
                    (cx + math.cos(a) * 0.42, cy + math.sin(a) * 0.42)], sw * 1.3)
    circle(d, s, cx, cy, 0.27, sw)
    circle(d, s, cx, cy, 0.09)

def keyboard(d, s, sw):
    d.rounded_rectangle((0.06 * s, 0.24 * s, 0.94 * s, 0.76 * s), radius=0.12 * s, outline=W, width=int(sw * s))
    for y in (0.40,):
        for x in (0.24, 0.40, 0.56, 0.72):
            circle(d, s, x, y, 0.045)
    line(d, s, [(0.30, 0.60), (0.70, 0.60)], sw * 0.9)

def close(d, s, sw):
    line(d, s, [(0.25, 0.25), (0.75, 0.75)], sw * 1.1)
    line(d, s, [(0.75, 0.25), (0.25, 0.75)], sw * 1.1)

def chevron(d, s, sw):
    line(d, s, [(0.26, 0.38), (0.50, 0.62), (0.74, 0.38)], sw * 1.1)

def check(d, s, sw):
    line(d, s, [(0.20, 0.52), (0.42, 0.72), (0.80, 0.30)], sw * 1.2)

def play(d, s, sw):
    poly(d, s, [(0.30, 0.18), (0.30, 0.82), (0.82, 0.50)])

def stop(d, s, sw):
    d.rounded_rectangle((0.24 * s, 0.24 * s, 0.76 * s, 0.76 * s), radius=0.08 * s, fill=W)

def logo(d, s, sw):
    # stylised diamond pick-mark for the header badge
    poly(d, s, [(0.5, 0.08), (0.92, 0.5), (0.5, 0.92), (0.08, 0.5)])
    d.polygon([(0.5 * s, 0.30 * s), (0.70 * s, 0.5 * s), (0.5 * s, 0.70 * s), (0.30 * s, 0.5 * s)], fill=0)

ICONS = dict(pickaxe=pickaxe, route=route, sword=sword, eye=eye, bell=bell, gear=gear,
             keyboard=keyboard, close=close, chevron=chevron, check=check, play=play, stop=stop, logo=logo)

def main():
    OUT.mkdir(parents=True, exist_ok=True)
    for name, fn in ICONS.items():
        for scale in (1, 2, 3, 4):
            px = BASE * scale
            mask, s = canvas(px)
            d = ImageDraw.Draw(mask)
            stroke = 0.12 if scale > 1 else 0.16
            fn(d, s, stroke)
            mask = mask.resize((px, px), Image.LANCZOS)
            img = Image.new("RGBA", (px, px), (255, 255, 255, 0))
            img.putalpha(mask)
            img.save(OUT / f"{name}_{scale}x.png")
    print("wrote", len(ICONS) * 4, "icons to", OUT)

if __name__ == "__main__":
    main()
