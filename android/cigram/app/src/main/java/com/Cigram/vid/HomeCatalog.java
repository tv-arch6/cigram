package com.Cigram.vid;

/**
 * Catalog flattening / de-duplication used by Home. Moved out of MainActivity.onResponse (where it
 * was a local class) so it can run on a background thread: parsing the whole catalogue on the main
 * thread is what froze Home at start and produced "Cigram isn't responding".
 */
				final class HomeCatalog {
					String value(org.json.JSONObject o, String... keys) {
						if (o == null) return "";
						for (String key : keys) {
							Object v = o.opt(key);
							if (v != null
							&& v != org.json.JSONObject.NULL
							&& !String.valueOf(v).trim().isEmpty()) {
								return String.valueOf(v).trim();
							}
						}
						return "";
					}
					
					// Perf: patterns compiled once (replaceAll() compiled them on every call, for every title).
					private static final java.util.regex.Pattern RX_MARKS = java.util.regex.Pattern.compile("\\p{M}+");
					private static final java.util.regex.Pattern RX_NOT_WORD = java.util.regex.Pattern.compile("[^\\p{L}\\p{N}]+");
					private static final java.util.regex.Pattern RX_SPACES = java.util.regex.Pattern.compile("\\s+");
					
					String normal(String s) {
						if (s == null) return "";
						String t = RX_MARKS.matcher(java.text.Normalizer.normalize(
						s, java.text.Normalizer.Form.NFKD)).replaceAll("")
						.replace("ـ", "")
						.toLowerCase(java.util.Locale.ROOT)
						.replace('أ', 'ا')
						.replace('إ', 'ا')
						.replace('آ', 'ا')
						.replace('ٱ', 'ا')
						.replace('ى', 'ي')
						.replace('ة', 'ه')
						.replace('ؤ', 'و')
						.replace('ئ', 'ي');
						t = RX_NOT_WORD.matcher(t).replaceAll(" ").trim();
						return RX_SPACES.matcher(t).replaceAll(" ");
					}
					
					String kind(org.json.JSONObject o, String inherited) {
						String t = normal(
						value(o, "type", "content_type", "media_type")
						);
						
						if (t.equals("series")
						|| t.equals("tv")
						|| t.equals("show")
						|| t.contains("مسلسل")) {
							return "series";
						}
						
						if (t.equals("movie")
						|| t.equals("film")
						|| t.equals("فيلم")) {
							return "movie";
						}
						
						if (!value(
						o, "season", "episode",
						"series_title", "series_id"
						).isEmpty()
						|| o.has("episodes")
						|| o.has("seasons")
						|| o.has("series_episodes_json")) {
							return "series";
						}
						
						return "series".equals(inherited)
						? "series" : "movie";
					}
					
					void episodes(
					Object node,
					org.json.JSONObject parent,
					String season,
					org.json.JSONArray out,
					int depth
					) throws org.json.JSONException {
						if (node == null
						|| node == org.json.JSONObject.NULL
						|| depth > 12) {
							return;
						}
						
						if (node instanceof String) {
							String s = ((String) node).trim();
							
							if (s.startsWith("[") || s.startsWith("{")) {
								episodes(
								new org.json.JSONTokener(s).nextValue(),
								parent, season, out, depth + 1
								);
							}
							return;
						}
						
						if (node instanceof org.json.JSONArray) {
							org.json.JSONArray array =
							(org.json.JSONArray) node;
							
							for (int i = 0; i < array.length(); i++) {
								episodes(
								array.opt(i),
								parent, season, out, depth + 1
								);
							}
							return;
						}
						
						if (!(node instanceof org.json.JSONObject)) return;
						
						org.json.JSONObject object =
						(org.json.JSONObject) node;
						
						String seasonNumber =
						value(object, "season", "season_number");
						
						if (seasonNumber.isEmpty()) {
							seasonNumber = season;
						}
						
						if (object.has("seasons")) {
							episodes(
							object.opt("seasons"),
							parent, seasonNumber, out, depth + 1
							);
							return;
						}
						
						if (object.has("episodes")) {
							if (seasonNumber.isEmpty()) {
								seasonNumber = value(object, "number");
							}
							
							episodes(
							object.opt("episodes"),
							parent, seasonNumber, out, depth + 1
							);
							return;
						}
						
						org.json.JSONObject episode =
						new org.json.JSONObject(object.toString());
						
						for (String key : new String[]{
							"poster", "series_poster", "backdrop",
							"category", "year", "imdb", "description"
						}) {
							if (value(episode, key).isEmpty()
							&& !value(parent, key).isEmpty()) {
								episode.put(key, parent.opt(key));
							}
						}
						
						episode.put("type", "series");
						
						if (value(episode, "series_title").isEmpty()) {
							episode.put(
							"series_title",
							value(parent, "series_title", "title", "name")
							);
						}
						
						if (value(episode, "series_id").isEmpty()) {
							episode.put(
							"series_id",
							value(parent, "series_id", "id")
							);
						}
						
						if (value(episode, "season").isEmpty()) {
							episode.put(
							"season",
							seasonNumber.isEmpty() ? "1" : seasonNumber
							);
						}
						
						if (value(episode, "episode").isEmpty()) {
							episode.put(
							"episode",
							value(episode, "episode_number", "number")
							);
						}
						
						if (value(episode, "video").isEmpty()) {
							episode.put(
							"video",
							value(
							episode, "link", "url",
							"movie_url", "video_url"
							)
							);
						}
						
						out.put(episode);
					}
					
					void collect(
					Object node,
					String inherited,
					org.json.JSONArray out,
					int depth
					) throws org.json.JSONException {
						if (node == null
						|| node == org.json.JSONObject.NULL
						|| depth > 12) {
							return;
						}
						
						if (node instanceof org.json.JSONArray) {
							org.json.JSONArray array =
							(org.json.JSONArray) node;
							
							for (int i = 0; i < array.length(); i++) {
								collect(
								array.opt(i),
								inherited, out, depth + 1
								);
							}
							return;
						}
						
						if (!(node instanceof org.json.JSONObject)) return;
						
						org.json.JSONObject object =
						(org.json.JSONObject) node;
						
						String[] containers = {
							"movies", "series", "items", "data",
							"results", "content", "sections", "catalog"
						};
						
						boolean collection = false;
						
						for (String key : containers) {
							Object child = object.opt(key);
							
							if (child instanceof org.json.JSONArray
							|| child instanceof org.json.JSONObject) {
								collection = true;
							}
							
							if (child instanceof String) {
								String encoded =
								((String) child).trim();
								
								if (encoded.startsWith("[")
								|| encoded.startsWith("{")) {
									collection = true;
								}
							}
						}
						
						boolean record =
						!collection
						&& !value(
						object, "title", "name", "series_title"
						).isEmpty()
						&& (object.has("id")
						|| object.has("poster")
						|| object.has("video")
						|| object.has("link")
						|| object.has("type")
						|| object.has("episodes")
						|| object.has("seasons"));
						
						if (record) {
							org.json.JSONObject item =
							new org.json.JSONObject(object.toString());
							
							String type = kind(item, inherited);
							item.put("type", type);
							
							item.put(
							"title",
							type.equals("series")
							? value(
							item, "series_title", "title", "name"
							)
							: value(item, "title", "name")
							);
							
							if (value(item, "poster").isEmpty()) {
								item.put(
								"poster",
								value(
								item,
								"series_poster", "season_poster",
								"image", "backdrop"
								)
								);
							}
							
							if (value(item, "video").isEmpty()) {
								item.put(
								"video",
								value(
								item,
								"link", "url",
								"video_url", "movie_url"
								)
								);
							}
							
							if (value(item, "category").isEmpty()) {
								item.put(
								"category",
								value(item, "genre", "genres")
								);
							}
							
							if (value(item, "imdb").isEmpty()) {
								item.put(
								"imdb",
								value(item, "rating")
								);
							}
							
							if (type.equals("series")) {
								org.json.JSONArray list =
								new org.json.JSONArray();
								
								for (String key : new String[]{
									"series_episodes_json",
									"episodes", "seasons"
								}) {
									if (item.has(key)) {
										episodes(
										item.opt(key),
										item, "", list, 0
										);
									}
								}
								
								if (list.length() > 0) {
									item.put(
									"series_episodes_json",
									list.toString()
									);
								}
							}
							
							out.put(item);
							return;
						}
						
						for (String key : containers) {
							Object child = object.opt(key);
							
							if (child instanceof String) {
								String encoded =
								((String) child).trim();
								
								if (encoded.startsWith("[")
								|| encoded.startsWith("{")) {
									child = new org.json.JSONTokener(
									encoded
									).nextValue();
								}
							}
							
							if (child != null) {
								collect(
								child,
								key.equals("series")
								? "series" : inherited,
								out,
								depth + 1
								);
							}
						}
					}
					
					String key(org.json.JSONObject item) {
						if (value(item, "type").equals("series")) {
							String id = value(item, "series_id");
							
							return "series|"
							+ (id.isEmpty()
							? normal(
							value(
							item,
							"series_title", "title"
							)
							)
							: id);
						}
						
						String id = value(item, "id");
						
						return "movie|"
						+ (id.isEmpty()
						? normal(value(item, "title"))
						+ "|" + value(item, "year")
						: id);
					}
					
					org.json.JSONArray parse(String raw)
					throws org.json.JSONException {

						// Tolerate a leading BOM / stray whitespace: JSONTokener does not skip U+FEFF,
						// so a BOM-prefixed body would otherwise fail to parse and blank the screen.
						Object root =
						new org.json.JSONTokener(CigramTitles.stripBom(raw)).nextValue();
						
						if (root instanceof org.json.JSONObject) {
							org.json.JSONObject object =
							(org.json.JSONObject) root;
							
							if (!object.optBoolean("ok", true)) {
								throw new org.json.JSONException(
								"Catalog failed"
								);
							}
							
							boolean known =
							!value(
							object,
							"title", "name", "series_title"
							).isEmpty();
							
							for (String key : new String[]{
								"movies", "series", "items", "data",
								"results", "content",
								"sections", "catalog"
							}) {
								known = known || object.has(key);
							}
							
							if (!known) {
								throw new org.json.JSONException(
								"Unknown catalog"
								);
							}
						} else if (!(root instanceof org.json.JSONArray)) {
							throw new org.json.JSONException(
							"Invalid catalog"
							);
						}
						
						org.json.JSONArray flat =
						new org.json.JSONArray();
						
						collect(root, "", flat, 0);
						
						java.util.LinkedHashMap<
						String, org.json.JSONObject
						> unique =
						new java.util.LinkedHashMap<
						String, org.json.JSONObject
						>();
						
						java.util.LinkedHashMap<
						String,
						java.util.LinkedHashMap<
						String, org.json.JSONObject
						>
						> episodeGroups =
						new java.util.LinkedHashMap<
						String,
						java.util.LinkedHashMap<
						String, org.json.JSONObject
						>
						>();
						
						for (int i = 0; i < flat.length(); i++) {
							org.json.JSONObject item =
							flat.getJSONObject(i);
							
							String itemKey = key(item);
							
							org.json.JSONObject existing =
							unique.get(itemKey);
							
							if (existing == null) {
								org.json.JSONObject first =
								new org.json.JSONObject(item.toString());
								if (value(item, "type").equals("series")
								&& value(item, "episode").isEmpty()
								&& !item.has("episodes") && !item.has("seasons")) {
									first.put("_cg_header", true); // v6.3: a series header, never an episode
								}
								unique.put(itemKey, first);
							} else if (value(item, "type").equals("series")
							&& !value(existing, "episode").isEmpty()
							&& value(item, "episode").isEmpty()
							&& !item.has("episodes") && !item.has("seasons")) {
								// v6.3: the series header arrives after its newest episode (the list is newest first).
								// The header keeps the identity (title, poster, description); the episode only
								// fills what the header lacks. Before, the series showed its last episode's name.
								org.json.JSONObject header =
								new org.json.JSONObject(item.toString());
								java.util.Iterator<String> oldKeys =
								existing.keys();
								while (oldKeys.hasNext()) {
									String name = oldKeys.next();
									if (value(header, name).isEmpty()) {
										header.put(name, existing.opt(name));
									}
								}
								header.remove("episode");
								header.remove("season");
								header.put("_cg_header", true);
								unique.put(itemKey, header);
							} else {
								java.util.Iterator<String> keys =
								item.keys();
								boolean isHeader =
								existing.optBoolean("_cg_header", false);
								
								while (keys.hasNext()) {
									String name = keys.next();
									// v6.3: a header never takes an episode's number / season
									if (isHeader && (name.equals("episode")
									|| name.equals("season"))) {
										continue;
									}
									
									if (value(
									existing, name
									).isEmpty()) {
										existing.put(
										name,
										item.opt(name)
										);
									}
								}
							}
							
							if (value(item, "type").equals("series")) {
								if (!episodeGroups.containsKey(
								itemKey
								)) {
									episodeGroups.put(
									itemKey,
									new java.util.LinkedHashMap<
									String, org.json.JSONObject
									>()
									);
								}
								
								org.json.JSONArray list =
								new org.json.JSONArray();
								
								String nested =
								value(
								item,
								"series_episodes_json"
								);
								
								if (!nested.isEmpty()) {
									list = new org.json.JSONArray(
									nested
									);
								}
								
								if (!value(
								item, "episode"
								).isEmpty()
								|| (!value(
								item, "video"
								).isEmpty()
								&& list.length() == 0)) {
									list.put(item);
								}
								
								for (int n = 0;
								n < list.length();
								n++) {
									org.json.JSONObject episode =
									list.optJSONObject(n);
									
									if (episode == null) continue;
									
									String episodeKey =
									value(episode, "season")
									+ "|"
									+ value(episode, "episode")
									+ "|"
									+ value(
									episode,
									"video", "link"
									);
									
									episodeGroups.get(
									itemKey
									).put(
									episodeKey,
									episode
									);
								}
							}
						}
						
						org.json.JSONArray result =
						new org.json.JSONArray();
						
						for (java.util.Map.Entry<
						String, org.json.JSONObject
						> entry : unique.entrySet()) {
							
							java.util.LinkedHashMap<
							String, org.json.JSONObject
							> group =
							episodeGroups.get(
							entry.getKey()
							);
							
							if (group != null
							&& !group.isEmpty()) {
								
								org.json.JSONArray list =
								new org.json.JSONArray();
								
								for (org.json.JSONObject episode
								: group.values()) {
									list.put(episode);
								}
								
								entry.getValue().put(
								"series_episodes_json",
								list.toString()
								);
							}
							
							result.put(
							entry.getValue()
							);
						}
						
						return result;
					}
				}
