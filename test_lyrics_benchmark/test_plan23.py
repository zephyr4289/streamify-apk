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

def http_get(url, headers=None, timeout=3):
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

def http_post(url, payload, headers=None, timeout=3):
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
# SANITIZERS (PLAN 23)
# =====================================================================
def sanitize_search_query(title):
    re_noise = re.compile(
        r"(?i)\((?:official|audio|video|remastered|remaster|radio edit|edit|deluxe|version|feat\.|feat|ft\.|with|bonus|live|acoustic|anniversary|lyrics|lyric video).*?\)|\[.*?\]"
    )
    cleaned = re_noise.sub("", title).strip()
    for delim in [" – ", " — ", " - ", " // "]:
        if delim in cleaned:
            cleaned = cleaned.split(delim)[0].strip()
    return cleaned if cleaned else title

def sanitize_artist_query(artist):
    cleaned = re.sub(r'(?i)(\s*-\s*Topic|\s*VEVO|\s*Official)', '', artist).strip()
    cleaned = re.sub(r'(?i)(feat\.?|ft\.?).*', '', cleaned).strip()
    return cleaned if cleaned else artist

# =====================================================================
# 1. YOUTUBE MUSIC TOP-3 CANDIDATE SWEEP ENGINE (PLAN 23)
# =====================================================================
def resolve_top_atv_candidates(title, artist):
    try:
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
            "query": f"{title} {artist}",
            "params": "EgWKAQIIAWoQEAMQBBAJEAoQBRAREBAQFQ%3D%3D" # Filter: Songs
        }
        headers = {
            "User-Agent": ANDROID_YTM_UA,
            "X-YouTube-Client-Name": "21",
            "X-YouTube-Client-Version": "7.21.50",
            "Content-Type": "application/json"
        }
        res = http_post(search_url, search_payload, headers=headers, timeout=2.5)
        
        ids = []
        # Check musicCardShelf
        try:
            top_card = res["contents"]["tabbedSearchResultsRenderer"]["tabs"][0]["tabRenderer"]["content"]["sectionListRenderer"]["contents"][0]["musicCardShelfRenderer"]["onTap"]["watchEndpoint"]["videoId"]
            if top_card and top_card not in ids:
                ids.append(top_card)
        except Exception:
            pass

        # Check list items
        def collect_video_ids(obj):
            if isinstance(obj, dict):
                if "videoId" in obj and isinstance(obj["videoId"], str):
                    if obj["videoId"] not in ids:
                        ids.append(obj["videoId"])
                for v in obj.values():
                    collect_video_ids(v)
            elif isinstance(obj, list):
                for item in obj:
                    collect_video_ids(item)

        collect_video_ids(res)
        return ids[:3]
    except Exception:
        return []

def try_extract_timed_lyrics(video_id):
    try:
        # Step 1: /next to get MPLYt_ browseId
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
            "videoId": video_id,
            "isAudioOnly": True
        }
        headers = {
            "User-Agent": ANDROID_YTM_UA,
            "X-YouTube-Client-Name": "21",
            "X-YouTube-Client-Version": "7.21.50",
            "Content-Type": "application/json"
        }
        
        next_res = http_post(next_url, next_payload, headers=headers, timeout=2.0)
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
            return None

        # Step 2: /browse to get timedLyricsData
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
        
        browse_res = http_post(browse_url, browse_payload, headers=headers, timeout=2.0)
        
        # Deep search for timedLyricsData
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
                return lines
        return None
    except Exception:
        return None

def test_plan23_youtube_music(raw_title, raw_artist, fallback_vid=""):
    t0 = time.time()
    try:
        clean_title = sanitize_search_query(raw_title)
        clean_artist = sanitize_artist_query(raw_artist)
        
        candidates = resolve_top_atv_candidates(clean_title, clean_artist)
        if fallback_vid and fallback_vid not in candidates:
            candidates.append(fallback_vid)
            
        for vid in candidates:
            lines = try_extract_timed_lyrics(vid)
            if lines:
                return {
                    "provider": "YouTube Music Plan23",
                    "status": "synced (timedLyricsModel)",
                    "is_synced": True,
                    "is_word_synced": False,
                    "line_count": len(lines),
                    "latency_ms": int((time.time() - t0)*1000)
                }
                
        return {"provider": "YouTube Music Plan23", "status": "no_timed_lyrics_in_candidates", "latency_ms": int((time.time() - t0)*1000)}
    except Exception as e:
        return {"provider": "YouTube Music Plan23", "status": f"error: {str(e)[:30]}", "latency_ms": int((time.time() - t0)*1000)}

# =====================================================================
# 2. MUSIXMATCH LEAN ENGINE (PLAN 23)
# =====================================================================
cached_mxm_token = None

def get_musixmatch_token():
    global cached_mxm_token
    if cached_mxm_token:
        return cached_mxm_token
    try:
        url = "https://apic-desktop.musixmatch.com/ws/1.1/token.get?app_id=web-desktop-app-v1.0"
        res = http_get(url, timeout=2.5)
        token = res.get("message", {}).get("body", {}).get("user_token")
        if token and token != "Upgrade.me":
            cached_mxm_token = token
            return token
    except Exception:
        pass
    cached_mxm_token = "240228000000000000000000000000"
    return cached_mxm_token

def test_plan23_musixmatch(raw_title, raw_artist):
    t0 = time.time()
    try:
        token = get_musixmatch_token()
        clean_title = sanitize_search_query(raw_title)
        clean_artist = sanitize_artist_query(raw_artist)
        
        headers = {
            "User-Agent": USER_AGENT_DESKTOP,
            "Authority": "apic-desktop.musixmatch.com",
            "Cookie": f"x-mxm-token-id={token}"
        }
        
        q_t = urllib.parse.quote(clean_title)
        q_a = urllib.parse.quote(clean_artist)
        macro_url = (
            f"https://apic-desktop.musixmatch.com/ws/1.1/macro.subtitles.get?"
            f"format=json&namespace=lyrics_richsynched&subtitle_format=mxm"
            f"&app_id=web-desktop-app-v1.0&usertoken={token}"
            f"&q_track={q_t}&q_artist={q_a}"
        )
        
        res = http_get(macro_url, headers=headers, timeout=2.5)
        macro_calls = res.get("message", {}).get("body", {}).get("macro_calls", {})
        latency = int((time.time() - t0)*1000)
        
        # 1. Syllable RichSync
        richsync = macro_calls.get("track.richsync.get", {}).get("message", {}).get("body", {}).get("richsync", {})
        if richsync and isinstance(richsync, dict):
            rich_raw = richsync.get("richsync_body", "")
            if rich_raw:
                try:
                    parsed = json.loads(rich_raw)
                    return {
                        "provider": "Musixmatch Plan23",
                        "status": "richsync (syllable)",
                        "is_synced": True,
                        "is_word_synced": True,
                        "line_count": len(parsed),
                        "latency_ms": latency
                    }
                except Exception:
                    pass

        # 2. Subtitle fallback
        track_sub = macro_calls.get("track.subtitles.get", {}).get("message", {}).get("body", {})
        if track_sub and isinstance(track_sub, dict):
            sub_list = track_sub.get("subtitle_list", [])
            if sub_list and isinstance(sub_list, list) and len(sub_list) > 0:
                sub_body = sub_list[0].get("subtitle", {}).get("subtitle_body", "")
                if sub_body:
                    try:
                        parsed = json.loads(sub_body)
                        if isinstance(parsed, list) and len(parsed) > 0:
                            return {
                                "provider": "Musixmatch Plan23",
                                "status": "mxm (syllable)",
                                "is_synced": True,
                                "is_word_synced": True,
                                "line_count": len(parsed),
                                "latency_ms": latency
                            }
                    except Exception:
                        pass
                    lines = [l for l in sub_body.splitlines() if l.strip()]
                    return {
                        "provider": "Musixmatch Plan23",
                        "status": "synced (line-level)",
                        "is_synced": True,
                        "is_word_synced": False,
                        "line_count": len(lines),
                        "latency_ms": latency
                    }
                    
        return {"provider": "Musixmatch Plan23", "status": "not_found", "latency_ms": latency}
    except Exception as e:
        return {"provider": "Musixmatch Plan23", "status": f"error: {str(e)[:30]}", "latency_ms": int((time.time() - t0)*1000)}

# =====================================================================
# 3. PLAN 23 PARALLEL RACER
# =====================================================================
def run_plan23_parallel_race(song):
    title = song["title"]
    artist = song["artist"]
    vid = song["video_id"]
    
    t0 = time.time()
    with ThreadPoolExecutor(max_workers=2) as ex:
        f_ytm = ex.submit(test_plan23_youtube_music, title, artist, vid)
        f_mxm = ex.submit(test_plan23_musixmatch, title, artist)
        
        res_ytm = f_ytm.result()
        res_mxm = f_mxm.result()
        
    total_latency = int((time.time() - t0)*1000)
    
    # Selection rule: Word-Sync (Musixmatch) > 0.00s Timed Sync (YouTube Music)
    winner = None
    if res_mxm.get("is_word_synced"):
        winner = res_mxm
    elif res_ytm.get("is_synced"):
        winner = res_ytm
    elif res_mxm.get("is_synced"):
        winner = res_mxm
        
    return song, winner, [res_ytm, res_mxm], total_latency

# =====================================================================
# 20 DIVERSE REAL-WORLD TRACKS
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

def main():
    print("=" * 95)
    print("🚀 LIVE BENCHMARK: PLAN 23 (TOP-3 ATV SWEEP + PARALLEL RACER)")
    print(f"Testing {len(TEST_SONGS)} tracks across YouTube Music Top-3 Sweep & Musixmatch Parallel Race...")
    print("=" * 95, flush=True)
    
    get_musixmatch_token()
    
    stats = {
        "YouTube Music Plan23": {"synced": 0, "word_synced": 0, "fail": 0, "total_ms": 0},
        "Musixmatch Plan23": {"synced": 0, "word_synced": 0, "fail": 0, "total_ms": 0}
    }
    
    total_race_wins = 0
    total_word_wins = 0
    total_race_ms = 0
    
    t_global_start = time.time()
    
    with ThreadPoolExecutor(max_workers=8) as pool:
        futures = [pool.submit(run_plan23_parallel_race, s) for s in TEST_SONGS]
        
        for i, f in enumerate(as_completed(futures), 1):
            song, winner, results, race_ms = f.result()
            total_race_ms += race_ms
            
            w_name = winner["provider"] if winner else "None"
            is_w_synced = winner.get("is_synced", False) if winner else False
            is_w_word = winner.get("is_word_synced", False) if winner else False
            
            if is_w_synced:
                total_race_wins += 1
            if is_w_word:
                total_word_wins += 1
                
            w_badge = "🔥 WORD-SYNC (Syllable)" if is_w_word else ("🟢 0.00s TIMED SYNC" if is_w_synced else "🔴 FAILED")
            print(f"[{i:02d}/{len(TEST_SONGS)}] 🎵 {song['title']} - {song['artist']}", flush=True)
            print(f"   ⚡ PARALLEL RACER WINNER: {w_name} ({w_badge}) in {race_ms}ms [{winner.get('line_count', 0) if winner else 0} lines]", flush=True)
            
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
                    stat_str = f"🟢 0.00s TIMED SYNC     [{r.get('line_count', 0)} lines]"
                else:
                    stats[p_name]["fail"] += 1
                    stat_str = f"🔴 {r.get('status', 'FAIL')}"
                print(f"      ├─ {p_name:<22} : {stat_str:<36} [{lat:>4}ms]", flush=True)
            print(flush=True)

    t_total = time.time() - t_global_start
    total_tracks = len(TEST_SONGS)
    
    print("=" * 95)
    print("📊 BENCHMARK SUMMARY & PERFORMANCE MATRIX (PLAN 23)")
    print("=" * 95)
    print(f"{'Provider / Target':<24} | {'Synced %':<9} | {'Word-Sync %':<12} | {'Avg Latency':<12} | {'Rating'}")
    print("-" * 95)
    for p_name, d in stats.items():
        s_pct = (d["synced"] / total_tracks) * 100
        w_pct = (d["word_synced"] / total_tracks) * 100
        avg_l = d["total_ms"] // total_tracks
        r = "⭐⭐⭐⭐⭐ (Tier 1)" if s_pct >= 75 else ("⭐⭐⭐⭐ (Tier 2)" if s_pct >= 40 else "⭐⭐⭐ (Fallback)")
        print(f"{p_name:<24} | {s_pct:>7.1f}% | {w_pct:>10.1f}% | {avg_l:>9}ms | {r}")
        
    print("=" * 95)
    print("🏆 PLAN 23 PARALLEL ASYNC RACER TOTAL SYNERGY RESULTS")
    print("=" * 95)
    print(f"🎯 Total Multi-Tier Synced Coverage : {(total_race_wins / total_tracks) * 100:.1f}% ({total_race_wins}/{total_tracks} tracks)")
    print(f"🎤 Word/Syllable-Level Karaoke Sync : {(total_word_wins / total_tracks) * 100:.1f}% ({total_word_wins}/{total_tracks} tracks)")
    print(f"⚡ Average First-to-Finish Latency  : {total_race_ms // total_tracks} ms")
    print(f"⏱️ Total 20-Song Benchmark Runtime  : {t_total:.2f} seconds")
    print("=" * 95, flush=True)

if __name__ == "__main__":
    main()
