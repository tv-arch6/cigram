#!/usr/bin/env python3
"""Counts the real titles without a subtitle in the Cigram catalogue (movies.json). Read-only; prints aggregates only.

usage: python3 -I catalogue_audit.py <movies.json path or https URL>
The rule is the one the admin app's Subtitle Center uses (SubCenterStore.syncFromCatalogue): every movie and every episode
is a title; a series header is not; a title "has a subtitle" when its `subtitle` field is not empty. Ids of an episode come from
its own fields or, when missing, from its series header (TMDB id, IMDb id, `tmdb` / `imdb` links, `series_<tmdbId>`).
"""
import collections, json, re, sys, urllib.request

src = sys.argv[1]
raw = urllib.request.urlopen(src, timeout=60).read().decode("utf-8") if src.startswith("http") else open(src, encoding="utf-8").read()
doc = json.loads(raw)
rows = doc["movies"] if isinstance(doc, dict) else doc
headers = {r["id"]: r for r in rows if r.get("type") == "series"}


def has_sub(r):
    return bool((r.get("subtitle") or "").strip())


def ids(r):
    h = headers.get(r.get("series_id")) if r.get("type") == "episode" else None
    src_ = h or r
    tm = str(src_.get("tmdb_id") or "").strip() or (re.search(r"(\d{1,9})", str(src_.get("tmdb") or "")) or [None, ""])[1]
    if not tm:
        m = re.match(r"^series_(\d+)$", str(src_.get("series_id") or ""))
        tm = m.group(1) if m else ""
    im = (re.search(r"tt\d{5,12}", str(src_.get("imdb_id") or "") + " " + str(src_.get("imdb") or "")) or [""])[0]
    return tm, im


titles = [r for r in rows if r.get("type") in ("movie", "episode")]
missing = [r for r in titles if not has_sub(r)]
withid = [r for r in missing if any(ids(r))]
by_series = collections.Counter(r["series_id"] for r in missing if r.get("type") == "episode")
print(json.dumps({
    "records": len(rows), "movies": sum(r["type"] == "movie" for r in titles), "series_headers": len(headers),
    "episodes": sum(r["type"] == "episode" for r in titles), "titles": len(titles), "with_subtitle": len(titles) - len(missing),
    "without_subtitle": len(missing), "movies_without": sum(r["type"] == "movie" for r in missing),
    "episodes_without": sum(r["type"] == "episode" for r in missing), "without_subtitle_with_tmdb_or_imdb_id": len(withid),
    "without_subtitle_and_without_any_id": len(missing) - len(withid), "series_with_missing_episodes": len(by_series),
    "largest_series_missing": by_series.most_common(5),
}, indent=2, ensure_ascii=False))
