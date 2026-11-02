#!/usr/bin/env python3
"""Regenerates app/src/test/resources/fixtures/ (scene photos, derived copies, junk assets, fingerprints.tsv).

The six real-looking scenes come from public/demo; the other six are synthetic. Everything is deterministic.
The fingerprint algorithm here mirrors engine/Fingerprints.kt (integer cell averages, dHash 9x8, aHash 8x8).
"""
import hashlib, io, os, sys
import numpy as np
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.normpath(os.path.join(HERE, "../app/src/test/resources/fixtures"))
DEMO = os.path.normpath(os.path.join(HERE, "../../public/demo"))
os.makedirs(OUT, exist_ok=True)
LEGACY = ("jpeg_", "png_")   # small fixtures for the carver tests, made once with an independent encoder; never regenerated

def exif_bytes(make="Radd Test", model="Cam 1", when="2025:06:01 12:00:00"):
    ex = Image.Exif()
    ex[0x010F] = make
    ex[0x0110] = model
    ex.get_ifd(0x8769)[0x9003] = when
    return ex.tobytes()

def save_jpeg(img, name, q=85, exif=True):
    kw = dict(format="JPEG", quality=q, optimize=False)
    if exif:
        kw["exif"] = exif_bytes()
    img.convert("RGB").save(os.path.join(OUT, name), **kw)

def synthetic(seed, w=640, h=480, noise=3.5):
    rng = np.random.default_rng(seed)
    base = np.zeros((h, w, 3), np.float32)
    for scale, amp in ((6, 90), (16, 45), (40, 20)):
        small = rng.random((scale * 3 // 4 + 2, scale + 2, 3)).astype(np.float32)
        big = Image.fromarray((small * 255).astype(np.uint8)).resize((w, h), Image.BICUBIC)
        base += np.asarray(big, np.float32) / 255.0 * amp
    img = Image.fromarray(np.clip(base + 40, 0, 255).astype(np.uint8))
    d = ImageDraw.Draw(img)
    for _ in range(7):
        x0, y0 = int(rng.integers(0, w - 80)), int(rng.integers(0, h - 80))
        x1, y1 = x0 + int(rng.integers(40, 220)), y0 + int(rng.integers(40, 200))
        col = tuple(int(c) for c in rng.integers(0, 255, 3))
        (d.ellipse if rng.random() < 0.5 else d.rectangle)((x0, y0, x1, y1), fill=col)
    arr = np.asarray(img, np.float32) + rng.normal(0, noise, (h, w, 3))
    return Image.fromarray(np.clip(arr, 0, 255).astype(np.uint8))

scenes = []
for i, n in enumerate(["camera", "compass", "dunes", "harbor", "jasmine", "pomegranate"]):
    im = Image.open(os.path.join(DEMO, n + ".jpg")).convert("RGB").resize((720, 480), Image.LANCZOS)
    scenes.append(im)
for s in range(6):
    scenes.append(synthetic(100 + s, 720, 480))

for i, im in enumerate(scenes, 1):
    save_jpeg(im, f"scene_{i:02d}.jpg", q=82, exif=i <= 10)   # 11 and 12 have no EXIF
# copies the way chat apps and gallery caches make them: smaller, recompressed, no EXIF
for i in (1, 2, 3, 7, 8):
    save_jpeg(scenes[i - 1].resize((480, 320), Image.LANCZOS), f"scene_{i:02d}_wa.jpg", q=72, exif=False)
for i in (1, 2, 7, 4, 9):
    save_jpeg(scenes[i - 1].resize((384, 256), Image.LANCZOS), f"scene_{i:02d}_thumb.jpg", q=78, exif=False)
# large photo without EXIF (counts as a user photo because of its size)
big = synthetic(555, 1600, 1066, noise=0.8)   # a large picture that is not any of the scenes
save_jpeg(big, "big_noexif.jpg", q=80, exif=False)
# junk: small but noisy, wide banner, tiny file, icons
rng = np.random.default_rng(5)
noisy = lambda w, h: Image.fromarray(rng.integers(0, 255, (h, w, 3), dtype=np.uint8))
save_jpeg(noisy(150, 150), "tiny_noisy.jpg", q=90, exif=False)           # >10 KB, under 200 px
save_jpeg(synthetic(77, 1200, 240), "banner.jpg", q=85, exif=False)      # 5:1
grad = np.tile(np.linspace(0, 255, 480, dtype=np.uint8), (360, 1))
save_jpeg(Image.fromarray(np.stack([grad] * 3, axis=2)), "smooth_small.jpg", q=40, exif=False)  # under 10 KB
lowbit = lambda w, h, bits: Image.fromarray(((rng.integers(0, 1 << bits, (h, w, 3), dtype=np.uint8)) * (255 // ((1 << bits) - 1))).astype(np.uint8))
lowbit(96, 96, 2).save(os.path.join(OUT, "icon_96.png"))
lowbit(192, 192, 2).save(os.path.join(OUT, "launcher_192.png"))
Image.fromarray((rng.integers(0, 2, (300, 300), dtype=np.uint8) * 255).astype(np.uint8)).save(os.path.join(OUT, "ui_asset_300.png"))
Image.new("RGB", (400, 400), (30, 120, 90)).save(os.path.join(OUT, "flat_400.png"))

# ---- fingerprints -------------------------------------------------------------------------------
def means(g, w, h, cols, rows):
    out = []
    for r in range(rows):
        y0, y1 = r * h // rows, max(r * h // rows + 1, (r + 1) * h // rows)
        for c in range(cols):
            x0, x1 = c * w // cols, max(c * w // cols + 1, (c + 1) * w // cols)
            blk = g[y0:y1, x0:x1].astype(np.int64)
            out.append(int(blk.sum()) // blk.size)
    return out

def fingerprint(g):
    h, w = g.shape
    d = means(g, w, h, 9, 8)
    a = means(g, w, h, 8, 8)
    dh = 0
    for r in range(8):
        for c in range(8):
            dh = (dh << 1) | (1 if d[r * 9 + c] < d[r * 9 + c + 1] else 0)
    mean = sum(a) // 64
    ah = 0
    for v in a:
        ah = (ah << 1) | (1 if v > mean else 0)
    return dh, ah, max(a) - min(a)

def gray_of(path):
    im = Image.open(path).convert("L")
    im.thumbnail((160, 160), Image.BOX)
    return np.asarray(im, np.uint8), Image.open(path).size

lines = []
for f in sorted(os.listdir(OUT)):
    if not f.endswith((".jpg", ".png")) or f.startswith(LEGACY):
        continue
    p = os.path.join(OUT, f)
    g, (fw, fh) = gray_of(p)
    dh, ah, spread = fingerprint(g)
    sha = hashlib.sha256(open(p, "rb").read()).hexdigest()
    lines.append(f"{sha}\t{f}\t{fw}\t{fh}\t{dh}\t{ah}\t{spread}")
open(os.path.join(OUT, "fingerprints.tsv"), "w").write("\n".join(lines) + "\n")

# known vector for the Kotlin port
w, h = 40, 30
g = np.array([[(x * 7 + y * 13 + (x * y) % 31) % 256 for x in range(w)] for y in range(h)], np.uint8)
print("vector", fingerprint(g))
print("files", len(os.listdir(OUT)), "bytes", sum(os.path.getsize(os.path.join(OUT, f)) for f in os.listdir(OUT)))
