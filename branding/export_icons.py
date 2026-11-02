import json, os, sys
from PIL import Image, ImageDraw
src, proj = sys.argv[1], sys.argv[2]
full = Image.open(f'{src}/icon_full.png').convert('RGBA')
fg = Image.open(f'{src}/adaptive_fg.png').convert('RGBA')
bg = Image.open(f'{src}/adaptive_bg.png').convert('RGB')
notif = Image.open(f'{src}/notification_mono.png').convert('RGBA')
mono = Image.new('RGBA', fg.size, (255, 255, 255, 255)); mono.putalpha(fg.split()[3])

res = f'{proj}/android/app/src/main/res'
dens = {'mdpi': 1, 'hdpi': 1.5, 'xhdpi': 2, 'xxhdpi': 3, 'xxxhdpi': 4}
def rounded(img, size):
    im = img.resize((size, size), Image.LANCZOS)
    m = Image.new('L', (size * 4, size * 4), 0)
    ImageDraw.Draw(m).rounded_rectangle((0, 0, size * 4, size * 4), size * 4 * 0.22, fill=255)
    im.putalpha(m.resize((size, size), Image.LANCZOS)); return im
def circle(img, size):
    im = img.resize((size, size), Image.LANCZOS)
    m = Image.new('L', (size * 4, size * 4), 0)
    ImageDraw.Draw(m).ellipse((0, 0, size * 4, size * 4), fill=255)
    im.putalpha(m.resize((size, size), Image.LANCZOS)); return im
for d, k in dens.items():
    mip = f'{res}/mipmap-{d}'; os.makedirs(mip, exist_ok=True)
    rounded(full, int(48 * k)).save(f'{mip}/ic_launcher.png')
    circle(full, int(48 * k)).save(f'{mip}/ic_launcher_round.png')
    s = int(108 * k)
    fg.resize((s, s), Image.LANCZOS).save(f'{mip}/ic_launcher_foreground.png')
    bg.resize((s, s), Image.LANCZOS).save(f'{mip}/ic_launcher_background.png')
    mono.resize((s, s), Image.LANCZOS).save(f'{mip}/ic_launcher_monochrome.png')
    dr = f'{res}/drawable-{d}'; os.makedirs(dr, exist_ok=True)
    notif.resize((int(24 * k), int(24 * k)), Image.LANCZOS).save(f'{dr}/ic_stat_bytify.png')
os.makedirs(f'{res}/mipmap-anydpi-v26', exist_ok=True)
xml = '''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@mipmap/ic_launcher_background"/>
    <foreground android:drawable="@mipmap/ic_launcher_foreground"/>
    <monochrome android:drawable="@mipmap/ic_launcher_monochrome"/>
</adaptive-icon>
'''
for name in ('ic_launcher', 'ic_launcher_round'):
    open(f'{res}/mipmap-anydpi-v26/{name}.xml', 'w').write(xml)

ios = f'{proj}/ios/Runner/Assets.xcassets/AppIcon.appiconset'
meta = json.load(open(f'{ios}/Contents.json'))
square = full.convert('RGB')  # iOS: tanpa alpha, sudut dibulatkan oleh sistem
n = 0
for img in meta['images']:
    if 'filename' not in img: continue
    pt = float(img['size'].split('x')[0]); sc = int(img['scale'].rstrip('x'))
    px = round(pt * sc)
    square.resize((px, px), Image.LANCZOS).save(f"{ios}/{img['filename']}"); n += 1
full.convert('RGB').resize((1024, 1024), Image.LANCZOS).save(f'{proj}/assets/branding/bytify_icon_1024.png') if os.path.isdir(f'{proj}/assets/branding') else None
print('ios icons:', n)
