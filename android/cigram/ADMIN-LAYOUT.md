# Cigram v10.70 — Netflix home layout (for the Admin app)

The app reads the home layout from the worker endpoint:

```
GET https://cigram-admin-api.wwq-mixtv.workers.dev/home-layout
```

Answer with a JSON object (or `{ "layout": { ... } }`). Every key is optional; anything missing
uses the defaults below. The app caches the last answer, refreshes it at most once a minute
(and whenever Home renders), and rebuilds the home when it changes. A missing endpoint (404)
or no network keeps the defaults: nothing breaks.

```json
{
  "version": 1,
  "theme": { "mode": "system" },
  "chips": [
    { "title": "مسلسلات", "type": "series" },
    { "title": "أفلام",   "type": "movie" },
    { "title": "أنمي",    "section_id": "anime", "type": "all" }
  ],
  "billboard": { "enabled": true, "item_id": "1234", "type": "movie" },
  "sections": {
    "trending": { "style": "top10", "title": "أفضل 10 هذا الأسبوع" },
    "new":      { "style": "landscape" },
    "anime":    { "style": "large" },
    "featured": { "style": "billboard" }
  }
}
```

| Key | Meaning | Default |
|---|---|---|
| `theme.mode` | `system` (follow the phone's Light/Dark setting), `light`, or `dark` for everybody | `system` |
| `chips` | Chips under the header. `type`: `series` / `movie` / `all`; optional `section_id` opens that section | none (hidden) |
| `billboard` | Big featured title at the top (replaces the old hero carousel). Use `item_id` (+ `type`), or `section_id` = first title of that section. `"enabled": false` turns it off | off |
| `sections.<id or name>.style` | `row` (normal posters), `top10` (big ranks, max 10), `landscape` (16:9 backdrops), `large` (big posters), `billboard` (that section becomes a billboard of its first title) | `row` |
| `sections.<id or name>.title` | Overrides the section title on the home page | catalogue title |

The order of the sections and which ones are shown keep coming from `/home-sections`, unchanged.

A section can also carry its style inside the catalogue itself: put `"style": "top10"`
(or `layout` / `display`) on the section object, next to `section_id` / `name`.
The `/home-layout` value wins when both are set.
