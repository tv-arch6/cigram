#!/usr/bin/env python3
"""Builds the Cigram emoji atlas (assets/emoji) from open-licensed sources.

  Fluent Emoji 3D (Microsoft, MIT)        -> every emoji it covers
  Twemoji (CC-BY 4.0, via iamcal sheet)   -> flags and whatever Fluent lacks
  emoji-data (iamcal, MIT)                -> order, categories, sequences

Output: strip_NN.webp (32 cells of 64 px each) + index.txt + LICENSES.txt
Usage:  build_emoji_atlas.py <emoji_src dir> <out dir>
"""
import json, os, sys, glob
from PIL import Image
Image.MAX_IMAGE_PIXELS = None

SRC, OUT = sys.argv[1], sys.argv[2]
CELL, PER = 64, 32
os.makedirs(OUT, exist_ok=True)
for f in glob.glob(os.path.join(OUT, '*')):
    os.remove(f)

GROUPS = [  # the order and grouping of the iOS keyboard
    ('smileys', ['Smileys & Emotion', 'People & Body']),
    ('animals', ['Animals & Nature']),
    ('food', ['Food & Drink']),
    ('activity', ['Activities']),
    ('travel', ['Travel & Places']),
    ('objects', ['Objects']),
    ('symbols', ['Symbols']),
    ('flags', ['Flags']),
]

def key(seq):
    return '-'.join(p for p in seq.upper().replace(' ', '-').split('-') if p != 'FE0F')

fluent = {}
for meta in glob.glob(os.path.join(SRC, 'fluent', 'assets', '*', 'metadata.json')):
    d = os.path.dirname(meta)
    m = json.load(open(meta, encoding='utf-8'))
    png = glob.glob(os.path.join(d, '3D', '*.png')) or glob.glob(os.path.join(d, 'Default', '3D', '*.png'))
    if png and m.get('unicode'):
        fluent[key(m['unicode'])] = png[0]

data = json.load(open(os.path.join(SRC, 'emoji.json'), encoding='utf-8'))
sheet = Image.open(os.path.join(SRC, 'sheet_twitter_64.png')).convert('RGBA')

rows, n_fluent, n_tw = [], 0, 0
for gi, (gid, cats) in enumerate(GROUPS):
    items = sorted((e for e in data if e['category'] in cats), key=lambda e: (cats.index(e['category']), e['sort_order']))
    for e in items:
        k = key(e['unified'])
        if k in fluent:
            im = Image.open(fluent[k]).convert('RGBA').resize((CELL, CELL), Image.LANCZOS)
            n_fluent += 1
        elif e.get('has_img_twitter'):
            x, y = e['sheet_x'] * 66 + 1, e['sheet_y'] * 66 + 1
            im = sheet.crop((x, y, x + 64, y + 64))
            n_tw += 1
        else:
            continue
        rows.append((gi, e['unified'], im))

strips = (len(rows) + PER - 1) // PER
total = 0
for s in range(strips):
    strip = Image.new('RGBA', (CELL * PER, CELL), (0, 0, 0, 0))
    for i, (_, _, im) in enumerate(rows[s * PER:(s + 1) * PER]):
        strip.paste(im, (i * CELL, 0))
    p = os.path.join(OUT, 'strip_%02d.webp' % s)
    strip.save(p, 'WEBP', quality=88, method=6, alpha_quality=92)
    total += os.path.getsize(p)

with open(os.path.join(OUT, 'index.txt'), 'w', encoding='utf-8') as f:
    f.write('v1 %d %d %d %s\n' % (CELL, PER, len(rows), ','.join(g for g, _ in GROUPS)))
    for gi, uni, _ in rows:
        f.write('%d %s\n' % (gi, uni))

with open(os.path.join(OUT, 'LICENSES.txt'), 'w', encoding='utf-8') as f:
    f.write('''Cigram emoji atlas

Artwork
  Fluent Emoji (3D) - Copyright (c) Microsoft Corporation - MIT License
    https://github.com/microsoft/fluentui-emoji
  Twemoji (flags and the emoji Fluent does not cover) - Copyright Twitter, Inc and other contributors
    Graphics licensed under CC-BY 4.0: https://creativecommons.org/licenses/by/4.0/
    https://github.com/twitter/twemoji (images taken from the emoji-data sprite sheet)

Data
  emoji-data - Copyright (c) Cal Henderson - MIT License
    https://github.com/iamcal/emoji-data

The images were resized to 64 px and packed into strips; nothing else was changed.
''')
print('emoji', len(rows), 'fluent', n_fluent, 'twemoji', n_tw, 'strips', strips, 'bytes', total)
