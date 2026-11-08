"""Generate every Android branding asset of Cigram from the cleaned master logo.

usage: make_assets.py <logo_dir with logo_master.png, mark_white.png, mark_mono.png, founder.jpg> <res dir> <fonts dir> <preview dir> [admin]
"""
import os
import sys
import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

LOGO, RES, FONTS, PREV = sys.argv[1:5]
ADMIN = len(sys.argv) > 5 and sys.argv[5] == "admin"
os.makedirs(PREV, exist_ok=True)

master = Image.open(os.path.join(LOGO, "logo_master.png")).convert("RGBA")
mark = Image.open(os.path.join(LOGO, "mark_white.png")).convert("RGBA")
mono = Image.open(os.path.join(LOGO, "mark_mono.png")).convert("RGBA")

# tight crops
master = master.crop(master.getbbox())
mark = mark.crop(mark.getbbox())
mono = mono.crop(mono.getbbox())
# square-pad the master (it is 993 x 1007): pad, never stretch
def square_pad(img):
    s = max(img.size)
    out = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    out.paste(img, ((s - img.width) // 2, (s - img.height) // 2), img)
    return out
master = square_pad(master)
mark = square_pad(mark)
mono = square_pad(mono)

DENS = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}


def save(img, *parts):
    path = os.path.join(RES, *parts)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path, optimize=True)
    return path


def fit(img, size):
    return img.resize((size, size), Image.LANCZOS)


def place(canvas_px, img, frac, dy=0.0):
    """img centred on a transparent square canvas, occupying frac of its width."""
    c = Image.new("RGBA", (canvas_px, canvas_px), (0, 0, 0, 0))
    s = max(1, int(round(canvas_px * frac)))
    r = fit(img, s)
    c.alpha_composite(r, ((canvas_px - s) // 2, (canvas_px - s) // 2 + int(dy * canvas_px)))
    return c


def tint(img, rgb):
    a = np.asarray(img).copy()
    a[..., 0], a[..., 1], a[..., 2] = rgb
    return Image.fromarray(a, "RGBA")


def gradient(size, stops, angle_vertical=True, radial=False, center=(0.5, 0.35)):
    """stops: list of (pos 0..1, (r,g,b))"""
    w = h = size
    if radial:
        yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
        t = np.sqrt(((xx / w) - center[0]) ** 2 + ((yy / h) - center[1]) ** 2) / 0.85
    else:
        t = np.repeat(np.linspace(0, 1, h, dtype=np.float32)[:, None], w, axis=1)
    t = np.clip(t, 0, 1)
    out = np.zeros((h, w, 3), np.float32)
    pos = [p for p, _ in stops]
    cols = np.array([c for _, c in stops], np.float32)
    for ch in range(3):
        out[..., ch] = np.interp(t, pos, cols[:, ch])
    # tiny dither so the gradient never bands on 8-bit panels
    out += np.random.default_rng(7).uniform(-0.6, 0.6, out.shape)
    return Image.fromarray(np.clip(out, 0, 255).astype(np.uint8), "RGB").convert("RGBA")


def squircle_mask(size, n=5.0, inset=0.0):
    yy, xx = np.mgrid[0:size * 4, 0:size * 4].astype(np.float32)
    c = size * 2.0
    r = c * (1 - inset)
    v = np.abs((xx - c + 0.5) / r) ** n + np.abs((yy - c + 0.5) / r) ** n
    m = (v <= 1).astype(np.float32)
    m = Image.fromarray((m * 255).astype(np.uint8), "L").resize((size, size), Image.LANCZOS)
    return m


def circle_mask(size, inset=0.0):
    big = Image.new("L", (size * 4, size * 4), 0)
    d = ImageDraw.Draw(big)
    p = int(size * 4 * inset)
    d.ellipse((p, p, size * 4 - p - 1, size * 4 - p - 1), fill=255)
    return big.resize((size, size), Image.LANCZOS)


# ---------------------------------------------------------------- icon variants (Cigram Plus collection)
# Every variant = the same mark on a different background. Classic = the supplied logo's own charcoal.
CHARCOAL = [(0.0, (66, 67, 67)), (0.45, (18, 19, 19)), (1.0, (2, 2, 2))]
VARIANTS = {
    "": dict(bg=CHARCOAL, mark=(255, 255, 255)),
    "_gold": dict(bg=[(0.0, (46, 38, 22)), (0.5, (16, 13, 8)), (1.0, (3, 3, 2))], mark=(232, 196, 120),
                  glow=(232, 196, 120)),
    "_neon": dict(bg=[(0.0, (14, 22, 52)), (0.55, (6, 8, 24)), (1.0, (2, 2, 8))], mark=(120, 214, 255),
                  glow=(80, 160, 255)),
    "_minimal": dict(bg=[(0.0, (250, 250, 250)), (1.0, (232, 233, 236))], mark=(16, 16, 18), dark_mark=True),
    "_cinema": dict(bg=[(0.0, (150, 18, 26)), (0.55, (70, 8, 14)), (1.0, (24, 3, 6))], mark=(255, 255, 255),
                    radial=True),
    "_glass": dict(bg=[(0.0, (120, 132, 150)), (0.5, (52, 60, 74)), (1.0, (20, 24, 32))], mark=(255, 255, 255),
                   glass=True),
}


def variant_mark(v, size):
    """the mark recoloured for a variant (keeps the grey eye highlight as partial alpha)."""
    m = tint(mark, v["mark"])
    m = fit(m, size)
    if v.get("glow"):
        g = tint(fit(mark, size), v["glow"]).filter(ImageFilter.GaussianBlur(size * 0.035))
        a = np.asarray(g).astype(np.float32)
        a[..., 3] *= 0.75
        g = Image.fromarray(a.astype(np.uint8), "RGBA")
        out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        out.alpha_composite(g)
        out.alpha_composite(m)
        return out
    return m


def variant_bg(v, size):
    bg = gradient(size, v["bg"], radial=v.get("radial", False))
    if v.get("glass"):
        # soft diagonal sheen
        sheen = Image.new("L", (size, size), 0)
        d = ImageDraw.Draw(sheen)
        d.polygon([(0, 0), (size * 0.62, 0), (0, size * 0.62)], fill=38)
        sheen = sheen.filter(ImageFilter.GaussianBlur(size * 0.06))
        white = Image.new("RGBA", (size, size), (255, 255, 255, 0))
        white.putalpha(sheen)
        bg.alpha_composite(white)
    return bg


MARK_FRAC_ADAPTIVE = 0.40   # of the 108 dp canvas (≈ 59 % of the 72 dp visible area, inside the 66 dp safe zone)
MARK_FRAC_LEGACY = 0.54     # of the visible icon (same proportion as the supplied logo: 536 / 993)

for suffix, v in VARIANTS.items():
    name = "ic_launcher" + suffix
    # adaptive layers, 432 px = 108 dp at xxxhdpi, nodpi so every density uses the same crisp source
    save(variant_bg(v, 432), "drawable-nodpi", name + "_bg.png")
    fg = Image.new("RGBA", (432, 432), (0, 0, 0, 0))
    s = int(432 * MARK_FRAC_ADAPTIVE)
    fg.alpha_composite(variant_mark(v, s), ((432 - s) // 2, (432 - s) // 2))
    save(fg, "drawable-nodpi", name + "_fg.png")
    # legacy (pre-Android 8) square-ish icon + round icon at every density
    for dens, k in DENS.items():
        px = int(round(48 * k))
        art = int(round(px * 4))
        body = variant_bg(v, art)
        mk = variant_mark(v, int(art * MARK_FRAC_LEGACY))
        body.alpha_composite(mk, ((art - mk.width) // 2, (art - mk.height) // 2))
        if suffix == "":
            # classic: the supplied logo itself (its own squircle, bevel and gradient), padded 1 dp
            sq = Image.new("RGBA", (art, art), (0, 0, 0, 0))
            inner = int(art * 0.94)
            sq.alpha_composite(fit(master, inner), ((art - inner) // 2, (art - inner) // 2))
        else:
            m = squircle_mask(art, inset=0.03)
            sq = Image.new("RGBA", (art, art), (0, 0, 0, 0))
            sq.paste(body, (0, 0), m)
        save(sq.resize((px, px), Image.LANCZOS), "mipmap-" + dens, name + ".png")
        rnd = Image.new("RGBA", (art, art), (0, 0, 0, 0))
        rnd.paste(body, (0, 0), circle_mask(art, inset=0.02))
        save(rnd.resize((px, px), Image.LANCZOS), "mipmap-" + dens, name + "_round.png")

# themed (Android 13) monochrome layer, shared by every variant
mono_fg = Image.new("RGBA", (432, 432), (0, 0, 0, 0))
s = int(432 * MARK_FRAC_ADAPTIVE)
mono_fg.alpha_composite(fit(mono, s), ((432 - s) // 2, (432 - s) // 2))
save(mono_fg, "drawable-nodpi", "ic_launcher_mono.png")

# ---------------------------------------------------------------- notification small icon (24 dp, white silhouette)
for dens, k in DENS.items():
    px = int(round(24 * k))
    big = px * 8
    c = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    s = int(big * 22 / 24)          # 1 dp padding each side
    c.alpha_composite(fit(mono, s), ((big - s) // 2, (big - s) // 2))
    out = c.resize((px, px), Image.LANCZOS)
    # notification icons are alpha-only: force pure white RGB
    a = np.asarray(out).copy()
    a[..., 0:3] = 255
    save(Image.fromarray(a, "RGBA"), "drawable-" + dens, "ic_stat_cigram.png")

# ---------------------------------------------------------------- in-app branding
save(fit(master, 512), "drawable-nodpi", "cigram_logo.png")              # rounded logo (splash, headers, dialogs)
save(fit(mark, 384), "drawable-nodpi", "cigram_mark.png")                # white mark only (TV rail, about, watermark)
save(fit(mono, 96), "drawable-nodpi", "cigram_watermark.png")            # small silhouette for poster watermark
if not ADMIN:
    save(fit(master, 512), "drawable-xhdpi", "hoo.png")                  # home header logo (was the red play logo)

# Android 12+ splash icon: 240 dp canvas (with an icon background circle of 160 dp)
spl = Image.new("RGBA", (960, 960), (0, 0, 0, 0))
s = int(960 * 0.40)
spl.alpha_composite(fit(mark, s), ((960 - s) // 2, (960 - s) // 2))
save(spl, "drawable-nodpi", "cigram_splash_mark.png")

# ---------------------------------------------------------------- TV banner (320x180 px xhdpi, + higher densities)
def banner(wpx, hpx):
    bg = gradient(max(wpx, hpx), [(0.0, (34, 35, 37)), (1.0, (8, 8, 9))])
    bg = bg.resize((wpx, hpx), Image.LANCZOS)
    d = ImageDraw.Draw(bg)
    logo_px = int(hpx * 0.56)
    bg.alpha_composite(fit(master, logo_px), (int(wpx * 0.07), (hpx - logo_px) // 2))
    try:
        f1 = ImageFont.truetype(os.path.join(FONTS, "cairo_bold.ttf"), int(hpx * 0.24))
        f2 = ImageFont.truetype(os.path.join(FONTS, "cairo_regular.ttf"), int(hpx * 0.095))
    except Exception:
        f1 = f2 = ImageFont.load_default()
    x = int(wpx * 0.07) + logo_px + int(wpx * 0.05)
    d.text((x, int(hpx * 0.22)), "Cigram" if not ADMIN else "Cigram Admin", font=f1, fill=(255, 255, 255))
    d.text((x, int(hpx * 0.60)), "Movies · Series · Live TV" if not ADMIN else "Control center", font=f2,
           fill=(176, 178, 184))
    return bg.convert("RGB")


if not ADMIN:
    save(banner(320, 180), "drawable-xhdpi", "tv_banner.png")
    save(banner(480, 270), "drawable-xxhdpi", "tv_banner.png")
    save(banner(640, 360), "drawable-xxxhdpi", "tv_banner.png")
    # founder portrait for the About page (natural colours, square, 720 px)
    fp = os.path.join(LOGO, "founder.jpg")
    if os.path.exists(fp):
        f = Image.open(fp).convert("RGB")
        side = min(f.size)
        f = f.crop(((f.width - side) // 2, (f.height - side) // 2, (f.width + side) // 2, (f.height + side) // 2))
        p = os.path.join(RES, "drawable-nodpi", "cigram_founder.jpg")
        f.resize((720, 720), Image.LANCZOS).save(p, quality=90, optimize=True)

# ---------------------------------------------------------------- previews at real sizes for inspection
sheet = Image.new("RGB", (1180, 560), (128, 128, 132))
dark = Image.new("RGB", (590, 280), (14, 14, 16))
sheet.paste(dark, (590, 0))
light = Image.new("RGB", (590, 280), (244, 244, 246))
sheet.paste(light, (0, 280))
dd = ImageDraw.Draw(sheet)
x = 10
for suffix in VARIANTS:
    for dens in ("mdpi", "xhdpi", "xxxhdpi"):
        im = Image.open(os.path.join(RES, "mipmap-" + dens, "ic_launcher" + suffix + ".png"))
        sheet.paste(im, (x, 10), im)
        sheet.paste(im, (x + 590, 10) if x + 590 < 1180 else (x, 140), im)
        x += im.width + 6
        if x > 560:
            break
    if x > 560:
        break
y = 290
x = 10
for dens in DENS:
    im = Image.open(os.path.join(RES, "drawable-" + dens, "ic_stat_cigram.png"))
    sheet.paste(im, (x, y), im)
    sheet.paste(im, (x + 590, y), im)
    x += im.width + 10
sheet.save(os.path.join(PREV, "preview_sheet.png"))
print("assets written to", RES)

# ---------------------------------------------------------------- previews for the in-app icon picker (Settings)
for suffix, v in VARIANTS.items():
    if suffix == "":
        continue
    art = 1024
    body = variant_bg(v, art)
    mk = variant_mark(v, int(art * MARK_FRAC_LEGACY))
    body.alpha_composite(mk, ((art - mk.width) // 2, (art - mk.height) // 2))
    sq = Image.new("RGBA", (art, art), (0, 0, 0, 0))
    sq.paste(body, (0, 0), squircle_mask(art, inset=0.03))
    save(sq.resize((256, 256), Image.LANCZOS), "drawable-nodpi", "ic_launcher" + suffix + "_preview.png")
print("previews written")
