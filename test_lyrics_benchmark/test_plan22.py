import json
import time
import urllib.request
import urllib.parse
import gzip
import re
from concurrent.futures import ThreadPoolExecutor, as_completed

USER_AGENT_DESKTOP = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
ANDROID_YTM_UA = "com.google.android.apps.youtube.music/7.21.50 (Linux; U; Android 14)"
YTM_KEY = "AIzaSyC1xlRQImGslL28Q8HqTqD_o-w-r2Q_Z4"

def http_get(url, headers=None, timeout=4):
    req_headers = {
        "User-Agent": USER_AGENT_DESKTOP,
        "Accept": "*/*",
        "Accept-Encoding": "gzip, deflate"
    }
    if headers:
        req_headers.update(headers)
    req = urllib.request.Request(url, headers=req_headers, method="GET")
    with urllib.request.urlopen(req, timeout=timeout) as response:
        raw = response.read()
        if response.info().get("Content-Encoding") == "gzip" or (len(raw) > 2 and raw[0] == 0x1f and raw[1] == 0x8b):
            raw = gzip.decompress(raw)
        return json.loads(raw.decode("utf-8"))

def http_post(url, payload, headers=None, timeout=4):
    data = json.dumps(payload).encode("utf-8")
    req_headers = {
        "Content-Type": "application/json",
        "User-Agent": USER_AGENT_DESKTOP,
        "Accept": "*/*",
        "Accept-Encoding": "gzip, deflate"
    }
    if headers:
        req_headers.update(headers)
    req = urllib.request.Request(url, data=data, headers=req_headers, method="POST")
    with urllib.request.urlopen(req, timeout=timeout) as response:
        raw = response.read()
        if response.info().get("Content-Encoding") == "gzip" or (len(raw) > 2 and raw[0] == 0x1f and raw[1] == 0x8b):
            raw = gzip.decompress(raw)
        return json.loads(raw.decode("utf-8"))

# =====================================================================
# METADATA CLEANER (PLAN 22)
# =====================================================================
def clean_metadata(raw_title, raw_artist):
    title = raw_title
    artist = re.sub(r'(?i)(\s*-\s*Topic|\s*VEVO|\s*Official)', '', raw_artist).strip()

    # Handle delimiters: "Artist - Title" or "Title – Artist"
    for delim in [" – ", " — ", " - ", " // "]:
        if delim in title:
            parts = title.split(delim)
            if len(parts) == 2:
                p0 = parts[0].strip()
                p1 = parts[1].strip()
                if not artist or artist == "Various Artists":
                    artist = p0
                    title = p1
                elif artist.lower() in p0.lower():
                    title = p1
                else:
                    title = p0

    # Strip parenthetical and bracket noise
    re_noise = re.compile(
        r"(?i)\((?:official|audio|video|remastered|remaster|radio edit|edit|deluxe|version|feat\.|feat|ft\.|with|bonus|live|acoustic|anniversary|lyrics|lyric video).*?\)|\[.*?\]"
    )
    title = re_noise.sub("", title).strip()

    return title, artist

# =====================================================================
# 1. MUSIXMATCH LEAN ENGINE (PLAN 22 - MAX 1-2 CALLS)
# =====================================================================
cached_mxm_token = None

def get_musixmatch_token():
    global cached_mxm_token
    if cached_mxm_token:
        return cached_mxm_token
    try:
        url = "https://apic-desktop.musixmatch.com/ws/1.1/token.get?app_id=web-desktop-app-v1.0"
        res = http_get(url, timeout=3)
        token = res.get("message", {}).get("body", {}).get("user_token")
        if token and token != "Upgrade.me":
            cached_mxm_token = token
            return token
    except Exception:
        pass
    cached_mxm_token = "240228000000000000000000000000"
    return cached_mxm_token

def test_plan22_musixmatch(raw_title, raw_artist):
    t0 = time.time()
    try:
        token = get_musixmatch_token()
        title, artist = clean_metadata(raw_title, raw_artist)
        
        headers = {
            "User-Agent": USER_AGENT_DESKTOP,
            "Authority": "apic-desktop.musixmatch.com",
            "Cookie": f"x-mxm-token-id={token}"
        }
        
        # Call 1: Unified macro call (Single request returns RichSync + Subtitle + Matcher)
        q_t = urllib.parse.quote(title)
        q_a = urllib.parse.quote(artist)
        macro_url = (
            f"https://apic-desktop.musixmatch.com/ws/1.1/macro.subtitles.get?"
            f"format=json&namespace=lyrics_richsynched&subtitle_format=mxm"
            f"&app_id=web-desktop-app-v1.0&usertoken={token}"
            f"&q_track={q_t}&q_artist={q_a}"
        )
        
        res = http_get(macro_url, headers=headers, timeout=3)
        macro_calls = res.get("message", {}).get("body", {}).get("macro_calls", {})
        latency = int((time.time() - t0)*1000)
        
        # 1. Extract Syllable-Level RichSync
        richsync = macro_calls.get("track.richsync.get", {}).get("message", {}).get("body", {}).get("richsync", {})
        if richsync and isinstance(richsync, dict):
            rich_raw = richsync.get("richsync_body", "")
            if rich_raw:
                try:
                    parsed_rich = json.loads(rich_raw)
                    return {
                        "provider": "Musixmatch Lean",
                        "status": "richsync (syllable)",
                        "is_synced": True,
                        "is_word_synced": True,
                        "line_count": len(parsed_rich),
                        "latency_ms": latency
                    }
                except Exception:
                    pass

        # 2. Extract Line-Level Synced Subtitles
        track_sub = macro_calls.get("track.subtitles.get", {}).get("message", {}).get("body", {})
        if track_sub and isinstance(track_sub, dict):
            sub_list = track_sub.get("subtitle_list", [])
            if sub_list and isinstance(sub_list, list) and len(sub_list) > 0:
                sub_body = sub_list[0].get("subtitle", {}).get("subtitle_body", "")
                if sub_body:
                    try:
                        parsed_mxm = json.loads(sub_body)
                        if isinstance(parsed_mxm, list) and len(parsed_mxm) > 0:
                            return {
                                "provider": "Musixmatch Lean",
                                "status": "mxm (syllable)",
                                "is_synced": True,
                                "is_word_synced": True,
                                "line_count": len(parsed_mxm),
                                "latency_ms": latency
                            }
                    except Exception:
                        pass
                    lines = [l for l in sub_body.splitlines() if l.strip()]
                    return {
                        "provider": "Musixmatch Lean",
                        "status": "synced (line-level)",
                        "is_synced": True,
                        "is_word_synced": False,
                        "line_count": len(lines),
                        "latency_ms": latency
                    }

        # 3. Matcher subtitle fallback
        matcher_sub = macro_calls.get("matcher.track.get", {}).get("message", {}).get("body", {}).get("subtitle", {})
        if matcher_sub and isinstance(matcher_sub, dict):
            sub_body = matcher_sub.get("subtitle_body", "")
            if sub_body:
                try:
                    parsed_mxm = json.loads(sub_body)
                    if isinstance(parsed_mxm, list) and len(parsed_mxm) > 0:
                        return {
                            "provider": "Musixmatch Lean",
                            "status": "mxm (syllable)",
                            "is_synced": True,
                            "is_word_synced": True,
                            "line_count": len(parsed_mxm),
                            "latency_ms": latency
                        }
                except Exception:
                    pass
                lines = [l for l in sub_body.splitlines() if l.strip()]
                return {
                    "provider": "Musixmatch Lean",
                    "status": "synced (line-level)",
                    "is_synced": True,
                    "is_word_synced": False,
                    "line_count": len(lines),
                    "latency_ms": latency
                }

        # Call 2 (Fallback): Query search if strict matcher missed
        q_unified = urllib.parse.quote(f"{title} {artist}")
        search_url = (
            f"https://apic-desktop.musixmatch.com/ws/1.1/track.search?"
            f"format=json&page_size=1&s_track_rating=desc&f_has_richsync=1"
            f"&app_id=web-desktop-app-v1.0&usertoken={token}&q={q_unified}"
        )
        s_res = http_get(search_url, headers=headers, timeout=3)
        track_list = s_res.get("message", {}).get("body", {}).get("track_list", [])
        
        if track_list and isinstance(track_list, list):
            track = track_list[0].get("track", {})
            track_id = track.get("track_id", 0)
            if track_id > 0:
                rich_url = (
                    f"https://apic-desktop.musixmatch.com/ws/1.1/track.richsync.get?"
                    f"format=json&subtitle_format=mxm&app_id=web-desktop-app-v1.0&usertoken={token}&track_id={track_id}"
                )
                rich_res = http_get(rich_url, headers=headers, timeout=3)
                rich_dict = rich_res.get("message", {}).get("body", {})
                if isinstance(rich_dict, dict):
                    rich_body = rich_dict.get("richsync", {}).get("richsync_body", "")
                    if rich_body:
                        latency = int((time.time() - t0)*1000)
                        try:
                            parsed_rich = json.loads(rich_body)
                            return {
                                "provider": "Musixmatch Lean",
                                "status": "richsync (syllable)",
                                "is_synced": True,
                                "is_word_synced": True,
                                "line_count": len(parsed_rich),
                                "latency_ms": latency
                            }
                        except Exception:
                            pass

        return {"provider": "Musixmatch Lean", "status": "not_found", "latency_ms": int((time.time() - t0)*1000)}
    except Exception as e:
        return {"provider": "Musixmatch Lean", "status": f"error: {str(e)[:30]}", "latency_ms": int((time.time() - t0)*1000)}

# =====================================================================
# 2. YOUTUBE MUSIC SYNCED ENGINE (PLAN 22 - CANONICAL ATV RESOLUTION)
# =====================================================================
def resolve_canonical_atv_song_id(title, artist):
    try:
        clean_t, clean_a = clean_metadata(title, artist)
        search_url = f"https://music.youtube.com/youtubei/v1/search?key={YTM_KEY}&prettyPrint=false"
        search_payload = {
            "context": {
                "client": {
                    "clientName": "ANDROID_MUSIC",
                    "clientVersion": "7.21.50",
                    "androidSdkVersion": 34,
                    "hl": "en",
                    "gl": "US"
                }
            },
            "query": f"{clean_t} {clean_a}",
            "params": "EgWKAQIIAWoQEAMQBBAJEAoQBRAREBAQFQ%3D%3D" # Filter: Songs only (forces ATV selection)
        }
        headers = {
            "User-Agent": ANDROID_YTM_UA,
            "X-YouTube-Client-Name": "21",
            "X-YouTube-Client-Version": "7.21.50",
            "Content-Type": "application/json"
        }
        res = http_post(search_url, search_payload, headers=headers, timeout=3)
        
        # Deep search for videoId in first song result
        def find_first_video_id(obj):
            if isinstance(obj, dict):
                if "videoId" in obj and isinstance(obj["videoId"], str):
                    return obj["videoId"]
                for v in obj.values():
                    found = find_first_video_id(v)
                    if found:
                        return found
            elif isinstance(obj, list):
                for item in obj:
                    found = find_first_video_id(item)
                    if found:
                        return found
            return None

        found_id = find_first_video_id(res)
        return found_id
    except Exception:
        return None

def test_plan22_youtube_music(title, artist, video_id):
    t0 = time.time()
    try:
        # Step 1: Resolve canonical ATV video ID
        canonical_id = resolve_canonical_atv_song_id(title, artist) or video_id
        
        # Step 2: Fetch MPLYt_ browse ID via ANDROID_MUSIC Watch Next
        next_url = f"https://music.youtube.com/youtubei/v1/next?key={YTM_KEY}&prettyPrint=false"
        next_payload = {
            "context": {
                "client": {
                    "clientName": "ANDROID_MUSIC",
                    "clientVersion": "7.21.50",
                    "androidSdkVersion": 34,
                    "hl": "en",
                    "gl": "US"
                }
            },
            "videoId": canonical_id,
            "isAudioOnly": True
        }
        headers = {
            "User-Agent": ANDROID_YTM_UA,
            "X-YouTube-Client-Name": "21",
            "X-YouTube-Client-Version": "7.21.50",
            "Content-Type": "application/json"
        }
        
        next_res = http_post(next_url, next_payload, headers=headers, timeout=3)
        tabs = (
            next_res.get("contents", {}).get("singleColumnMusicWatchNextResultsRenderer", {}).get("tabbedRenderer", {}).get("watchNextTabbedResultsRenderer", {}).get("tabs", [])
            or next_res.get("contents", {}).get("singleColumnMusicWatchNextResultsRenderer", {}).get("tabRenderer", {}).get("tabs", [])
            or next_res.get("contents", {}).get("twoColumnBrowseResultsRenderer", {}).get("tabs", [])
        )
        
        lyric_browse_id = None
        for tab in tabs:
            tab_r = tab.get("tabRenderer", {})
            title_tab = tab_r.get("title", "")
            endpoint = tab_r.get("endpoint", {}).get("browseEndpoint", {}).get("browseId", "")
            if "LYRIC" in title_tab.upper() or endpoint.startswith("MPLYt_"):
                lyric_browse_id = endpoint
                break
                
        if not lyric_browse_id:
            return {"provider": "YouTube Music Plan22", "status": "no_lyrics_tab", "latency_ms": int((time.time() - t0)*1000)}

        # Step 3: Fetch the Timed Lyrics model from browse endpoint
        browse_url = f"https://music.youtube.com/youtubei/v1/browse?key={YTM_KEY}&prettyPrint=false"
        browse_payload = {
            "context": {
                "client": {
                    "clientName": "ANDROID_MUSIC",
                    "clientVersion": "7.21.50",
                    "androidSdkVersion": 34,
                    "hl": "en",
                    "gl": "US"
                }
            },
            "browseId": lyric_browse_id
        }
        
        browse_res = http_post(browse_url, browse_payload, headers=headers, timeout=3)
        latency = int((time.time() - t0)*1000)
        
        # Step 4: Parse timedLyricsData model
        def find_timed_lyrics(obj):
            if isinstance(obj, dict):
                if "timedLyricsData" in obj:
                    return obj["timedLyricsData"]
                for v in obj.values():
                    res = find_timed_lyrics(v)
                    if res:
                        return res
            elif isinstance(obj, list):
                for item in obj:
                    res = find_timed_lyrics(item)
                    if res:
                        return res
            return None
            
        timed_data = find_timed_lyrics(browse_res)
        if timed_data and isinstance(timed_data, list) and len(timed_data) > 0:
            lines = []
            for item in timed_data:
                cue = item.get("cueRange", {})
                start_ms = cue.get("startTimeMilliseconds", 0)
                if isinstance(start_ms, str) and start_ms.isdigit():
                    start_ms = int(start_ms)
                text = item.get("lyricLine", "")
                if text:
                    lines.append(f"[{start_ms}ms] {text}")
                    
            if lines:
                return {
                    "provider": "YouTube Music Plan22",
                    "status": "synced (timedLyricsModel)",
                    "is_synced": True,
                    "is_word_synced": False,
                    "line_count": len(lines),
                    "latency_ms": latency
                }

        # Check for static description runs
        runs = []
        for sec in browse_res.get("contents", {}).get("sectionListRenderer", {}).get("contents", []):
            shelf = sec.get("musicDescriptionShelfRenderer", {})
            if shelf:
                for r in shelf.get("description", {}).get("runs", []):
                    runs.append(r.get("text", ""))
        text = "".join(runs).strip()
        if text:
            lines = [l for l in text.splitlines() if l.strip()]
            return {
                "provider": "YouTube Music Plan22",
                "status": "plain_text",
                "is_synced": False,
                "line_count": len(lines),
                "latency_ms": latency
            }
            
        return {"provider": "YouTube Music Plan22", "status": "empty_shelf", "latency_ms": latency}
    except Exception as e:
        return {"provider": "YouTube Music Plan22", "status": f"error: {str(e)[:30]}", "latency_ms": int((time.time() - t0)*1000)}

# =====================================================================
# 20 REPRESENTATIVE TEST TRACKS
# =====================================================================
TEST_SONGS = [
    {"title": "Blinding Lights (Official Audio)", "artist": "The Weeknd", "video_id": "4NRXx6U8ABQ"},
    {"title": "Shape of You [Official Video]", "artist": "Ed Sheeran", "video_id": "JGwWNGJdvx8"},
    {"title": "Bohemian Rhapsody - Remastered 2011", "artist": "Queen - Topic", "video_id": "fJ9rUzIMcZQ"},
    {"title": "Levitating (feat. DaBaby)", "artist": "Dua Lipa", "video_id": "TUVcZfQe-Kw"},
    {"title": "Starboy (Official Music Video)", "artist": "The Weeknd ft. Daft Punk", "video_id": "34Na4j8AVgA"},
    {"title": "As It Was", "artist": "Harry Styles", "video_id": "H5v3kku4y6Q"},
    {"title": "Flowers", "artist": "Miley Cyrus", "video_id": "G7KNmW9a75Y"},
    {"title": "Someone You Loved", "artist": "Lewis Capaldi - Topic", "video_id": "zABLecsR5UE"},
    {"title": "Believer", "artist": "Imagine Dragons", "video_id": "7wtfhZwyrcc"},
    {"title": "Stay (with Justin Bieber)", "artist": "The Kid LAROI", "video_id": "kTJczUoc26U"},
    {"title": "Bad Guy", "artist": "Billie Eilish", "video_id": "DyDfgMOUjCI"},
    {"title": "Save Your Tears (Official Music Video)", "artist": "The Weeknd", "video_id": "XXYlFuWEuKi"},
    {"title": "Heat Waves (Official Video)", "artist": "Glass Animals", "video_id": "mRD0-GxqHVo"},
    {"title": "Watermelon Sugar", "artist": "Harry Styles", "video_id": "E07s5ZYygmg"},
    {"title": "Industry Baby (feat. Jack Harlow)", "artist": "Lil Nas X", "video_id": "UTHLKHL_whs"},
    {"title": "Hotel California - 2013 Remaster", "artist": "Eagles", "video_id": "09839DpTctU"},
    {"title": "Cruel Summer", "artist": "Taylor Swift", "video_id": "ic8j13piAhQ"},
    {"title": "Counting Stars – OneRepublic", "artist": "OneRepublic", "video_id": "hT_nvWreIhg"},
    {"title": "vampire (Official Video)", "artist": "Olivia Rodrigo", "video_id": "RlPNh_PBZb4"},
    {"title": "Wake Me Up (Radio Edit)", "artist": "Avicii", "video_id": "IcrbM1l_BoI"}
]

def benchmark_track(song):
    title = song["title"]
    artist = song["artist"]
    vid = song["video_id"]
    
    with ThreadPoolExecutor(max_workers=2) as executor:
        f_mxm = executor.submit(test_plan22_musixmatch, title, artist)
        f_ytm = executor.submit(test_plan22_youtube_music, title, artist, vid)
        
        res_mxm = f_mxm.result()
        res_ytm = f_ytm.result()
        
    return song, [res_mxm, res_ytm]

def main():
    print("=" * 95)
    print("🚀 LIVE BENCHMARK: PLAN 22 (MUSIXMATCH LEAN & YOUTUBE MUSIC CANONICAL ATV)")
    print(f"Testing {len(TEST_SONGS)} tracks across Musixmatch Lean + YouTube Music Plan 22...")
    print("=" * 95, flush=True)
    
    get_musixmatch_token()
    
    stats = {
        "Musixmatch Lean": {"synced": 0, "word_synced": 0, "plain": 0, "fail": 0, "total_ms": 0},
        "YouTube Music Plan22": {"synced": 0, "word_synced": 0, "plain": 0, "fail": 0, "total_ms": 0}
    }
    
    t_start = time.time()
    
    with ThreadPoolExecutor(max_workers=6) as pool:
        futures = [pool.submit(benchmark_track, s) for s in TEST_SONGS]
        
        for i, f in enumerate(as_completed(futures), 1):
            song, results = f.result()
            print(f"[{i:02d}/{len(TEST_SONGS)}] 🎵 {song['title']} - {song['artist']}", flush=True)
            for r in results:
                p_name = r["provider"]
                lat = r.get("latency_ms", 0)
                stats[p_name]["total_ms"] += lat
                
                if r.get("is_word_synced"):
                    stats[p_name]["word_synced"] += 1
                    stats[p_name]["synced"] += 1
                    stat_str = f"🔥 WORD-SYNC (Syllable) [{r.get('line_count', 0)} lines]"
                elif r.get("is_synced"):
                    stats[p_name]["synced"] += 1
                    stat_str = f"🟢 TIMED SYNC (0.00s)   [{r.get('line_count', 0)} lines]"
                elif r.get("status") == "plain_text":
                    stats[p_name]["plain"] += 1
                    stat_str = f"🟡 PLAIN TEXT          [{r.get('line_count', 0)} lines]"
                else:
                    stats[p_name]["fail"] += 1
                    stat_str = f"🔴 {r.get('status', 'FAIL')}"
                print(f"   ├─ {p_name:<22} : {stat_str:<36} [{lat:>4}ms]", flush=True)
            print(flush=True)

    t_total = time.time() - t_start
    total_tracks = len(TEST_SONGS)
    
    print("=" * 95)
    print("📊 BENCHMARK SUMMARY & PERFORMANCE MATRIX (PLAN 22)")
    print("=" * 95)
    print(f"{'Provider / Target':<24} | {'Synced %':<9} | {'Word-Sync %':<12} | {'Plain %':<9} | {'Avg Latency':<12} | {'Rating'}")
    print("-" * 95)
    for p_name, d in stats.items():
        s_pct = (d["synced"] / total_tracks) * 100
        w_pct = (d["word_synced"] / total_tracks) * 100
        p_pct = (d["plain"] / total_tracks) * 100
        avg_l = d["total_ms"] // total_tracks
        r = "⭐⭐⭐⭐⭐ (Tier 1)" if s_pct >= 75 else ("⭐⭐⭐⭐ (Tier 2)" if s_pct >= 40 else "⭐⭐⭐ (Fallback)")
        print(f"{p_name:<24} | {s_pct:>7.1f}% | {w_pct:>10.1f}% | {p_pct:>7.1f}% | {avg_l:>9}ms | {r}")
        
    print("=" * 95)
    print(f"⏱️ Total Benchmark Runtime: {t_total:.2f} seconds across {total_tracks} tracks")
    print("=" * 95, flush=True)

if __name__ == "__main__":
    main()
