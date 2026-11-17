#!/usr/bin/env python3
"""Read-only integrity audit of the Cigram catalogue (movies.json). Prints aggregates only, never credentials, never video URLs.

usage: python3 -I catalogue_integrity.py <movies.json path or https URL>

Checks (each one is a thing the offline-first sync must survive, not something it repairs by inventing data):
  - envelope: `ok`, `count` vs the real number of records
  - duplicate ids, duplicate (series_id, season, episode) rows
  - episodes whose series header is missing, series headers without any episode
  - records without id / type / title, titles without a video link, titles without a poster
  - section ids that appear under several names (and names under several ids)
  - serialized size per record (what an incremental change feed saves compared with the whole file)
"""
import collections, hashlib, json, sys, urllib.request

src = sys.argv[1]
raw = urllib.request.urlopen(src, timeout=90).read() if src.startswith("http") else open(src, "rb").read()
doc = json.loads(raw.decode("utf-8"))
rows = doc["movies"] if isinstance(doc, dict) else doc
env = {k: (v if not isinstance(v, list) else f"list[{len(v)}]") for k, v in doc.items()} if isinstance(doc, dict) else {}

ids = collections.Counter(str(r.get("id", "")) for r in rows)
dups = {k: v for k, v in ids.items() if v > 1 and k}
headers = {r["id"]: r for r in rows if r.get("type") == "series" and r.get("id")}
eps = [r for r in rows if r.get("type") == "episode"]
orphans = [r for r in eps if r.get("series_id") not in headers]
by_series = collections.defaultdict(list)
for r in eps:
    by_series[r.get("series_id")].append(r)
dup_ep = collections.Counter((r.get("series_id"), r.get("season"), r.get("episode")) for r in eps)
dup_ep = {k: v for k, v in dup_ep.items() if v > 1}
no_id = [r for r in rows if not r.get("id")]
no_type = [r for r in rows if r.get("type") not in ("movie", "series", "episode")]
titles = [r for r in rows if r.get("type") in ("movie", "episode")]
no_video = [r for r in titles if not (r.get("video") or r.get("link") or "").strip()]
no_poster = [r for r in rows if r.get("type") in ("movie", "series") and not (r.get("poster") or r.get("series_poster") or "").strip()]
sec_names = collections.defaultdict(set)
sec_ids = collections.defaultdict(set)
for r in rows:
    sid, sn = (r.get("section_id") or "").strip(), (r.get("section_name") or "").strip()
    if sid or sn:
        sec_names[sid].add(sn)
        sec_ids[sn].add(sid)
multi_name = {k: sorted(v) for k, v in sec_names.items() if len(v) > 1}
multi_id = {k: sorted(v) for k, v in sec_ids.items() if len(v) > 1}
sizes = [len(json.dumps(r, ensure_ascii=False, separators=(",", ":")).encode("utf-8")) for r in rows]
hashes = {r.get("id"): hashlib.sha256(json.dumps(r, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()).hexdigest()[:12] for r in rows}
out = {
    "bytes": len(raw), "envelope": env, "records": len(rows),
    "envelope_count_matches_records": (doc.get("count") == len(rows)) if isinstance(doc, dict) else None,
    "types": dict(collections.Counter(r.get("type") for r in rows)),
    "duplicate_ids": len(dups), "duplicate_episode_rows": len(dup_ep),
    "records_without_id": len(no_id), "records_with_unknown_type": len(no_type),
    "episodes_without_series_header": len(orphans), "series_headers_without_episodes": sum(1 for h in headers if h not in by_series),
    "titles_without_video": len(no_video), "movies_or_series_without_poster": len(no_poster),
    "section_ids_with_several_names": len(multi_name), "section_names_with_several_ids": len(multi_id),
    "section_name_examples": dict(list(multi_name.items())[:3]),
    "record_bytes": {"min": min(sizes), "avg": sum(sizes) // len(sizes), "max": max(sizes)},
    "distinct_record_hashes": len(set(hashes.values())),
}
print(json.dumps(out, ensure_ascii=False, indent=2))
