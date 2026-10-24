"""Generates the preset cape textures (cosmetic, client-side only).

Vanilla cape layout on a 64x32 texture, here drawn at 4x (256x128) for detail:
the outer face (seen from behind the player) is the 10x16 box face at (1,1),
the inner face is at (12,1), edges around them. Designs are symmetric so the
model's mirroring never matters. Run: python tools/gen_capes.py
"""
import math
import random
from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter

OUT = Path(__file__).resolve().parents[1] / "src/main/resources/assets/dragsimmacro/textures/cosmetic"
K = 4                       # texels per vanilla pixel
FACE_W, FACE_H = 10 * K, 16 * K
OUTER = (1 * K, 1 * K)
INNER = (12 * K, 1 * K)


def gradient(w, h, top, bottom):
    img = Image.new("RGB", (w, h))
    d = ImageDraw.Draw(img)
    for y in range(h):
        t = y / max(1, h - 1)
        d.line([(0, y), (w, y)], fill=tuple(round(top[i] + (bottom[i] - top[i]) * t) for i in range(3)))
    return img


def _add(base, layer, strength):
    """base + layer * strength, per channel (light adds up)."""
    b = base.load()
    l = layer.load()
    out = base.copy()
    o = out.load()
    for y in range(base.size[1]):
        for x in range(base.size[0]):
            o[x, y] = tuple(min(255, round(b[x, y][i] + l[x, y][i] * strength)) for i in range(3))
    return out


def supersampled(size, draw_fn, ss=8):
    """Mask drawn at ss x and downsampled (anti-aliased)."""
    m = Image.new("L", (size[0] * ss, size[1] * ss), 0)
    draw_fn(ImageDraw.Draw(m), ss)
    return m.resize(size, Image.LANCZOS)


def paste_color(face, mask, color):
    face.paste(Image.new("RGB", face.size, color), (0, 0), mask)


def crystal_mark(d, ss, cx, cy, h, line_w, fill_facet=True):
    """The DragSim End Crystal mark, h tall, centred on (cx, cy) in face texels."""
    def P(u, v):
        return ((cx + (u - 0.5) * h) * ss, (cy + (v - 0.5) * h) * ss)
    top, bot = (0.5, 0.04), (0.5, 0.96)
    left, right, front = (0.10, 0.46), (0.90, 0.46), (0.42, 0.58)
    w = max(1, round(line_w * ss))
    for a, b in [(top, left), (top, right), (bot, left), (bot, right),
                 (top, front), (bot, front), (left, front), (front, right)]:
        d.line([P(*a), P(*b)], fill=255, width=w)
    if fill_facet:
        d.polygon([P(*top), P(*left), P(*front)], fill=255)


# ==================================================================== designs (outer face)

def crystal():
    face = gradient(FACE_W, FACE_H, (28, 20, 44), (10, 8, 16))
    face = _add(face, _blurred_dot(face.size, (20, 24), 14), 0.55)
    mask = supersampled(face.size, lambda d, ss: crystal_mark(d, ss, 20, 24, 24, 1.3))
    paste_color(face, mask, (192, 132, 252))
    return face, (14, 10, 20)


def void():
    rng = random.Random(7)
    face = gradient(FACE_W, FACE_H, (30, 12, 52), (4, 3, 9))
    # faint nebula band
    band = Image.new("RGB", face.size, (0, 0, 0))
    bd = ImageDraw.Draw(band)
    bd.ellipse((-10, 18, 50, 40), fill=(90, 40, 140))
    face = _add(face, band.filter(ImageFilter.GaussianBlur(7)), 0.45)
    stars = supersampled(face.size, lambda d, ss: [
        d.ellipse(((x - r) * ss, (y - r) * ss, (x + r) * ss, (y + r) * ss), fill=255)
        for x, y, r in [(rng.uniform(2, 38), rng.uniform(2, 62), rng.choice((0.3, 0.3, 0.3, 0.45, 0.9))) for _ in range(22)]])
    paste_color(face, stars, (236, 228, 255))
    return face, (8, 5, 14)


def crying_obsidian():
    rng = random.Random(3)
    face = Image.new("RGB", (FACE_W, FACE_H))
    d = ImageDraw.Draw(face)
    for y in range(0, FACE_H, K):           # obsidian: 1-pixel blocks of near-black violet, a few lit flecks
        for x in range(0, FACE_W, K):
            v = rng.randint(0, 12)
            c = (16 + v, 10 + v // 2, 28 + v)
            if rng.random() < 0.07:
                c = (60, 24, 96)
            d.rectangle((x, y, x + K - 1, y + K - 1), fill=c)
    tears = Image.new("RGB", face.size, (0, 0, 0))
    td = ImageDraw.Draw(tears)
    # short, uneven drips from the top edge, each ending in a bead
    for x, length in ((7, 14), (12, 25), (20, 9), (27, 19), (33, 12)):
        td.line([(x, 0), (x, length)], fill=(170, 50, 230), width=1)
        td.ellipse((x - 1.5, length - 1, x + 1.5, length + 2), fill=(225, 110, 255))
    face = _add(face, tears.filter(ImageFilter.GaussianBlur(1.6)), 0.8)
    face = _add(face, tears, 0.7)
    return face, (12, 8, 20)


def ender():
    rng = random.Random(11)
    face = gradient(FACE_W, FACE_H, (8, 26, 30), (3, 7, 10))
    face = _add(face, _blurred_dot(face.size, (20, 22), 20, (30, 150, 135)), 0.5)
    # an ender pearl: dark rim, lighter body, a darker swirl at the centre, a highlight
    rings = [((20, 22), 9.0, (12, 70, 64)), ((20, 22), 7.6, (30, 125, 110)),
             ((20, 22), 4.2, (14, 82, 74)), ((17.5, 19.5), 1.6, (150, 240, 220))]
    for (cx, cy), r, col in rings:
        m = supersampled(face.size, lambda d, ss, cx=cx, cy=cy, r=r:
                         d.ellipse(((cx - r) * ss, (cy - r) * ss, (cx + r) * ss, (cy + r) * ss), fill=255))
        paste_color(face, m, col)
    # ender particles drifting down from it
    specks = supersampled(face.size, lambda d, ss: [
        d.rectangle((x * ss, y * ss, (x + 0.75) * ss, (y + 0.75) * ss), fill=255)
        for x, y in [(20 + rng.gauss(0, 6), rng.uniform(34, 60)) for _ in range(9)]])
    paste_color(face, specks, (150, 80, 210))
    return face, (5, 14, 16)


def _blurred_dot(size, center, radius, color=(150, 80, 230)):
    layer = Image.new("RGB", size, (0, 0, 0))
    d = ImageDraw.Draw(layer)
    cx, cy = center
    d.ellipse((cx - radius / 2, cy - radius / 2, cx + radius / 2, cy + radius / 2), fill=color)
    return layer.filter(ImageFilter.GaussianBlur(radius * 0.55))


DESIGNS = dict(crystal=crystal, void=void, crying_obsidian=crying_obsidian, ender=ender)


def build(face, edge):
    tex = Image.new("RGBA", (64 * K, 32 * K), (0, 0, 0, 0))
    d = ImageDraw.Draw(tex)
    # whole cape box region in the edge colour (top, bottom, sides, inner face)
    d.rectangle((0, 0, 22 * K - 1, 17 * K - 1), fill=edge + (255,))
    tex.paste(face, OUTER)
    inner = Image.blend(face, Image.new("RGB", face.size, edge), 0.75)
    tex.paste(inner, INNER)
    return tex


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    for name, fn in DESIGNS.items():
        face, edge = fn()
        build(face, edge).save(OUT / f"cape_{name}.png")
    print("wrote", len(DESIGNS), "capes to", OUT)


if __name__ == "__main__":
    main()
