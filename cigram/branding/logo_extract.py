"""Extract a clean Cigram logo from the supplied RGB image whose 'transparency' is a baked-in checkerboard.

Outputs (in OUT dir):
  logo_master.png    1024x1024 RGBA: the dark rounded square with the mark, real alpha outside
  mark_white.png     1024x1024 RGBA: only the white mark (ring + D + eye highlight) on transparent
  mark_mono.png      1024x1024 RGBA: monochrome silhouette for notification icons (white, binary-ish alpha)
  info.txt           measured geometry
"""
import sys
import numpy as np
from PIL import Image, ImageFilter
from scipy import ndimage

SRC, OUT = sys.argv[1], sys.argv[2]

im = Image.open(SRC).convert("RGB")
a = np.asarray(im).astype(np.float32)
lum = 0.299 * a[..., 0] + 0.587 * a[..., 1] + 0.114 * a[..., 2]
h, w = lum.shape

# 1. checkerboard = light pixels connected to the image border
light = lum > 120
labels, n = ndimage.label(light)
border_labels = set(np.unique(np.concatenate([labels[0, :], labels[-1, :], labels[:, 0], labels[:, -1]])))
border_labels.discard(0)
bg = np.isin(labels, list(border_labels))
square = ~bg
square = ndimage.binary_fill_holes(square)
# keep the single largest component (drop specks)
lab2, n2 = ndimage.label(square)
sizes = ndimage.sum(square, lab2, range(1, n2 + 1))
square = lab2 == (int(np.argmax(sizes)) + 1)
# erode 2 px: the outer rim pixels are blended with the checkerboard
core = ndimage.binary_erosion(square, iterations=2)

# 2. anti-aliased alpha: blur the binary mask a little
alpha = ndimage.gaussian_filter(core.astype(np.float32), 1.1)
alpha = np.clip((alpha - 0.5) * 2.2 + 0.5, 0, 1)

# 3. de-contaminate colours of edge pixels: take the colour of the nearest core pixel
dist, (iy, ix) = ndimage.distance_transform_edt(~ndimage.binary_erosion(core, iterations=2), return_indices=True)
clean = a[iy, ix]
master = np.dstack([clean, alpha * 255]).astype(np.uint8)
ys, xs = np.where(core)
bbox = (xs.min(), ys.min(), xs.max(), ys.max())

# 4. the white mark: bright pixels well inside the square
inner = ndimage.binary_erosion(core, iterations=40)
# local background of the square (dark gradient) estimated by a large median of non-bright pixels
dark_ref = np.where(lum < 110, lum, np.nan)
# column-wise vertical gradient model: mean dark lum per row
row_dark = np.nanmedian(np.where(inner, dark_ref, np.nan), axis=1)
row_dark = np.where(np.isnan(row_dark), 20.0, row_dark)
bgl = row_dark[:, None]
mark_a = np.clip((lum - bgl) / (255.0 - bgl), 0, 1) * inner
mark_a[mark_a < 0.06] = 0
mark = np.zeros((h, w, 4), np.uint8)
mark[..., 0:3] = 255
mark[..., 3] = (mark_a * 255).astype(np.uint8)
mys, mxs = np.where(mark_a > 0.5)
mbbox = (mxs.min(), mys.min(), mxs.max(), mys.max())

# 5. monochrome silhouette (notification): white where the mark is clearly white; the grey eye highlight
#    (alpha ~0.3) is dropped so the silhouette stays crisp at 24 dp.
mono_a = np.clip((mark_a - 0.45) / 0.2, 0, 1)
mono = np.zeros((h, w, 4), np.uint8)
mono[..., 0:3] = 255
mono[..., 3] = (mono_a * 255).astype(np.uint8)

Image.fromarray(master, "RGBA").save(OUT + "/logo_master.png")
Image.fromarray(mark, "RGBA").save(OUT + "/mark_white.png")
Image.fromarray(mono, "RGBA").save(OUT + "/mark_mono.png")
# sample the square's colours for a procedural background
top = a[bbox[1] + 60, (bbox[0] + bbox[2]) // 2]
mid = a[(bbox[1] + bbox[3]) // 2, bbox[0] + 60]
bot = a[bbox[3] - 60, (bbox[0] + bbox[2]) // 2]
with open(OUT + "/info.txt", "w") as f:
    f.write("square bbox %s\nmark bbox %s\ntop %s mid %s bottom %s\n" % (bbox, mbbox, top, mid, bot))
print(open(OUT + "/info.txt").read())
