"""Logo Bytify gaya cel-shaded (ilustrasi anime): bentuk B dari logo induk,
garis tebal navy, isi soft gold dengan bayangan & kilau, latar navy/beige.

Pemakaian: python make_icon_cel.py <logo_induk.jpg> <folder_output>
"""
import sys

import numpy as np
from PIL import Image, ImageChops, ImageDraw, ImageFilter

SRC, OUT = sys.argv[1], sys.argv[2]

NAVY = (31, 42, 68)          # #1F2A44
NAVY_DEEP = (20, 28, 47)
BEIGE = (232, 220, 200)      # #E8DCC8
GOLD = (198, 167, 94)        # #C6A75E
GOLD_SHADE = (150, 122, 58)
GOLD_LIGHT = (240, 222, 168)

N = 2048

# --- Bentuk B dari logo induk (gambar 2) ---
L = np.array(Image.open(SRC).convert("L")).astype(float)
alpha = np.clip((235 - L) / (235 - 60), 0, 1) * 255
B_MASK = Image.fromarray(alpha.astype(np.uint8)).crop((355, 398, 1120, 1053))


def shifted(mask, dx, dy):
    return ImageChops.offset(mask, dx, dy)


def solid(color, size, a=255):
    return Image.new("RGBA", size, color + (a,))


def grain(size, strength):
    """Tekstur kertas halus (noise) agar tidak terasa datar/digital."""
    rng = np.random.default_rng(7)
    noise = rng.normal(128, 40, (size[1], size[0])).clip(0, 255).astype(np.uint8)
    n = Image.fromarray(noise, "L").filter(ImageFilter.GaussianBlur(0.6))
    layer = Image.new("RGBA", size, (255, 255, 255, 0))
    layer.putalpha(n.point(lambda v: int(abs(v - 128) / 128 * strength)))
    return layer


def mark(canvas, scale):
    """B cel-shaded di kanvas transparan; lebar B = scale * kanvas."""
    img = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
    bw = int(canvas * scale)
    bh = int(bw * B_MASK.height / B_MASK.width)
    x0, y0 = (canvas - bw) // 2, (canvas - bh) // 2
    m = Image.new("L", (canvas, canvas), 0)
    m.paste(B_MASK.resize((bw, bh), Image.LANCZOS), (x0, y0))
    m = m.point(lambda v: 255 if v > 110 else 0).filter(ImageFilter.GaussianBlur(1.2))

    unit = max(2, canvas // 256)
    outline_w = unit * 7

    # Bayangan keras (gaya stiker/komik) di belakang.
    drop = m.filter(ImageFilter.MaxFilter(outline_w | 1))
    drop = shifted(drop, unit * 6, unit * 7)
    sh = solid(NAVY_DEEP, img.size)
    sh.putalpha(drop.point(lambda v: int(v * 0.55)))
    img.alpha_composite(sh)

    # Garis luar tebal navy.
    outline = m.filter(ImageFilter.MaxFilter(outline_w | 1))
    ol = solid(NAVY, img.size)
    ol.putalpha(outline)
    img.alpha_composite(ol)

    # Isi dasar gold.
    base = solid(GOLD, img.size)
    base.putalpha(m)
    img.alpha_composite(base)

    # Cel shading: sisi kanan-bawah lebih gelap (batas tegas, tanpa gradasi).
    shade = ImageChops.subtract(m, shifted(m, -unit * 9, -unit * 9))
    s = solid(GOLD_SHADE, img.size)
    s.putalpha(shade)
    img.alpha_composite(s)

    # Kilau kiri-atas (rim light).
    rim = ImageChops.subtract(m, shifted(m, unit * 5, unit * 5))
    r = solid(GOLD_LIGHT, img.size)
    r.putalpha(rim)
    img.alpha_composite(r)

    # Kilau diagonal seperti botol plastik pada gambar 1.
    streak = Image.new("L", img.size, 0)
    d = ImageDraw.Draw(streak)
    for off, width, a in ((0.18, 0.05, 210), (0.27, 0.018, 170)):
        cx = x0 + bw * off
        d.polygon(
            [(cx, y0 - 10), (cx + bw * width, y0 - 10),
             (cx + bw * width - bh * 0.55, y0 + bh + 10), (cx - bh * 0.55, y0 + bh + 10)],
            fill=a,
        )
    streak = ImageChops.multiply(streak, m.filter(ImageFilter.MinFilter(unit * 3 | 1)))
    st = solid((255, 248, 228), img.size)
    st.putalpha(streak.point(lambda v: int(v * 0.55)))
    img.alpha_composite(st)

    # Garis dalam tipis agar bentuk tetap tegas di ukuran kecil.
    inner = ImageChops.subtract(m, m.filter(ImageFilter.MinFilter(unit * 2 | 1)))
    il = solid(NAVY, img.size)
    il.putalpha(inner.point(lambda v: int(v * 0.6)))
    img.alpha_composite(il)
    return img


def background(canvas, color, accent):
    """Latar dengan lingkaran cahaya lembut + tekstur kertas."""
    bg = solid(color, (canvas, canvas))
    glow = Image.new("L", (canvas, canvas), 0)
    ImageDraw.Draw(glow).ellipse((canvas * 0.12, canvas * 0.08, canvas * 0.88, canvas * 0.84), fill=90)
    glow = glow.filter(ImageFilter.GaussianBlur(canvas * 0.13))
    g = solid(accent, (canvas, canvas))
    g.putalpha(glow)
    bg.alpha_composite(g)
    bg.alpha_composite(grain((canvas, canvas), 26))
    return bg


# Varian utama: latar navy (kontras kuat di launcher).
navy_full = background(N, NAVY, (58, 74, 112))
navy_full.alpha_composite(mark(N, 0.62))
navy_full.convert("RGB").save(f"{OUT}/icon_full.png")
background(N, NAVY, (58, 74, 112)).convert("RGB").save(f"{OUT}/adaptive_bg.png")
mark(N, 0.46).save(f"{OUT}/adaptive_fg.png")

# Varian beige (untuk pratinjau perbandingan).
beige_full = background(N, BEIGE, (250, 240, 220))
beige_full.alpha_composite(mark(N, 0.62))
beige_full.convert("RGB").save(f"{OUT}/icon_full_beige.png")

# Siluet putih untuk notifikasi (Android wajib monokrom).
mono_src = mark(N, 0.86).split()[3]
white = Image.new("RGBA", (N, N), (255, 255, 255, 255))
white.putalpha(mono_src.point(lambda v: 255 if v > 40 else 0))
white.save(f"{OUT}/notification_mono.png")

# Pratinjau.
def rounded(img, size, radius):
    im = img.resize((size, size), Image.LANCZOS).convert("RGBA")
    m = Image.new("L", (size, size), 0)
    ImageDraw.Draw(m).rounded_rectangle((0, 0, size, size), radius, fill=255)
    im.putalpha(m)
    return im


prev = Image.new("RGB", (1500, 560), BEIGE)
src_icon = Image.open(SRC).crop((255, 250, 1195, 1190))
for x, im in ((50, src_icon), (540, navy_full), (1030, beige_full)):
    r = rounded(im, 420, 90)
    prev.paste(r, (x, 50), r)
d = ImageDraw.Draw(prev)
for x, t in ((50, "Induk (gambar 2)"), (540, "Bytify - navy (utama)"), (1030, "Bytify - beige")):
    d.text((x, 500), t, fill=NAVY)
prev.save(f"{OUT}/preview_cel.png")
print("ok")
