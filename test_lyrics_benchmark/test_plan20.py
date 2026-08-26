import json
import time
import urllib.request
import urllib.parse
import gzip
import re
from concurrent.futures import ThreadPoolExecutor, as_completed

USER_AGENT_DESKTOP = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
ANDROID_YTM_UA = "com.google.android.apps.youtube.music/7.21.50 (Linux; U; Android 14)"
YTM_API_KEY = "AIzaSyC1xlRQImGslL28Q8HqTqD_o-w-r2Q_Z4"

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

def sanitize_title(title):
    re_noise = r"(?i)\(official.*?\)|\[official.*?\]|\(audio.*?\)|\[audio.*?\]|\(video.*?\)|\[video.*?\]|\(.*remaster.*?\)|\[.*remaster.*?\]|\(.*lyric.*?\)|\[.*lyric.*?\]|\(.*feat.*?\)|\[.*feat.*?\]"
    cleaned = re.sub(re_noise, "", title).strip()
    cleaned = re.sub(r'[\-–—].*', '', cleaned).strip()
    return cleaned if cleaned else title

def sanitize_artist(artist):
    cleaned = re.sub(r'(?i)(\s*-\s*Topic|\s*VEVO|\s*Official)', '', artist).strip()
    cleaned = re.sub(r'(?i)(feat\.?|ft\.?).*', '', cleaned).strip()
    return cleaned if cleaned else artist

# =====================================================================
# 1. PLAN 20 MUSIXMATCH 100 ENGINE (4-TIER + RETRY + 10 CANDIDATES)
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

def test_plan20_musixmatch(title, artist):
    t0 = time.time()
    try:
        token = get_musixmatch_token()
        clean_title = sanitize_title(title)
        clean_artist = sanitize_artist(artist)
        
        headers = {
            "User-Agent": USER_AGENT_DESKTOP,
            "Authority": "apic-desktop.musixmatch.com",
            "Cookie": f"x-mxm-token-id={token}"
        }
        
        # Tier 1: Direct Matcher Subtitle Get
        q_t = urllib.parse.quote(clean_title)
        q_a = urllib.parse.quote(clean_artist)
        matcher_url = f"https://apic-desktop.musixmatch.com/ws/1.1/matcher.subtitle.get?format=json&subtitle_format=mxm&app_id=web-desktop-app-v1.0&usertoken={token}&q_track={q_t}&q_artist={q_a}"
        
        try:
            match_res = http_get(matcher_url, headers=headers, timeout=3)
            match_body = match_res.get("message", {}).get("body", {})
            if isinstance(match_body, dict):
                sub_raw = match_body.get("subtitle", {}).get("subtitle_body", "")
                if sub_raw:
                    latency = int((time.time() - t0)*1000)
                    try:
                        parsed_mxm = json.loads(sub_raw)
                        if isinstance(parsed_mxm, list) and len(parsed_mxm) > 0:
                            return {
                                "provider": "Musixmatch 100",
                                "status": "mxm (syllable)",
                                "is_synced": True,
                                "is_word_synced": True,
                                "line_count": len(parsed_mxm),
                                "latency_ms": latency
                            }
                    except Exception:
                        pass
                    lines = [l for l in sub_raw.splitlines() if l.strip()]
                    return {
                        "provider": "Musixmatch 100",
                        "status": "synced (line-level)",
                        "is_synced": True,
                        "is_word_synced": False,
                        "line_count": len(lines),
                        "latency_ms": latency
                    }
        except Exception:
            pass

        # Multi-Candidate Search Cascade (4 Tiers)
        queries = [
            f"q_track={q_t}&q_artist={q_a}",
            f"q={urllib.parse.quote(f'{clean_title} {clean_artist}')}",
            f"q_track={q_t}",
            f"q={urllib.parse.quote(clean_title)}"
        ]
        
        for q_params in queries:
            search_url = f"https://apic-desktop.musixmatch.com/ws/1.1/track.search?format=json&page_size=10&s_track_rating=desc&app_id=web-desktop-app-v1.0&usertoken={token}&{q_params}"
            try:
                search_res = http_get(search_url, headers=headers, timeout=3)
                s_body = search_res.get("message", {}).get("body", {})
                track_list = s_body.get("track_list", []) if isinstance(s_body, dict) else []
                
                for item in track_list:
                    if isinstance(item, dict):
                        track = item.get("track", {})
                        track_id = track.get("track_id", 0)
                        has_richsync = track.get("has_richsync", 0)
                        has_subtitles = track.get("has_subtitles", 0)
                        
                        if track_id > 0:
                            # 1. Try RichSync (Word-level)
                            if has_richsync == 1:
                                rich_url = f"https://apic-desktop.musixmatch.com/ws/1.1/track.richsync.get?format=json&subtitle_format=mxm&app_id=web-desktop-app-v1.0&usertoken={token}&track_id={track_id}"
                                rich_res = http_get(rich_url, headers=headers, timeout=3)
                                rich_dict = rich_res.get("message", {}).get("body", {})
                                if isinstance(rich_dict, dict):
                                    rich_raw = rich_dict.get("richsync", {}).get("richsync_body", "")
                                    if rich_raw:
                                        try:
                                            parsed = json.loads(rich_raw)
                                            return {
                                                "provider": "Musixmatch 100",
                                                "status": "richsync (syllable)",
                                                "is_synced": True,
                                                "is_word_synced": True,
                                                "line_count": len(parsed),
                                                "latency_ms": int((time.time() - t0)*1000)
                                            }
                                        except Exception:
                                            pass

                            # 2. Try Subtitle (Line/MXM)
                            if has_subtitles == 1:
                                sub_url = f"https://apic-desktop.musixmatch.com/ws/1.1/track.subtitle.get?format=json&subtitle_format=mxm&app_id=web-desktop-app-v1.0&usertoken={token}&track_id={track_id}"
                                sub_res = http_get(sub_url, headers=headers, timeout=3)
                                sub_dict = sub_res.get("message", {}).get("body", {})
                                if isinstance(sub_dict, dict):
                                    sub_body = sub_dict.get("subtitle", {}).get("subtitle_body", "")
                                    if sub_body:
                                        latency = int((time.time() - t0)*1000)
                                        try:
                                            parsed_mxm = json.loads(sub_body)
                                            if isinstance(parsed_mxm, list) and len(parsed_mxm) > 0:
                                                return {
                                                    "provider": "Musixmatch 100",
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
                                            "provider": "Musixmatch 100",
                                            "status": "synced (line-level)",
                                            "is_synced": True,
                                            "is_word_synced": False,
                                            "line_count": len(lines),
                                            "latency_ms": latency
                                        }
            except Exception:
                continue

        return {"provider": "Musixmatch 100", "status": "not_found", "latency_ms": int((time.time() - t0)*1000)}
    except Exception as e:
        return {"provider": "Musixmatch 100", "status": f"error: {str(e)[:30]}", "latency_ms": int((time.time() - t0)*1000)}

# =====================================================================
# 2. PLAN 20 YOUTUBE MUSIC ANDROID_MUSIC SYNCED TIMEDLYRICS ENGINE
# =====================================================================
def test_plan20_ytmusic_synced_timedlyrics(video_id):
    t0 = time.time()
    try:
        # Step 1: Query Next with WEB_REMIX to extract MPLYt_ ID
        next_url = f"https://music.youtube.com/youtubei/v1/next?key={YTM_API_KEY}&prettyPrint=false"
        next_payload = {
            "context": {
                "client": {
                    "clientName": "WEB_REMIX",
                    "clientVersion": "1.20240401.01.00",
                    "hl": "en",
                    "gl": "US"
                }
            },
            "videoId": video_id,
            "isAudioOnly": True
        }
        headers_web = {
            "User-Agent": USER_AGENT_DESKTOP,
            "Origin": "https://music.youtube.com",
            "Referer": "https://music.youtube.com/"
        }
        
        next_res = http_post(next_url, next_payload, headers=headers_web, timeout=3)
        tabs = (
            next_res.get("contents", {}).get("singleColumnMusicWatchNextResultsRenderer", {}).get("tabbedRenderer", {}).get("watchNextTabbedResultsRenderer", {}).get("tabs", [])
            or next_res.get("contents", {}).get("singleColumnMusicWatchNextResultsRenderer", {}).get("tabRenderer", {}).get("tabs", [])
            or next_res.get("contents", {}).get("twoColumnBrowseResultsRenderer", {}).get("tabs", [])
        )
        
        lyric_browse_id = None
        for tab in tabs:
            tab_r = tab.get("tabRenderer", {})
            title = tab_r.get("title", "")
            endpoint = tab_r.get("endpoint", {}).get("browseEndpoint", {}).get("browseId", "")
            if "LYRIC" in title.upper() or endpoint.startswith("MPLYt_"):
                lyric_browse_id = endpoint
                break
                
        if not lyric_browse_id:
            return {"provider": "YouTube Music Synced", "status": "no_lyrics_tab", "latency_ms": int((time.time() - t0)*1000)}

        # Step 2: Query browse with ANDROID_MUSIC (7.21.50) context
        browse_url = f"https://music.youtube.com/youtubei/v1/browse?key={YTM_API_KEY}&prettyPrint=false"
        browse_payload = {
            "context": {
                "client": {
                    "clientName": "ANDROID_MUSIC",
                    "clientVersion": "7.21.50",
                    "hl": "en",
                    "gl": "US"
                }
            },
            "browseId": lyric_browse_id
        }
        headers_android = {
            "User-Agent": ANDROID_YTM_UA,
            "Content-Type": "application/json"
        }
        
        browse_res = http_post(browse_url, browse_payload, headers=headers_android, timeout=3)
        
        # Step 3: Extract timedLyricsData model
        # Search anywhere in JSON tree for timedLyricsData
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
        latency = int((time.time() - t0)*1000)
        
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
                    "provider": "YouTube Music Synced",
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
                "provider": "YouTube Music Synced",
                "status": "plain_text",
                "is_synced": False,
                "line_count": len(lines),
                "latency_ms": latency
            }
            
        return {"provider": "YouTube Music Synced", "status": "empty_shelf", "latency_ms": latency}
    except Exception as e:
        return {"provider": "YouTube Music Synced", "status": f"error: {str(e)[:30]}", "latency_ms": int((time.time() - t0)*1000)}

# =====================================================================
# 20 DIVERSE REAL-WORLD TRACKS (ATVs & OMVs with Noise Tokens)
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
    {"title": "vampire (Official Video)", "artist": "Olivia Rodrigo", "video_id": "RlPNh_PBZb4"},
    {"title": "Counting Stars", "artist": "OneRepublic", "video_id": "hT_nvWreIhg"},
    {"title": "Wake Me Up (Radio Edit)", "artist": "Avicii", "video_id": "IcrbM1l_BoI"}
]

def benchmark_track(song):
    title = song["title"]
    artist = song["artist"]
    vid = song["video_id"]
    
    with ThreadPoolExecutor(max_workers=2) as executor:
        f_mxm = executor.submit(test_plan20_musixmatch, title, artist)
        f_ytm = executor.submit(test_plan20_ytmusic_synced_timedlyrics, vid)
        
        res_mxm = f_mxm.result()
        res_ytm = f_ytm.result()
        
    return song, [res_mxm, res_ytm]

def main():
    print("=" * 95)
    print("🚀 LIVE BENCHMARK: PLAN 20 (MUSIXMATCH 100 & YOUTUBE MUSIC ANDROID_MUSIC)")
    print(f"Testing {len(TEST_SONGS)} tracks across Musixmatch 4-Tier + YouTube Music TimedLyrics...")
    print("=" * 95, flush=True)
    
    get_musixmatch_token()
    
    stats = {
        "Musixmatch 100": {"synced": 0, "word_synced": 0, "plain": 0, "fail": 0, "total_ms": 0},
        "YouTube Music Synced": {"synced": 0, "word_synced": 0, "plain": 0, "fail": 0, "total_ms": 0}
    }
    
    t_start = time.time()
    
    with ThreadPoolExecutor(max_workers=8) as pool:
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
    print("📊 BENCHMARK SUMMARY & PERFORMANCE MATRIX (PLAN 20)")
    print("=" * 95)
    print(f"{'Provider / Target':<24} | {'Synced %':<9} | {'Word-Sync %':<12} | {'Plain %':<9} | {'Avg Latency':<12} | {'Rating'}")
    print("-" * 95)
    for p_name, d in stats.items():
        s_pct = (d["synced"] / total_tracks) * 100
        w_pct = (d["word_synced"] / total_tracks) * 100
        p_pct = (d["plain"] / total_tracks) * 100
        avg_l = d["total_ms"] // total_tracks
        r = "⭐⭐⭐⭐⭐ (Tier 1)" if s_pct >= 85 else ("⭐⭐⭐⭐ (Tier 2)" if s_pct >= 40 else "⭐⭐⭐ (Fallback)")
        print(f"{p_name:<24} | {s_pct:>7.1f}% | {w_pct:>10.1f}% | {p_pct:>7.1f}% | {avg_l:>9}ms | {r}")
        
    print("=" * 95)
    print(f"⏱️ Total Benchmark Runtime: {t_total:.2f} seconds across {total_tracks} tracks")
    print("=" * 95, flush=True)

if __name__ == "__main__":
    main()
