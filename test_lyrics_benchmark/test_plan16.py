import json
import time
import urllib.request
import urllib.parse
import gzip
import re
from concurrent.futures import ThreadPoolExecutor, as_completed

MAC_USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
SPOTIFY_UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Spotify-Desktop/1.2.0"
CHROME_WIN_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

def http_get(url, headers=None, timeout=4):
    req_headers = {
        "User-Agent": MAC_USER_AGENT,
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

def http_get_text(url, headers=None, timeout=4):
    req_headers = {
        "User-Agent": MAC_USER_AGENT,
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
        return raw.decode("utf-8", errors="ignore")

def http_post(url, payload, headers=None, timeout=4):
    data = json.dumps(payload).encode("utf-8")
    req_headers = {
        "Content-Type": "application/json",
        "User-Agent": CHROME_WIN_UA,
        "Accept": "*/*",
        "Accept-Encoding": "gzip, deflate",
        "Origin": "https://music.youtube.com",
        "Referer": "https://music.youtube.com/"
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
# 1. PLAN 16 MUSIXMATCH FORCE-FEED PROTOCOL
# =====================================================================
def sanitize_yt_metadata(title, artist):
    # 1. Remove parentheses / brackets
    clean_title = re.sub(r'\(.*?\)|\[.*?\]|\{.*?\}', '', title)
    # 2. Remove noise keywords
    clean_title = re.sub(r'(?i)(official|audio|video|lyrics|remastered|feat\.?|ft\.?|with).*', '', clean_title)
    # 3. Remove dashes & trailing punctuation
    clean_title = re.sub(r'[\-–—].*', '', clean_title).strip()
    
    # 4. Clean artist (- Topic, Vevo, etc.)
    clean_artist = re.sub(r'(?i)(\s*-\s*Topic|\s*Vevo)', '', artist).strip()
    return clean_title, clean_artist

cached_mxm_token = None

def get_musixmatch_token():
    global cached_mxm_token
    if cached_mxm_token:
        return cached_mxm_token
    try:
        url = "https://apic-desktop.musixmatch.com/ws/1.1/token.get?app_id=web-desktop-app-v1.0"
        headers = {
            "User-Agent": MAC_USER_AGENT,
            "Authority": "apic-desktop.musixmatch.com",
        }
        res = http_get(url, headers=headers, timeout=3)
        token = res.get("message", {}).get("body", {}).get("user_token")
        if token and token != "Upgrade.me":
            cached_mxm_token = token
            return token
    except Exception:
        pass
    cached_mxm_token = "240228000000000000000000000000"
    return cached_mxm_token

def test_plan16_musixmatch(title, artist, duration_sec=0):
    t0 = time.time()
    try:
        token = get_musixmatch_token()
        clean_title, clean_artist = sanitize_yt_metadata(title, artist)
        
        headers = {
            "User-Agent": SPOTIFY_UA,
            "Cookie": f"x-mxm-token-id={token}",
            "Authority": "apic-desktop.musixmatch.com",
            "Referer": "https://desktop.musixmatch.com/"
        }
        
        # Step 1: Query global 'q' with f_subtitle_length & f_subtitle_format=mxm
        q_str = urllib.parse.quote(f"{clean_title} {clean_artist}")
        dur_param = f"&f_subtitle_length={duration_sec}" if duration_sec > 0 else ""
        
        search_url = f"https://apic-desktop.musixmatch.com/ws/1.1/track.search?q={q_str}{dur_param}&f_subtitle_format=mxm&page_size=5&s_track_rating=desc&app_id=web-desktop-app-v1.0&usertoken={token}"
        search_res = http_get(search_url, headers=headers, timeout=3)
        
        s_body = search_res.get("message", {}).get("body", {})
        track_list = s_body.get("track_list", []) if isinstance(s_body, dict) else []
        
        track_id = None
        if track_list and isinstance(track_list, list) and len(track_list) > 0:
            track_id = track_list[0].get("track", {}).get("track_id")
            
        # Fallback: try q_track and q_artist if global q missed
        if not track_id:
            q_t = urllib.parse.quote(clean_title)
            q_a = urllib.parse.quote(clean_artist)
            search_url_fallback = f"https://apic-desktop.musixmatch.com/ws/1.1/track.search?q_track={q_t}&q_artist={q_a}{dur_param}&page_size=5&s_track_rating=desc&app_id=web-desktop-app-v1.0&usertoken={token}"
            search_res_fallback = http_get(search_url_fallback, headers=headers, timeout=3)
            s_body_fallback = search_res_fallback.get("message", {}).get("body", {})
            if isinstance(s_body_fallback, dict):
                t_list = s_body_fallback.get("track_list", [])
                if t_list and isinstance(t_list, list) and len(t_list) > 0:
                    track_id = t_list[0].get("track", {}).get("track_id")
                    
        if not track_id:
            return {"provider": "Musixmatch Force-Feed", "status": "track_not_found", "latency_ms": int((time.time() - t0)*1000)}

        # Step 2: Fetch subtitle_format=mxm
        mxm_url = f"https://apic-desktop.musixmatch.com/ws/1.1/track.subtitle.get?track_id={track_id}&subtitle_format=mxm&app_id=web-desktop-app-v1.0&usertoken={token}"
        mxm_res = http_get(mxm_url, headers=headers, timeout=3)
        mxm_body = mxm_res.get("message", {}).get("body", {}).get("subtitle", {}).get("subtitle_body", "")
        
        latency = int((time.time() - t0)*1000)
        if mxm_body:
            try:
                parsed_mxm = json.loads(mxm_body)
                if isinstance(parsed_mxm, list) and len(parsed_mxm) > 0:
                    return {
                        "provider": "Musixmatch Force-Feed",
                        "status": "mxm (syllable/word)",
                        "is_synced": True,
                        "is_word_synced": True,
                        "line_count": len(parsed_mxm),
                        "sample_line": parsed_mxm[0] if parsed_mxm else {},
                        "latency_ms": latency
                    }
            except Exception:
                pass
                
        # Step 3: Fetch RichSync
        rich_url = f"https://apic-desktop.musixmatch.com/ws/1.1/track.richsync.get?track_id={track_id}&app_id=web-desktop-app-v1.0&usertoken={token}"
        rich_res = http_get(rich_url, headers=headers, timeout=3)
        rich_body = rich_res.get("message", {}).get("body", {}).get("richsync", {}).get("richsync_body", "")
        if rich_body:
            try:
                parsed_rich = json.loads(rich_body)
                return {
                    "provider": "Musixmatch Force-Feed",
                    "status": "richsync (syllable/word)",
                    "is_synced": True,
                    "is_word_synced": True,
                    "line_count": len(parsed_rich),
                    "latency_ms": latency
                }
            except Exception:
                pass

        # Step 4: Fetch standard LRC
        sub_url = f"https://apic-desktop.musixmatch.com/ws/1.1/track.subtitle.get?track_id={track_id}&subtitle_format=lrc&app_id=web-desktop-app-v1.0&usertoken={token}"
        sub_res = http_get(sub_url, headers=headers, timeout=3)
        sub_body = sub_res.get("message", {}).get("body", {}).get("subtitle", {}).get("subtitle_body", "")
        if sub_body:
            lines = [l for l in sub_body.splitlines() if l.strip()]
            return {
                "provider": "Musixmatch Force-Feed",
                "status": "synced (line-level)",
                "is_synced": True,
                "is_word_synced": False,
                "line_count": len(lines),
                "latency_ms": latency
            }
            
        return {"provider": "Musixmatch Force-Feed", "status": "no_subtitles_body", "latency_ms": latency}
    except Exception as e:
        return {"provider": "Musixmatch Force-Feed", "status": f"error: {str(e)[:30]}", "latency_ms": int((time.time() - t0)*1000)}

# =====================================================================
# 2. PLAN 16 YOUTUBE MUSIC WEB_REMIX + X-Goog-Visitor-Id PROTOCOL
# =====================================================================
cached_yt_key = None
cached_visitor_data = None

def init_yt_web_session():
    global cached_yt_key, cached_visitor_data
    if cached_yt_key and cached_visitor_data:
        return cached_yt_key, cached_visitor_data
    cached_yt_key = "AIzaSyC9K4P7wXJkK13vO5HhY6nN"
    cached_visitor_data = "CgtnOHpXSU5MWS1kYyi-maDUBjIoCgJGUhIiEh4SHAsMDg8QERITFBUWFxgZGhscHR4fICEiIyQlJicgRg%3D%3D"
    try:
        main_page = http_get_text("https://music.youtube.com/", headers={"User-Agent": CHROME_WIN_UA}, timeout=2)
        key_match = re.search(r'"INNERTUBE_API_KEY":\s*"([a-zA-Z0-9_\-]+)"', main_page) or re.search(r'key=([a-zA-Z0-9_\-]{30,45})', main_page)
        visitor_match = re.search(r'"visitorData":\s*"([a-zA-Z0-9%_\-]+)"', main_page)
        if key_match:
            cached_yt_key = key_match.group(1)
        if visitor_match:
            cached_visitor_data = visitor_match.group(1)
    except Exception:
        pass
    return cached_yt_key, cached_visitor_data

def test_plan16_ytm_webremix(video_id):
    t0 = time.time()
    try:
        api_key, visitor_data = init_yt_web_session()
        
        headers = {
            "User-Agent": CHROME_WIN_UA,
            "X-Goog-Visitor-Id": visitor_data,
            "Origin": "https://music.youtube.com",
            "Referer": "https://music.youtube.com/"
        }
        
        # Step A: Resolve Lyrics Browse ID (MPLYt_...)
        next_url = f"https://music.youtube.com/youtubei/v1/next?key={api_key}"
        next_payload = {
            "context": {
                "client": {
                    "clientName": "WEB_REMIX",
                    "clientVersion": "1.20240313.01.00",
                    "visitorData": visitor_data,
                    "hl": "en", "gl": "US"
                }
            },
            "videoId": video_id,
            "isAudioOnly": True
        }
        next_resp = http_post(next_url, next_payload, headers=headers, timeout=3)
        tabs = next_resp.get("contents", {}).get("singleColumnMusicWatchNextResultsRenderer", {}).get("tabbedRenderer", {}).get("watchNextTabbedResultsRenderer", {}).get("tabs", [])
        
        lyrics_browse_id = None
        for tab in tabs:
            tab_renderer = tab.get("tabRenderer", {})
            title = tab_renderer.get("title", "")
            if "LYRIC" in title.upper():
                lyrics_browse_id = tab_renderer.get("endpoint", {}).get("browseEndpoint", {}).get("browseId")
                break
                
        if not lyrics_browse_id:
            return {"provider": "YTM WEB_REMIX", "status": "no_lyrics_tab", "latency_ms": int((time.time() - t0)*1000)}

        # Step B: Browse Lyrics with WEB_REMIX + X-Goog-Visitor-Id
        browse_url = f"https://music.youtube.com/youtubei/v1/browse?key={api_key}"
        browse_payload = {
            "context": {
                "client": {
                    "clientName": "WEB_REMIX",
                    "clientVersion": "1.20240313.01.00",
                    "visitorData": visitor_data,
                    "hl": "en", "gl": "US"
                }
            },
            "browseId": lyrics_browse_id
        }
        browse_resp = http_post(browse_url, browse_payload, headers=headers, timeout=3)
        
        # Check for timed lyrics
        timed_lines = []
        for section in browse_resp.get("contents", {}).get("sectionListRenderer", {}).get("contents", []):
            timed_renderer = section.get("musicTimedLyricsRenderer", {}) or section.get("timedLyricsRenderer", {})
            if timed_renderer:
                lines = timed_renderer.get("timedLyricsData", {}).get("lines", []) or timed_renderer.get("lines", [])
                timed_lines.extend(lines)
                
        latency = int((time.time() - t0)*1000)
        if timed_lines:
            return {"provider": "YTM WEB_REMIX", "status": "synced (timed)", "is_synced": True, "line_count": len(timed_lines), "latency_ms": latency}
            
        for section in browse_resp.get("contents", {}).get("sectionListRenderer", {}).get("contents", []):
            shelf = section.get("musicDescriptionShelfRenderer", {})
            if shelf:
                runs = shelf.get("description", {}).get("runs", [])
                text = "".join(r.get("text", "") for r in runs)
                lines = [l for l in text.splitlines() if l.strip()]
                return {"provider": "YTM WEB_REMIX", "status": "plain_text", "is_synced": False, "line_count": len(lines), "latency_ms": latency}
                
        return {"provider": "YTM WEB_REMIX", "status": "empty", "latency_ms": latency}
    except Exception as e:
        return {"provider": "YTM WEB_REMIX", "status": f"error: {str(e)[:30]}", "latency_ms": int((time.time() - t0)*1000)}

# =====================================================================
# 3. LRCLIB (TIER 1 OPEN SPEED RACER)
# =====================================================================
def test_lrclib(title, artist, duration_sec=0):
    t0 = time.time()
    try:
        clean_title, clean_artist = sanitize_yt_metadata(title, artist)
        q_title = urllib.parse.quote(clean_title)
        q_artist = urllib.parse.quote(clean_artist)
        url = f"https://lrclib.net/api/get?track_name={q_title}&artist_name={q_artist}"
        if duration_sec > 0:
            url += f"&duration={duration_sec}"
            
        res = http_get(url, timeout=3)
        synced = res.get("syncedLyrics", "")
        plain = res.get("plainLyrics", "")
        latency = int((time.time() - t0)*1000)
        
        if synced:
            lines = [l for l in synced.splitlines() if l.strip()]
            return {"provider": "LRCLIB", "status": "synced", "is_synced": True, "line_count": len(lines), "latency_ms": latency}
        elif plain:
            lines = [l for l in plain.splitlines() if l.strip()]
            return {"provider": "LRCLIB", "status": "plain_only", "is_synced": False, "line_count": len(lines), "latency_ms": latency}
        else:
            url_search = f"https://lrclib.net/api/search?q={urllib.parse.quote(f'{clean_title} {clean_artist}')}"
            search_res = http_get(url_search, timeout=3)
            if isinstance(search_res, list) and len(search_res) > 0:
                first = search_res[0]
                s = first.get("syncedLyrics", "")
                if s:
                    lines = [l for l in s.splitlines() if l.strip()]
                    return {"provider": "LRCLIB", "status": "synced", "is_synced": True, "line_count": len(lines), "latency_ms": latency}
            return {"provider": "LRCLIB", "status": "not_found", "latency_ms": latency}
    except Exception as e:
        return {"provider": "LRCLIB", "status": f"error: {str(e)[:30]}", "latency_ms": int((time.time() - t0)*1000)}

# =====================================================================
# THE PLAN 16 TOKIO RACER SIMULATION
# =====================================================================
def run_plan16_racer(song):
    title = song["title"]
    artist = song["artist"]
    vid = song["video_id"]
    dur = song["duration"]
    
    t_start = time.time()
    
    with ThreadPoolExecutor(max_workers=3) as executor:
        f_mxm = executor.submit(test_plan16_musixmatch, title, artist, dur)
        f_ytm = executor.submit(test_plan16_ytm_webremix, vid)
        f_lrc = executor.submit(test_lrclib, title, artist, dur)
        
        futures = {f_mxm: "Musixmatch Force-Feed", f_ytm: "YTM WEB_REMIX", f_lrc: "LRCLIB"}
        winner = None
        all_results = {}
        
        for f in as_completed(futures):
            name = futures[f]
            res = f.result()
            all_results[name] = res
            
            if winner is None and res.get("is_synced"):
                winner = res
                
        total_time_ms = int((time.time() - t_start) * 1000)
        return song, winner, all_results, total_time_ms

# =====================================================================
# 20 REAL-WORLD TRACKS WITH REALISTIC NOISY TITLES
# =====================================================================
TEST_SONGS = [
    {"title": "Blinding Lights (Official Audio)", "artist": "The Weeknd", "video_id": "4NRXx6U8ABQ", "duration": 200},
    {"title": "Shape of You [Official Video]", "artist": "Ed Sheeran", "video_id": "JGwWNGJdvx8", "duration": 233},
    {"title": "Bohemian Rhapsody - Remastered 2011", "artist": "Queen - Topic", "video_id": "fJ9rUzIMcZQ", "duration": 354},
    {"title": "Levitating (feat. DaBaby)", "artist": "Dua Lipa", "video_id": "TUVcZfQe-Kw", "duration": 203},
    {"title": "Starboy (Official Music Video)", "artist": "The Weeknd ft. Daft Punk", "video_id": "34Na4j8AVgA", "duration": 230},
    {"title": "As It Was", "artist": "Harry Styles", "video_id": "H5v3kku4y6Q", "duration": 167},
    {"title": "Flowers", "artist": "Miley Cyrus", "video_id": "G7KNmW9a75Y", "duration": 200},
    {"title": "Someone You Loved", "artist": "Lewis Capaldi - Topic", "video_id": "zABLecsR5UE", "duration": 182},
    {"title": "Believer", "artist": "Imagine Dragons", "video_id": "7wtfhZwyrcc", "duration": 204},
    {"title": "Stay (with Justin Bieber)", "artist": "The Kid LAROI", "video_id": "kTJczUoc26U", "duration": 141},
    {"title": "Bad Guy", "artist": "Billie Eilish", "video_id": "DyDfgMOUjCI", "duration": 194},
    {"title": "Save Your Tears (Official Music Video)", "artist": "The Weeknd", "video_id": "XXYlFuWEuKi", "duration": 215},
    {"title": "Heat Waves (Official Video)", "artist": "Glass Animals", "video_id": "mRD0-GxqHVo", "duration": 238},
    {"title": "Watermelon Sugar", "artist": "Harry Styles", "video_id": "E07s5ZYygmg", "duration": 174},
    {"title": "Industry Baby (feat. Jack Harlow)", "artist": "Lil Nas X", "video_id": "UTHLKHL_whs", "duration": 212},
    {"title": "Hotel California - 2013 Remaster", "artist": "Eagles", "video_id": "09839DpTctU", "duration": 390},
    {"title": "Cruel Summer", "artist": "Taylor Swift", "video_id": "ic8j13piAhQ", "duration": 178},
    {"title": "vampire (Official Video)", "artist": "Olivia Rodrigo", "video_id": "RlPNh_PBZb4", "duration": 219},
    {"title": "Counting Stars", "artist": "OneRepublic", "video_id": "hT_nvWreIhg", "duration": 257},
    {"title": "Wake Me Up (Radio Edit)", "artist": "Avicii", "video_id": "IcrbM1l_BoI", "duration": 247}
]

def main():
    print("=" * 92)
    print("🚀 EXTENSIVE LIVE BENCHMARK: PLAN 16 ARCHITECTURE (FORCE-FEED MXM + WEB_REMIX + RACER)")
    print(f"Testing {len(TEST_SONGS)} tracks with YouTube noise tags (Remaster, feat, Official, Topic)...")
    print("=" * 92)
    
    init_yt_web_session()
    get_musixmatch_token()
    
    provider_stats = {
        "Musixmatch Force-Feed": {"synced": 0, "word_synced": 0, "plain": 0, "fail": 0, "total_ms": 0},
        "YTM WEB_REMIX": {"synced": 0, "word_synced": 0, "plain": 0, "fail": 0, "total_ms": 0},
        "LRCLIB": {"synced": 0, "word_synced": 0, "plain": 0, "fail": 0, "total_ms": 0}
    }
    
    synced_racer_wins = 0
    word_synced_count = 0
    total_racer_ms = 0
    
    t_global_start = time.time()
    
    with ThreadPoolExecutor(max_workers=8) as pool:
        futures = [pool.submit(run_plan16_racer, s) for s in TEST_SONGS]
        
        for i, f in enumerate(as_completed(futures), 1):
            song, winner, all_res, racer_latency_ms = f.result()
            total_racer_ms += racer_latency_ms
            
            w_name = winner["provider"] if winner else "None"
            is_synced = winner.get("is_synced", False) if winner else False
            is_word = winner.get("is_word_synced", False) if winner else False
            
            if is_synced:
                synced_racer_wins += 1
            if is_word:
                word_synced_count += 1
                
            w_badge = "🔥 WORD-SYNC (MXM)" if is_word else ("🟢 LINE-SYNC" if is_synced else "🔴 FAILED")
            print(f"[{i:02d}/{len(TEST_SONGS)}] 🎵 {song['title']} - {song['artist']}")
            print(f"   ⚡ TOKIO RACER WINNER: {w_name} ({w_badge}) in {racer_latency_ms}ms [{winner.get('line_count', 0) if winner else 0} lines]")
            
            for p_name, r in all_res.items():
                lat = r.get("latency_ms", 0)
                provider_stats[p_name]["total_ms"] += lat
                
                if r.get("is_word_synced"):
                    provider_stats[p_name]["word_synced"] += 1
                    provider_stats[p_name]["synced"] += 1
                    stat_str = f"🔥 WORD-SYNC ({r.get('line_count', 0)} lines)"
                elif r.get("is_synced"):
                    provider_stats[p_name]["synced"] += 1
                    stat_str = f"🟢 LINE-SYNC ({r.get('line_count', 0)} lines)"
                elif r.get("status") in ("plain_text", "plain_only"):
                    provider_stats[p_name]["plain"] += 1
                    stat_str = f"🟡 PLAIN     ({r.get('line_count', 0)} lines)"
                else:
                    provider_stats[p_name]["fail"] += 1
                    stat_str = f"🔴 {r.get('status', 'FAIL')}"
                print(f"      ├─ {p_name:<24} : {stat_str:<32} [{lat:>4}ms]")
            print()

    total_time = time.time() - t_global_start
    total_tracks = len(TEST_SONGS)
    
    print("=" * 92)
    print("📊 INDIVIDUAL PROVIDER BENCHMARK PERFORMANCE (PLAN 16)")
    print("=" * 92)
    print(f"{'Provider / Attack Vector':<26} | {'Synced %':<9} | {'Word-Sync %':<12} | {'Avg Latency':<12} | {'Rating'}")
    print("-" * 92)
    for p_name, d in provider_stats.items():
        s_pct = (d["synced"] / total_tracks) * 100
        w_pct = (d["word_synced"] / total_tracks) * 100
        avg_l = d["total_ms"] // total_tracks
        r = "⭐⭐⭐⭐⭐ (Tier 1)" if s_pct >= 80 else ("⭐⭐⭐⭐ (Tier 2)" if s_pct >= 40 else "⭐⭐⭐ (Fallback)")
        print(f"{p_name:<26} | {s_pct:>7.1f}% | {w_pct:>10.1f}% | {avg_l:>9}ms | {r}")
        
    print("=" * 92)
    print("🏆 PLAN 16 TOKIO ASYNC RACER TOTAL SYNERGY RESULTS")
    print("=" * 92)
    print(f"🎯 Total Multi-Tier Synced Coverage : {(synced_racer_wins / total_tracks) * 100:.1f}% ({synced_racer_wins}/{total_tracks} tracks)")
    print(f"🎤 Word/Syllable-Level Karaoke Sync : {(word_synced_count / total_tracks) * 100:.1f}% ({word_synced_count}/{total_tracks} tracks)")
    print(f"⚡ Average First-to-Finish Latency  : {total_racer_ms // total_tracks} ms")
    print(f"⏱️ Total 20-Song Benchmark Runtime  : {total_time:.2f} seconds")
    print("=" * 92)

if __name__ == "__main__":
    main()
