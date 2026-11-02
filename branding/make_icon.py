import sys, numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageChops
src, out = sys.argv[1], sys.argv[2]
STYLE = sys.argv[3] if len(sys.argv) > 3 else 'eq'  # 'eq' atau 'wave'
L = np.array(Image.open(src).convert('L')).astype(float)
a = np.clip((235 - L) / (235 - 60), 0, 1) * 255
mask = Image.fromarray(a.astype(np.uint8)).crop((355, 398, 1120, 1053))
mask.save(f'{out}/b_mask.png')

N = 2048  # kerja di resolusi besar lalu diperkecil (anti-alias)
GREEN_TOP, GREEN_BOT = (40, 232, 116), (8, 150, 70)

def gradient(size, top, bot):
    w, h = size
    t = np.linspace(0, 1, h)[:, None, None]
    arr = (np.array(top) * (1 - t) + np.array(bot) * t) * np.ones((h, w, 1))
    return Image.fromarray(arr.astype(np.uint8), 'RGB')

def mark(canvas, scale):
    """B hijau + elemen musik (STYLE); total lebar = scale * canvas."""
    img = Image.new('RGBA', (canvas, canvas), (0, 0, 0, 0))
    total_w = int(canvas * scale)
    bw = int(total_w * 0.78)
    bh = int(bw * mask.height / mask.width)
    m = mask.resize((bw, bh), Image.LANCZOS)
    x0 = (canvas - total_w) // 2
    y0 = (canvas - bh) // 2
    fill = gradient((bw, bh), GREEN_TOP, GREEN_BOT).convert('RGBA')
    fill.putalpha(m)
    img.alpha_composite(fill, (x0, y0))
    d = ImageDraw.Draw(img)
    if STYLE == 'wave':
        # gelombang suara di kanan atas B (pembeda: B "bersuara")
        cx, cy = x0 + int(bw * 0.80), y0 + int(bh * 0.17)
        stroke = max(2, int(bw * 0.045))
        for i, r in enumerate([0.16, 0.25, 0.34]):
            rr = int(bw * r)
            alpha = [255, 200, 140][i]
            d.arc((cx - rr, cy - rr, cx + rr, cy + rr), start=-62, end=-8,
                  fill=(40, 232, 116, alpha), width=stroke)
    else:
        # equalizer: 4 bar dengan tinggi berbeda, sejajar dasar di kanan atas B
        bar_w, gap = bw * 0.058, bw * 0.032
        base_y = y0 + bh * 0.40
        left = x0 + bw * 0.93
        for i, h in enumerate([0.20, 0.38, 0.27, 0.14]):
            bx = left + i * (bar_w + gap)
            top = base_y - bh * h
            bar = gradient((int(bar_w), int(base_y - top)), GREEN_TOP,
                           GREEN_BOT).convert('RGBA')
            bm = Image.new('L', bar.size, 0)
            ImageDraw.Draw(bm).rounded_rectangle(
                (0, 0, bar.size[0] - 1, bar.size[1] - 1), bar_w / 2, fill=255)
            bar.putalpha(bm)
            img.alpha_composite(bar, (int(bx), int(top)))
    return img

def background(canvas):
    bg = gradient((canvas, canvas), (34, 34, 34), (10, 10, 10)).convert('RGBA')
    glow = Image.new('L', (canvas, canvas), 0)
    ImageDraw.Draw(glow).ellipse((canvas*0.15, canvas*0.1, canvas*0.85, canvas*0.8), fill=70)
    glow = glow.filter(ImageFilter.GaussianBlur(canvas * 0.12))
    green = Image.new('RGBA', (canvas, canvas), (30, 215, 96, 255)); green.putalpha(glow)
    bg.alpha_composite(green)
    return bg

# 1) ikon penuh (legacy Android & iOS): latar + mark
full = background(N); full.alpha_composite(mark(N, 0.70))
full.convert('RGB').save(f'{out}/icon_full.png')
# 2) adaptive: foreground transparan (zona aman 66/108) + background terpisah
mark(N, 0.52).save(f'{out}/adaptive_fg.png')
background(N).convert('RGB').save(f'{out}/adaptive_bg.png')
# 3) siluet putih untuk notifikasi (Android wajib monokrom)
mono = mark(N, 0.86); r, g, b, al = mono.split()
white = Image.new('RGBA', (N, N), (255, 255, 255, 255)); white.putalpha(al)
white.save(f'{out}/notification_mono.png')
# pratinjau: rounded + bulat + notifikasi
prev = Image.new('RGB', (1500, 560), (245, 245, 245))
def rounded(img, size, radius):
    im = img.resize((size, size), Image.LANCZOS).convert('RGBA')
    m = Image.new('L', (size, size), 0); ImageDraw.Draw(m).rounded_rectangle((0, 0, size, size), radius, fill=255)
    im.putalpha(m); return im
src_icon = Image.open(src).crop((255, 250, 1195, 1190))
prev.paste(rounded(src_icon, 420, 90), (50, 70), rounded(src_icon, 420, 90))
ic = rounded(full, 420, 90); prev.paste(ic, (540, 70), ic)
circ = full.resize((420, 420), Image.LANCZOS); cm = Image.new('L', (420, 420), 0); ImageDraw.Draw(cm).ellipse((0, 0, 420, 420), fill=255)
prev.paste(circ, (1030, 70), cm)
d = ImageDraw.Draw(prev)
for x, t in [(50, 'Induk (logo Anda)'), (540, 'Bytify - rounded'), (1030, 'Bytify - bulat (Android)')]:
    d.text((x, 510), t, fill=(30, 30, 30))
prev.save(f'{out}/preview.png')
print('ok')
