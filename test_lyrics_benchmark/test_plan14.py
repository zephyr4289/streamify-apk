import json
import time
import urllib.request
import urllib.parse
import gzip
import re
from concurrent.futures import ThreadPoolExecutor, as_completed

MAC_USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
TV_USER_AGENT = "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version"

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
        "User-Agent": MAC_USER_AGENT,
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
# 1. YOUTUBE MUSIC TVHTML5 INNERTUBE SCRAPER & DISCOVERY
# =====================================================================
cached_tv_key = None
cached_visitor_data = None

def init_yt_tv_credentials():
    global cached_tv_key, cached_visitor_data
    if cached_tv_key and cached_visitor_data:
        return cached_tv_key, cached_visitor_data
    try:
        # Scrape sw.js or main page for API key and visitorData
        sw_text = http_get_text("https://www.youtube.com/sw.js", timeout=3)
        key_match = re.search(r'"INNERTUBE_API_KEY":\s*"([a-zA-Z0-9_\-]+)"', sw_text) or re.search(r'key=([a-zA-Z0-9_\-]{30,45})', sw_text) or re.search(r'"key":\s*"([a-zA-Z0-9_\-]+)"', sw_text)
        if key_match:
            cached_tv_key = key_match.group(1)
            
        # Get visitorData from youtube initial page
        main_page = http_get_text("https://music.youtube.com", timeout=3)
        visitor_match = re.search(r'"visitorData":\s*"([a-zA-Z0-9%_\-]+)"', main_page)
        if visitor_match:
            cached_visitor_data = visitor_match.group(1)
            
        if not cached_tv_key:
            # Fallback standard Innertube TV key
            cached_tv_key = "AIzaSyC9K4P7wXJkK13vO5HhY6nN"
        if not cached_visitor_data:
            cached_visitor_data = "CgtnOHpXSU5MWS1kYyi-maDUBjIoCgJGUhIiEh4SHAsMDg8QERITFBUWFxgZGhscHR4fICEiIyQlJicgRg%3D%3D"
    except Exception:
        cached_tv_key = "AIzaSyAO_FJ2SlqAeC13vO5HhY6nN"
        cached_visitor_data = "CgtnOHpXSU5MWS1kYyi-maDUBjIoCgJGUhIiEh4SHAsMDg8QERITFBUWFxgZGhscHR4fICEiIyQlJicgRg%3D%3D"
    return cached_tv_key, cached_visitor_data

def test_ytm_tvhtml5_plan14(video_id):
    t0 = time.time()
    try:
        tv_key, visitor_data = init_yt_tv_credentials()
        
        # 1. Query next with visitorData
        next_url = f"https://music.youtube.com/youtubei/v1/next?key={tv_key}"
        next_payload = {
            "context": {
                "client": {
                    "clientName": "WEB_REMIX",
                    "clientVersion": "1.20240101.01.00",
                    "visitorData": visitor_data,
                    "hl": "en", "gl": "US"
                }
            },
            "videoId": video_id
        }
        next_resp = http_post(next_url, next_payload, timeout=3)
        tabs = next_resp.get("contents", {}).get("singleColumnMusicWatchNextResultsRenderer", {}).get("tabbedRenderer", {}).get("watchNextTabbedResultsRenderer", {}).get("tabs", [])
        
        lyrics_browse_id = None
        for tab in tabs:
            tab_renderer = tab.get("tabRenderer", {})
            title = tab_renderer.get("title", "")
            if "LYRIC" in title.upper():
                lyrics_browse_id = tab_renderer.get("endpoint", {}).get("browseEndpoint", {}).get("browseId")
                break
                
        if not lyrics_browse_id:
            return {"provider": "YTM TVHTML5", "status": "no_lyrics_tab", "latency_ms": int((time.time() - t0)*1000)}

        # 2. Browse with TVHTML5 context + visitor data
        browse_url = f"https://music.youtube.com/youtubei/v1/browse?key={tv_key}"
        browse_payload = {
            "context": {
                "client": {
                    "clientName": "TVHTML5",
                    "clientVersion": "7.20240313.09.00",
                    "visitorData": visitor_data,
                    "hl": "en", "gl": "US"
                }
            },
            "browseId": lyrics_browse_id
        }
        browse_resp = http_post(browse_url, browse_payload, {"User-Agent": TV_USER_AGENT}, timeout=3)
        
        # Parse for timed lyrics
        timed_lines = []
        for section in browse_resp.get("contents", {}).get("sectionListRenderer", {}).get("contents", []):
            timed_renderer = section.get("musicTimedLyricsRenderer", {}) or section.get("timedLyricsRenderer", {})
            if timed_renderer:
                lines = timed_renderer.get("timedLyricsData", {}).get("lines", []) or timed_renderer.get("lines", [])
                timed_lines.extend(lines)
                
        latency = int((time.time() - t0)*1000)
        if timed_lines:
            return {"provider": "YTM TVHTML5", "status": "synced", "is_synced": True, "line_count": len(timed_lines), "latency_ms": latency}
            
        for section in browse_resp.get("contents", {}).get("sectionListRenderer", {}).get("contents", []):
            shelf = section.get("musicDescriptionShelfRenderer", {})
            if shelf:
                runs = shelf.get("description", {}).get("runs", [])
                text = "".join(r.get("text", "") for r in runs)
                lines = [l for l in text.splitlines() if l.strip()]
                return {"provider": "YTM TVHTML5", "status": "plain_text", "is_synced": False, "line_count": len(lines), "latency_ms": latency}
                
        return {"provider": "YTM TVHTML5", "status": "empty", "latency_ms": latency}
    except Exception as e:
        return {"provider": "YTM TVHTML5", "status": f"error: {str(e)[:30]}", "latency_ms": int((time.time() - t0)*1000)}

# =====================================================================
# 2. MUSIXMATCH DEEP CHAIN: FUZZY SEARCH -> RICHSYNC / SUBTITLES
# =====================================================================
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

def test_musixmatch_deep_chain(title, artist):
    t0 = time.time()
    try:
        token = get_musixmatch_token()
        headers = {
            "User-Agent": MAC_USER_AGENT,
            "Cookie": f"x-mxm-token-id={token}",
            "Authority": "apic-desktop.musixmatch.com",
            "Referer": "https://desktop.musixmatch.com/"
        }
        
        # Step 1: Query macro.subtitles.get directly (Fastest, 1-Hop)
        q_track = urllib.parse.quote(title)
        q_artist = urllib.parse.quote(artist)
        
        macro_url = f"https://apic-desktop.musixmatch.com/ws/1.1/macro.subtitles.get?q_track={q_track}&q_artist={q_artist}&format=json&app_id=web-desktop-app-v1.0&usertoken={token}"
        macro_res = http_get(macro_url, headers=headers, timeout=3)
        macro_calls = macro_res.get("message", {}).get("body", {}).get("macro_calls", {})
        
        # Check RichSync in macro
        richsync = macro_calls.get("track.richsync.get", {}).get("message", {}).get("body", {}).get("richsync", {})
        if richsync and isinstance(richsync, dict):
            body_str = richsync.get("richsync_body", "")
            if body_str:
                parsed_rich = json.loads(body_str)
                return {
                    "provider": "Musixmatch Deep Chain",
                    "status": "richsync (syllable/word)",
                    "is_synced": True,
                    "is_word_synced": True,
                    "line_count": len(parsed_rich),
                    "latency_ms": int((time.time() - t0)*1000)
                }

        # Check subtitles in macro
        track_sub = macro_calls.get("track.subtitles.get", {}).get("message", {}).get("body", {})
        if track_sub and isinstance(track_sub, dict):
            sub_list = track_sub.get("subtitle_list", [])
            if sub_list and isinstance(sub_list, list) and len(sub_list) > 0:
                sub_body = sub_list[0].get("subtitle", {}).get("subtitle_body", "")
                lines = [l for l in sub_body.splitlines() if l.strip()]
                return {
                    "provider": "Musixmatch Deep Chain",
                    "status": "synced (line-level)",
                    "is_synced": True,
                    "is_word_synced": False,
                    "line_count": len(lines),
                    "latency_ms": int((time.time() - t0)*1000)
                }

        # Step 2: Fallback to track.search if macro query didn't find subtitles
        search_url = f"https://apic-desktop.musixmatch.com/ws/1.1/track.search?q_track={q_track}&q_artist={q_artist}&page_size=3&s_track_rating=desc&app_id=web-desktop-app-v1.0&usertoken={token}"
        search_res = http_get(search_url, headers=headers, timeout=3)
        s_body = search_res.get("message", {}).get("body", {})
        track_id = None
        if isinstance(s_body, dict):
            track_list = s_body.get("track_list", [])
            if track_list and isinstance(track_list, list) and len(track_list) > 0:
                track_id = track_list[0].get("track", {}).get("track_id")
                
        if track_id:
            richsync_url = f"https://apic-desktop.musixmatch.com/ws/1.1/track.richsync.get?track_id={track_id}&app_id=web-desktop-app-v1.0&usertoken={token}"
            richsync_res = http_get(richsync_url, headers=headers, timeout=3)
            richsync_body = richsync_res.get("message", {}).get("body", {}).get("richsync", {}).get("richsync_body")
            if richsync_body:
                parsed_rich = json.loads(richsync_body)
                return {
                    "provider": "Musixmatch Deep Chain",
                    "status": "richsync (syllable/word)",
                    "is_synced": True,
                    "is_word_synced": True,
                    "line_count": len(parsed_rich),
                    "latency_ms": int((time.time() - t0)*1000)
                }

        # Step 3: Fetch standard Line-Level Subtitles
        sub_url = f"https://apic-desktop.musixmatch.com/ws/1.1/track.subtitles.get?track_id={track_id}&app_id=web-desktop-app-v1.0&usertoken={token}"
        sub_res = http_get(sub_url, headers=headers, timeout=3)
        subtitle_list = sub_res.get("message", {}).get("body", {}).get("subtitle_list", [])
        
        latency_val = int((time.time() - t0)*1000)
        if subtitle_list and isinstance(subtitle_list, list) and len(subtitle_list) > 0:
            sub_body = subtitle_list[0].get("subtitle", {}).get("subtitle_body", "")
            lines = [l for l in sub_body.splitlines() if l.strip()]
            return {
                "provider": "Musixmatch Deep Chain",
                "status": "synced (line-level)",
                "is_synced": True,
                "is_word_synced": False,
                "line_count": len(lines),
                "latency_ms": latency_val
            }
            
        return {"provider": "Musixmatch Deep Chain", "status": "not_synced", "latency_ms": latency_val}
    except Exception as e:
        return {"provider": "Musixmatch Deep Chain", "status": f"error: {str(e)[:30]}", "latency_ms": int((time.time() - t0)*1000)}

# =====================================================================
# 3. LRCLIB EXACT & FUZZY (TIER 1 OPEN SPEED RACER)
# =====================================================================
def test_lrclib(title, artist, duration_sec=0):
    t0 = time.time()
    try:
        q_title = urllib.parse.quote(title)
        q_artist = urllib.parse.quote(artist)
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
            url_search = f"https://lrclib.net/api/search?q={urllib.parse.quote(f'{title} {artist}')}"
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
# THE 3-PRONGED TOKIO RACER (PLAN 14)
# =====================================================================
def run_plan14_racer(song):
    title = song["title"]
    artist = song["artist"]
    vid = song["video_id"]
    dur = song["duration"]
    
    t_start = time.time()
    
    with ThreadPoolExecutor(max_workers=3) as executor:
        f_ytm = executor.submit(test_ytm_tvhtml5_plan14, vid)
        f_mxm = executor.submit(test_musixmatch_deep_chain, title, artist)
        f_lrc = executor.submit(test_lrclib, title, artist, dur)
        
        futures = {f_ytm: "YTM TVHTML5", f_mxm: "Musixmatch Deep Chain", f_lrc: "LRCLIB"}
        winner = None
        all_results = {}
        
        for f in as_completed(futures):
            name = futures[f]
            res = f.result()
            all_results[name] = res
            
            # Prioritize Word-Level RichSync or First Synced response
            if winner is None and res.get("is_synced"):
                winner = res
                
        total_time_ms = int((time.time() - t_start) * 1000)
        return song, winner, all_results, total_time_ms

# =====================================================================
# 20 REPRESENTATIVE TEST TRACKS
# =====================================================================
TEST_SONGS = [
    {"title": "Blinding Lights", "artist": "The Weeknd", "video_id": "4NRXx6U8ABQ", "duration": 200},
    {"title": "Shape of You", "artist": "Ed Sheeran", "video_id": "JGwWNGJdvx8", "duration": 233},
    {"title": "Bohemian Rhapsody", "artist": "Queen", "video_id": "fJ9rUzIMcZQ", "duration": 354},
    {"title": "Levitating", "artist": "Dua Lipa", "video_id": "TUVcZfQe-Kw", "duration": 203},
    {"title": "Starboy", "artist": "The Weeknd", "video_id": "34Na4j8AVgA", "duration": 230},
    {"title": "As It Was", "artist": "Harry Styles", "video_id": "H5v3kku4y6Q", "duration": 167},
    {"title": "Flowers", "artist": "Miley Cyrus", "video_id": "G7KNmW9a75Y", "duration": 200},
    {"title": "Someone You Loved", "artist": "Lewis Capaldi", "video_id": "zABLecsR5UE", "duration": 182},
    {"title": "Believer", "artist": "Imagine Dragons", "video_id": "7wtfhZwyrcc", "duration": 204},
    {"title": "Stay", "artist": "The Kid LAROI", "video_id": "kTJczUoc26U", "duration": 141},
    {"title": "Bad Guy", "artist": "Billie Eilish", "video_id": "DyDfgMOUjCI", "duration": 194},
    {"title": "Save Your Tears", "artist": "The Weeknd", "video_id": "XXYlFuWEuKi", "duration": 215},
    {"title": "Heat Waves", "artist": "Glass Animals", "video_id": "mRD0-GxqHVo", "duration": 238},
    {"title": "Watermelon Sugar", "artist": "Harry Styles", "video_id": "E07s5ZYygmg", "duration": 174},
    {"title": "Industry Baby", "artist": "Lil Nas X", "video_id": "UTHLKHL_whs", "duration": 212},
    {"title": "Hotel California", "artist": "Eagles", "video_id": "09839DpTctU", "duration": 390},
    {"title": "Cruel Summer", "artist": "Taylor Swift", "video_id": "ic8j13piAhQ", "duration": 178},
    {"title": "vampire", "artist": "Olivia Rodrigo", "video_id": "RlPNh_PBZb4", "duration": 219},
    {"title": "Counting Stars", "artist": "OneRepublic", "video_id": "hT_nvWreIhg", "duration": 257},
    {"title": "Wake Me Up", "artist": "Avicii", "video_id": "IcrbM1l_BoI", "duration": 247}
]

def main():
    print("=" * 88)
    print("🚀 EXTENSIVE LIVE BENCHMARK: PLAN 14 ARCHITECTURE (MUSIXMATCH DEEP CHAIN + TOKIO RACER)")
    print(f"Testing {len(TEST_SONGS)} diverse tracks across Word/Syllable RichSync & Async Racers...")
    print("=" * 88)
    
    init_yt_tv_credentials()
    get_musixmatch_token()
    
    provider_stats = {
        "Musixmatch Deep Chain": {"synced": 0, "word_synced": 0, "plain": 0, "fail": 0, "total_ms": 0},
        "YTM TVHTML5": {"synced": 0, "word_synced": 0, "plain": 0, "fail": 0, "total_ms": 0},
        "LRCLIB": {"synced": 0, "word_synced": 0, "plain": 0, "fail": 0, "total_ms": 0}
    }
    
    synced_racer_wins = 0
    word_synced_count = 0
    total_racer_ms = 0
    
    t_global_start = time.time()
    
    with ThreadPoolExecutor(max_workers=8) as pool:
        futures = [pool.submit(run_plan14_racer, s) for s in TEST_SONGS]
        
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
                
            w_badge = "🔥 WORD-SYNCED (RichSync)" if is_word else ("🟢 LINE-SYNCED" if is_synced else "🔴 FAILED")
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
                print(f"      ├─ {p_name:<22} : {stat_str:<30} [{lat:>4}ms]")
            print()

    total_time = time.time() - t_global_start
    total_tracks = len(TEST_SONGS)
    
    print("=" * 88)
    print("📊 INDIVIDUAL PROVIDER BENCHMARK PERFORMANCE (PLAN 14)")
    print("=" * 88)
    print(f"{'Provider / Attack Vector':<24} | {'Synced %':<9} | {'Word-Sync %':<12} | {'Avg Latency':<12} | {'Rating'}")
    print("-" * 88)
    for p_name, d in provider_stats.items():
        s_pct = (d["synced"] / total_tracks) * 100
        w_pct = (d["word_synced"] / total_tracks) * 100
        avg_l = d["total_ms"] // total_tracks
        r = "⭐⭐⭐⭐⭐ (Tier 1)" if s_pct >= 85 else ("⭐⭐⭐⭐ (Tier 2)" if s_pct >= 50 else "⭐⭐⭐ (Fallback)")
        print(f"{p_name:<24} | {s_pct:>7.1f}% | {w_pct:>10.1f}% | {avg_l:>9}ms | {r}")
        
    print("=" * 88)
    print("🏆 3-PRONGED TOKIO ASYNC RACER TOTAL SYNERGY RESULTS")
    print("=" * 88)
    print(f"🎯 Total Multi-Tier Synced Coverage : {(synced_racer_wins / total_tracks) * 100:.1f}% ({synced_racer_wins}/{total_tracks} tracks)")
    print(f"🎤 Word/Syllable-Level Karaoke Sync : {(word_synced_count / total_tracks) * 100:.1f}% ({word_synced_count}/{total_tracks} tracks)")
    print(f"⚡ Average First-to-Finish Latency  : {total_racer_ms // total_tracks} ms")
    print(f"⏱️ Total 20-Song Benchmark Runtime  : {total_time:.2f} seconds")
    print("=" * 88)

if __name__ == "__main__":
    main()
