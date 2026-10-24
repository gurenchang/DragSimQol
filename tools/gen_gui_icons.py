"""Generates the GUI icon textures (white on transparent, tinted in game).

One PNG per icon per GUI scale (1x..4x of a 10-unit icon), drawn at 8x and
downsampled, so icons are crisp and anti-aliased at every Minecraft GUI scale.
Run: python tools/gen_gui_icons.py
"""
import math
from pathlib import Path
from PIL import Image, ImageDraw

OUT = Path(__file__).resolve().parents[1] / "src/main/resources/assets/dragsimmacro/textures/gui/icons"
BASE = 10          # icon size in GUI units (per-icon override in SIZES)
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

# Nav icons (pickaxe, route, sword, eye, bell, gear) use slightly thinner strokes than the UI glyphs.
NAV_STROKE = 0.84

def P(s, q):
    return (q[0] * s, q[1] * s)

def stroke(d, s, pts, w):
    """Polyline with round joins and round dots at every vertex."""
    d.line([P(s, q) for q in pts], fill=W, width=max(1, round(w * s)), joint="curve")
    r = w * s / 2
    for q in pts:
        x, y = P(s, q)
        d.ellipse((x - r, y - r, x + r, y + r), fill=W)

def pickaxe(d, s, sw):
    w = sw * NAV_STROKE
    # handle on the diagonal; a broad crescent head across its top, arching away from the handle
    hx0, hy0, hx1, hy1 = 0.14, 0.88, 0.62, 0.40
    stroke(d, s, [(hx0, hy0), (hx1, hy1)], w * 1.1)
    ux, uy = (hx1 - hx0), (hy1 - hy0)
    n = math.hypot(ux, uy)
    ux, uy = ux / n, uy / n
    px, py = -uy, ux
    cx, cy = hx1 - ux * 0.02, hy1 - uy * 0.02
    half, bow = 0.47, 0.17
    outer, inner = [], []
    for i in range(61):
        t = -1 + 2 * i / 60
        thick = 0.095 * (1 - t * t) ** 0.55 + 0.006
        arch = bow * (1 - t * t)
        bx = cx + px * t * half + ux * arch
        by = cy + py * t * half + uy * arch
        outer.append((bx + ux * thick, by + uy * thick))
        inner.append((bx - ux * thick, by - uy * thick))
    d.polygon([P(s, q) for q in outer + inner[::-1]], fill=W)

def route(d, s, sw):
    w = sw * NAV_STROKE
    # waypoints: hollow start, filled diamond goal, path with one bend
    stroke(d, s, [(0.24, 0.76), (0.24, 0.50), (0.50, 0.50), (0.76, 0.24)], w * 0.9)
    r = 0.12
    d.ellipse(P(s, (0.24 - r, 0.76 - r)) + P(s, (0.24 + r, 0.76 + r)), fill=W)
    hole = max(0.025, r - w)  # at 1x the stroke is nearly the radius: keep a pinhole
    d.ellipse(P(s, (0.24 - hole, 0.76 - hole)) + P(s, (0.24 + hole, 0.76 + hole)), fill=0)
    k = 0.15
    d.polygon([P(s, q) for q in [(0.76, 0.24 - k), (0.76 + k, 0.24), (0.76, 0.24 + k), (0.76 - k, 0.24)]], fill=W)

def sword(d, s, sw):
    w = sw * NAV_STROKE
    # tapered blade with a pointed tip, short guard, grip and pommel
    tip, base = (0.88, 0.12), (0.36, 0.64)
    ux, uy = tip[0] - base[0], tip[1] - base[1]
    n = math.hypot(ux, uy)
    ux, uy = ux / n, uy / n
    px, py = -uy, ux
    half = 0.08
    blade = [(base[0] + px * half, base[1] + py * half),
             (tip[0] - ux * 0.12 + px * half * 0.8, tip[1] - uy * 0.12 + py * half * 0.8),
             tip,
             (tip[0] - ux * 0.12 - px * half * 0.8, tip[1] - uy * 0.12 - py * half * 0.8),
             (base[0] - px * half, base[1] - py * half)]
    d.polygon([P(s, q) for q in blade], fill=W)
    g = 0.19
    stroke(d, s, [(base[0] + px * g, base[1] + py * g), (base[0] - px * g, base[1] - py * g)], w)
    stroke(d, s, [base, (base[0] - ux * 0.20, base[1] - uy * 0.20)], w)
    ex, ey = base[0] - ux * 0.26, base[1] - uy * 0.26
    r = 0.06
    d.ellipse((P(s, (ex - r, ey - r)) + P(s, (ex + r, ey + r))), fill=W)

def eye(d, s, sw):
    w = sw * NAV_STROKE
    # thin almond outline with a diamond pupil (echoes the crystal)
    pts = []
    for i in range(0, 361, 4):
        t = math.radians(i)
        v = math.sin(t)
        pts.append((0.5 - 0.44 * math.cos(t), 0.5 - 0.26 * math.copysign(abs(v) ** 1.3, v)))
    d.line([P(s, q) for q in pts], fill=W, width=max(1, round(w * s)), joint="curve")
    k = 0.13
    d.polygon([P(s, q) for q in [(0.5, 0.5 - k), (0.5 + k, 0.5), (0.5, 0.5 + k), (0.5 - k, 0.5)]], fill=W)

def bell(d, s, sw):
    w = sw * NAV_STROKE
    # bell outline: dome into a flared rim, clapper below
    pts = []
    for i in range(0, 181, 6):
        t = math.radians(180 + i)
        pts.append((0.5 + 0.24 * math.cos(t), 0.42 + 0.26 * math.sin(t)))
    body = [(0.18, 0.72), (0.26, 0.60), (0.26, 0.42)] + pts + [(0.74, 0.42), (0.74, 0.60), (0.82, 0.72), (0.18, 0.72)]
    d.line([P(s, q) for q in body], fill=W, width=max(1, round(w * s)), joint="curve")
    stroke(d, s, [(0.44, 0.84), (0.56, 0.84)], w * 1.2)

def gear(d, s, sw):
    w = sw * NAV_STROKE
    # "Advanced": three slider rails with knobs at different positions
    for y, k in ((0.24, 0.66), (0.50, 0.34), (0.76, 0.56)):
        stroke(d, s, [(0.12, y), (0.88, y)], w * 0.8)
        r = 0.10
        d.ellipse(P(s, (k - r, y - r)) + P(s, (k + r, y + r)), fill=W)

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
    # brand mark: an End Crystal (octahedron seen slightly from above), outlined,
    # with the upper-left front facet filled so it still reads as a crystal at 1x
    top, bot = (0.5, 0.04), (0.5, 0.96)
    left, right, front = (0.10, 0.46), (0.90, 0.46), (0.42, 0.58)
    P = lambda q: (q[0] * s, q[1] * s)
    w = max(1, int(0.075 * s))
    for a, b in [(top, left), (top, right), (bot, left), (bot, right),
                 (top, front), (bot, front), (left, front), (front, right)]:
        d.line([P(a), P(b)], fill=W, width=w)
    d.polygon([P(top), P(left), P(front)], fill=W)

SIZES = dict(logo=16)

ICONS = dict(pickaxe=pickaxe, route=route, sword=sword, eye=eye, bell=bell, gear=gear,
             keyboard=keyboard, close=close, chevron=chevron, check=check, play=play, stop=stop, logo=logo)

def main():
    OUT.mkdir(parents=True, exist_ok=True)
    for name, fn in ICONS.items():
        for scale in (1, 2, 3, 4):
            px = SIZES.get(name, BASE) * scale
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
