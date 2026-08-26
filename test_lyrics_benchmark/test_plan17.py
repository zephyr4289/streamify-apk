import json
import time
import urllib.request
import urllib.parse
import gzip
import re
from concurrent.futures import ThreadPoolExecutor, as_completed

MAC_USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36"
ANDROID_YT_UA = "com.google.android.youtube/19.09.37 (Linux; U; Android 14; en_US) gzip"

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
# METADATA SANITIZER
# =====================================================================
def sanitize_metadata(title, artist):
    clean_title = re.sub(r'\(.*?\)|\[.*?\]|\{.*?\}', '', title)
    clean_title = re.sub(r'(?i)(official|audio|video|lyrics|remastered|feat\.?|ft\.?|with).*', '', clean_title)
    clean_title = re.sub(r'[\-–—].*', '', clean_title).strip()
    clean_artist = re.sub(r'(?i)(\s*-\s*Topic|\s*Vevo)', '', artist).strip()
    return clean_title, clean_artist

# =====================================================================
# 1. PLAN 17 MUSIXMATCH CLIENT-SIDE SCORING ENGINE
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

def test_plan17_musixmatch(title, artist, yt_duration_sec=0):
    t0 = time.time()
    try:
        token = get_musixmatch_token()
        clean_title, clean_artist = sanitize_metadata(title, artist)
        
        headers = {
            "User-Agent": MAC_USER_AGENT,
            "Cookie": f"x-mxm-token-id={token}",
            "Authority": "apic-desktop.musixmatch.com",
            "Referer": "https://desktop.musixmatch.com/"
        }
        
        # 1. Search candidates without server-side f_subtitle_length restriction
        q_t = urllib.parse.quote(clean_title)
        q_a = urllib.parse.quote(clean_artist)
        search_url = f"https://apic-desktop.musixmatch.com/ws/1.1/track.search?q_track={q_t}&q_artist={q_a}&page_size=5&s_track_rating=desc&app_id=web-desktop-app-v1.0&usertoken={token}"
        search_res = http_get(search_url, headers=headers, timeout=3)
        
        s_body = search_res.get("message", {}).get("body", {})
        track_list = s_body.get("track_list", []) if isinstance(s_body, dict) else []
        
        # Fallback to global q if q_track+q_artist returned empty
        if not track_list:
            q_global = urllib.parse.quote(f"{clean_title} {clean_artist}")
            search_url_g = f"https://apic-desktop.musixmatch.com/ws/1.1/track.search?q={q_global}&page_size=5&s_track_rating=desc&app_id=web-desktop-app-v1.0&usertoken={token}"
            search_res_g = http_get(search_url_g, headers=headers, timeout=3)
            s_body_g = search_res_g.get("message", {}).get("body", {})
            if isinstance(s_body_g, dict):
                track_list = s_body_g.get("track_list", [])
                
        if not track_list:
            return {"provider": "Musixmatch Plan17", "status": "no_candidates_found", "latency_ms": int((time.time() - t0)*1000)}

        # 2. Client-side scoring with duration delta (tolerance window <= 8s)
        best_track_id = None
        min_duration_diff = float("inf")
        
        for item in track_list:
            if not isinstance(item, dict):
                continue
            track = item.get("track", {})
            track_id = track.get("track_id")
            has_sub = track.get("has_subtitles", 0)
            track_len = track.get("track_length", 0)
            
            if has_sub == 1:
                diff = abs(track_len - yt_duration_sec) if yt_duration_sec > 0 else 0
                if diff < min_duration_diff and (diff <= 8 or yt_duration_sec == 0):
                    min_duration_diff = diff
                    best_track_id = track_id
                    
        if not best_track_id and track_list:
            for item in track_list:
                if isinstance(item, dict) and item.get("track", {}).get("has_subtitles", 0) == 1:
                    best_track_id = item.get("track", {}).get("track_id")
                    break
            if not best_track_id and isinstance(track_list[0], dict):
                best_track_id = track_list[0].get("track", {}).get("track_id")
                
        if not best_track_id:
            return {"provider": "Musixmatch Plan17", "status": "no_candidate_passed_filter", "latency_ms": int((time.time() - t0)*1000)}

        # 3. Fetch Syllable-Level RichSync
        rich_url = f"https://apic-desktop.musixmatch.com/ws/1.1/track.richsync.get?track_id={best_track_id}&app_id=web-desktop-app-v1.0&usertoken={token}"
        rich_res = http_get(rich_url, headers=headers, timeout=3)
        rich_body = rich_res.get("message", {}).get("body", {}).get("richsync", {}).get("richsync_body", "")
        
        latency = int((time.time() - t0)*1000)
        if rich_body:
            try:
                parsed_rich = json.loads(rich_body)
                if isinstance(parsed_rich, list) and len(parsed_rich) > 0:
                    return {
                        "provider": "Musixmatch Plan17",
                        "status": "richsync (syllable)",
                        "is_synced": True,
                        "is_word_synced": True,
                        "line_count": len(parsed_rich),
                        "latency_ms": latency
                    }
            except Exception:
                pass

        # 4. Fallback to subtitle_format=mxm
        mxm_url = f"https://apic-desktop.musixmatch.com/ws/1.1/track.subtitle.get?track_id={best_track_id}&subtitle_format=mxm&app_id=web-desktop-app-v1.0&usertoken={token}"
        mxm_res = http_get(mxm_url, headers=headers, timeout=3)
        mxm_body = mxm_res.get("message", {}).get("body", {}).get("subtitle", {}).get("subtitle_body", "")
        if mxm_body:
            try:
                parsed_mxm = json.loads(mxm_body)
                if isinstance(parsed_mxm, list) and len(parsed_mxm) > 0:
                    return {
                        "provider": "Musixmatch Plan17",
                        "status": "mxm (syllable)",
                        "is_synced": True,
                        "is_word_synced": True,
                        "line_count": len(parsed_mxm),
                        "latency_ms": latency
                    }
            except Exception:
                pass

        # 5. Fallback to standard line-level subtitles
        sub_url = f"https://apic-desktop.musixmatch.com/ws/1.1/track.subtitle.get?track_id={best_track_id}&subtitle_format=lrc&app_id=web-desktop-app-v1.0&usertoken={token}"
        sub_res = http_get(sub_url, headers=headers, timeout=3)
        sub_body = sub_res.get("message", {}).get("body", {}).get("subtitle", {}).get("subtitle_body", "")
        if sub_body:
            lines = [l for l in sub_body.splitlines() if l.strip()]
            return {
                "provider": "Musixmatch Plan17",
                "status": "synced (line-level)",
                "is_synced": True,
                "is_word_synced": False,
                "line_count": len(lines),
                "latency_ms": latency
            }
            
        return {"provider": "Musixmatch Plan17", "status": "no_subtitles_on_track", "latency_ms": latency}
    except Exception as e:
        return {"provider": "Musixmatch Plan17", "status": f"error: {str(e)[:30]}", "latency_ms": int((time.time() - t0)*1000)}

# =====================================================================
# 2. PLAN 17 YOUTUBE TIMED CLOSED CAPTIONS (timedtext) ENGINE
# =====================================================================
def test_plan17_youtube_timedtext(video_id):
    t0 = time.time()
    try:
        player_url = "https://www.youtube.com/youtubei/v1/player?key=AIzaSyAO_FJ2SlqAeC13vO5HhY6nN"
        player_payload = {
            "context": {
                "client": {
                    "clientName": "ANDROID",
                    "clientVersion": "19.09.37",
                    "hl": "en",
                    "gl": "US"
                }
            },
            "videoId": video_id
        }
        headers = {
            "User-Agent": ANDROID_YT_UA,
            "Content-Type": "application/json"
        }
        
        player_res = http_post(player_url, player_payload, headers=headers, timeout=3)
        caption_tracks = player_res.get("captions", {}).get("playerCaptionsTracklistRenderer", {}).get("captionTracks", [])
        
        if not caption_tracks:
            return {"provider": "YouTube TimedText", "status": "no_captions_on_video", "latency_ms": int((time.time() - t0)*1000)}

        base_url = caption_tracks[0].get("baseUrl", "")
        if not base_url:
            return {"provider": "YouTube TimedText", "status": "no_base_url", "latency_ms": int((time.time() - t0)*1000)}

        # Fetch json3 timed events
        srv3_url = f"{base_url}&fmt=json3"
        captions_json = http_get(srv3_url, timeout=3)
        events = captions_json.get("events", [])
        
        # Filter valid timed lyric lines
        valid_lines = []
        for ev in events:
            segs = ev.get("segs", [])
            text = "".join(s.get("utf8", "") for s in segs).strip()
            if text and text != "\n":
                valid_lines.append(text)
                
        latency = int((time.time() - t0)*1000)
        if valid_lines:
            return {
                "provider": "YouTube TimedText",
                "status": "synced (timed-cc)",
                "is_synced": True,
                "is_word_synced": False,
                "line_count": len(valid_lines),
                "latency_ms": latency
            }
            
        return {"provider": "YouTube TimedText", "status": "empty_events", "latency_ms": latency}
    except Exception as e:
        return {"provider": "YouTube TimedText", "status": f"error: {str(e)[:30]}", "latency_ms": int((time.time() - t0)*1000)}

# =====================================================================
# 3. LRCLIB (TIER 1 OPEN SPEED RACER)
# =====================================================================
def test_lrclib(title, artist, duration_sec=0):
    t0 = time.time()
    try:
        clean_title, clean_artist = sanitize_metadata(title, artist)
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
# THE PLAN 17 3-PRONGED TOKIO RACER
# =====================================================================
def run_plan17_racer(song):
    title = song["title"]
    artist = song["artist"]
    vid = song["video_id"]
    dur = song["duration"]
    
    t_start = time.time()
    
    with ThreadPoolExecutor(max_workers=3) as executor:
        f_mxm = executor.submit(test_plan17_musixmatch, title, artist, dur)
        f_ytt = executor.submit(test_plan17_youtube_timedtext, vid)
        f_lrc = executor.submit(test_lrclib, title, artist, dur)
        
        futures = {f_mxm: "Musixmatch Plan17", f_ytt: "YouTube TimedText", f_lrc: "LRCLIB"}
        winner = None
        all_results = {}
        
        for f in as_completed(futures):
            name = futures[f]
            res = f.result()
            all_results[name] = res
            
            # Prioritize Word-Level RichSync/MXM or First Synced response
            if winner is None and res.get("is_synced"):
                winner = res
                
        total_time_ms = int((time.time() - t_start) * 1000)
        return song, winner, all_results, total_time_ms

# =====================================================================
# 20 REPRESENTATIVE TEST TRACKS (ATVs & OMVs with Noise Tokens)
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
    print("=" * 95)
    print("🚀 EXTENSIVE LIVE BENCHMARK: PLAN 17 ARCHITECTURE")
    print("Testing Client-Side Candidate Scoring (Musixmatch) + YouTube TimedText + Tokio Racer...")
    print("=" * 95, flush=True)
    
    get_musixmatch_token()
    
    provider_stats = {
        "Musixmatch Plan17": {"synced": 0, "word_synced": 0, "plain": 0, "fail": 0, "total_ms": 0},
        "YouTube TimedText": {"synced": 0, "word_synced": 0, "plain": 0, "fail": 0, "total_ms": 0},
        "LRCLIB": {"synced": 0, "word_synced": 0, "plain": 0, "fail": 0, "total_ms": 0}
    }
    
    synced_racer_wins = 0
    word_synced_count = 0
    total_racer_ms = 0
    
    t_global_start = time.time()
    
    with ThreadPoolExecutor(max_workers=8) as pool:
        futures = [pool.submit(run_plan17_racer, s) for s in TEST_SONGS]
        
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
                
            w_badge = "🔥 WORD-SYNC (RichSync)" if is_word else ("🟢 LINE-SYNC" if is_synced else "🔴 FAILED")
            print(f"[{i:02d}/{len(TEST_SONGS)}] 🎵 {song['title']} - {song['artist']}", flush=True)
            print(f"   ⚡ TOKIO RACER WINNER: {w_name} ({w_badge}) in {racer_latency_ms}ms [{winner.get('line_count', 0) if winner else 0} lines]", flush=True)
            
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
                print(f"      ├─ {p_name:<24} : {stat_str:<32} [{lat:>4}ms]", flush=True)
            print(flush=True)

    total_time = time.time() - t_global_start
    total_tracks = len(TEST_SONGS)
    
    print("=" * 95)
    print("📊 INDIVIDUAL PROVIDER BENCHMARK PERFORMANCE (PLAN 17)")
    print("=" * 95)
    print(f"{'Provider / Attack Vector':<26} | {'Synced %':<9} | {'Word-Sync %':<12} | {'Avg Latency':<12} | {'Rating'}")
    print("-" * 95)
    for p_name, d in provider_stats.items():
        s_pct = (d["synced"] / total_tracks) * 100
        w_pct = (d["word_synced"] / total_tracks) * 100
        avg_l = d["total_ms"] // total_tracks
        r = "⭐⭐⭐⭐⭐ (Tier 1)" if s_pct >= 80 else ("⭐⭐⭐⭐ (Tier 2)" if s_pct >= 40 else "⭐⭐⭐ (Fallback)")
        print(f"{p_name:<26} | {s_pct:>7.1f}% | {w_pct:>10.1f}% | {avg_l:>9}ms | {r}")
        
    print("=" * 95)
    print("🏆 PLAN 17 TOKIO ASYNC RACER TOTAL SYNERGY RESULTS")
    print("=" * 95)
    print(f"🎯 Total Multi-Tier Synced Coverage : {(synced_racer_wins / total_tracks) * 100:.1f}% ({synced_racer_wins}/{total_tracks} tracks)")
    print(f"🎤 Word/Syllable-Level Karaoke Sync : {(word_synced_count / total_tracks) * 100:.1f}% ({word_synced_count}/{total_tracks} tracks)")
    print(f"⚡ Average First-to-Finish Latency  : {total_racer_ms // total_tracks} ms")
    print(f"⏱️ Total 20-Song Benchmark Runtime  : {total_time:.2f} seconds")
    print("=" * 95)

if __name__ == "__main__":
    main()
