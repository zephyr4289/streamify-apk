import json
import time
import urllib.request
import urllib.parse
import sys
import os

USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
ANDROID_USER_AGENT = "com.google.android.apps.youtube.music/6.42.52 (Linux; U; Android 14; en_US) gzip"

def http_post_json(url, payload, headers=None):
    data = json.dumps(payload).encode("utf-8")
    req_headers = {
        "Content-Type": "application/json",
        "User-Agent": USER_AGENT,
        "Accept": "*/*"
    }
    if headers:
        req_headers.update(headers)
    req = urllib.request.Request(url, data=data, headers=req_headers, method="POST")
    with urllib.request.urlopen(req, timeout=5) as response:
        return json.loads(response.read().decode("utf-8"))

def http_get_json(url, headers=None):
    req_headers = {
        "User-Agent": USER_AGENT,
        "Accept": "*/*"
    }
    if headers:
        req_headers.update(headers)
    req = urllib.request.Request(url, headers=req_headers, method="GET")
    with urllib.request.urlopen(req, timeout=5) as response:
        return json.loads(response.read().decode("utf-8"))

# =====================================================================
# 1. YOUTUBE MUSIC INNERTUBE LYRICS ENGINE
# =====================================================================
def fetch_ytm_lyrics(video_id):
    t0 = time.time()
    try:
        # Step 1: Query Next endpoint
        next_url = "https://music.youtube.com/youtubei/v1/next"
        next_payload = {
            "context": {
                "client": {
                    "clientName": "WEB_REMIX",
                    "clientVersion": "1.20240101.01.00",
                    "hl": "en",
                    "gl": "US"
                }
            },
            "videoId": video_id
        }
        next_resp = http_post_json(next_url, next_payload)
        
        # Find lyrics tab and browseId
        tabs = next_resp.get("contents", {}).get("singleColumnMusicWatchNextResultsRenderer", {}).get("tabbedRenderer", {}).get("watchNextTabbedResultsRenderer", {}).get("tabs", [])
        lyrics_browse_id = None
        for tab in tabs:
            tab_renderer = tab.get("tabRenderer", {})
            title = tab_renderer.get("title", "")
            if "LYRIC" in title.upper():
                lyrics_browse_id = tab_renderer.get("endpoint", {}).get("browseEndpoint", {}).get("browseId")
                break
                
        if not lyrics_browse_id:
            return {"provider": "YouTube Music", "status": "no_lyrics_tab", "latency_ms": int((time.time() - t0) * 1000)}

        # Step 2: Query Browse endpoint with ANDROID_MUSIC context
        browse_url = "https://music.youtube.com/youtubei/v1/browse"
        browse_payload = {
            "context": {
                "client": {
                    "clientName": "ANDROID_MUSIC",
                    "clientVersion": "6.42.52",
                    "androidSdkVersion": 34,
                    "hl": "en",
                    "gl": "US"
                }
            },
            "browseId": lyrics_browse_id
        }
        browse_resp = http_post_json(browse_url, browse_payload, {"User-Agent": ANDROID_USER_AGENT})
        
        # Check for timed lyrics data
        section_list = browse_resp.get("contents", {}).get("sectionListRenderer", {}).get("contents", [])
        timed_lines = []
        plain_text = []
        
        for section in section_list:
            shelf = section.get("musicDescriptionShelfRenderer", {})
            if shelf:
                runs = shelf.get("description", {}).get("runs", [])
                for r in runs:
                    plain_text.append(r.get("text", ""))
                    
            timed_renderer = section.get("musicTimedLyricsRenderer", {}) or section.get("timedLyricsRenderer", {})
            if timed_renderer:
                lines = timed_renderer.get("timedLyricsData", {}).get("lines", []) or timed_renderer.get("lines", [])
                for l in lines:
                    timed_lines.append(l)

        # Alternative timed lyrics structure in newer Innertube
        if not timed_lines:
            def find_timed(d):
                if isinstance(d, dict):
                    if "timedLyricsData" in d or "timedLyricsLines" in d or "cueRange" in d:
                        return d
                    for v in d.values():
                        res = find_timed(v)
                        if res: return res
                elif isinstance(d, list):
                    for item in d:
                        res = find_timed(item)
                        if res: return res
                return None
            
            found = find_timed(browse_resp)
            if found:
                timed_lines.append(found)

        latency = int((time.time() - t0) * 1000)
        has_synced = len(timed_lines) > 0
        has_plain = len(plain_text) > 0 or "musicDescriptionShelfRenderer" in str(browse_resp)
        
        return {
            "provider": "YouTube Music (Innertube)",
            "status": "success" if (has_synced or has_plain) else "empty",
            "is_synced": has_synced,
            "line_count": len(timed_lines) if has_synced else len("".join(plain_text).splitlines()),
            "browse_id": lyrics_browse_id,
            "latency_ms": latency
        }
    except Exception as e:
        return {"provider": "YouTube Music (Innertube)", "status": f"error: {str(e)[:50]}", "latency_ms": int((time.time() - t0) * 1000)}

# =====================================================================
# 2. LRCLIB HIGH-PRECISION EXACT & FUZZY
# =====================================================================
def fetch_lrclib(title, artist, duration_sec=0):
    t0 = time.time()
    try:
        q_title = urllib.parse.quote(title)
        q_artist = urllib.parse.quote(artist)
        url = f"https://lrclib.net/api/get?track_name={q_title}&artist_name={q_artist}"
        if duration_sec > 0:
            url += f"&duration={duration_sec}"
            
        res = http_get_json(url)
        synced = res.get("syncedLyrics", "")
        plain = res.get("plainLyrics", "")
        latency = int((time.time() - t0) * 1000)
        
        if synced:
            lines = [l for l in synced.splitlines() if l.strip()]
            return {"provider": "LRCLIB Exact", "status": "success", "is_synced": True, "line_count": len(lines), "latency_ms": latency}
        elif plain:
            lines = [l for l in plain.splitlines() if l.strip()]
            return {"provider": "LRCLIB Exact", "status": "plain_only", "is_synced": False, "line_count": len(lines), "latency_ms": latency}
        else:
            # Try fuzzy search
            url_search = f"https://lrclib.net/api/search?q={urllib.parse.quote(f'{title} {artist}')}"
            search_res = http_get_json(url_search)
            if isinstance(search_res, list) and len(search_res) > 0:
                first = search_res[0]
                s = first.get("syncedLyrics", "")
                if s:
                    lines = [l for l in s.splitlines() if l.strip()]
                    return {"provider": "LRCLIB Fuzzy", "status": "success", "is_synced": True, "line_count": len(lines), "latency_ms": latency}
            return {"provider": "LRCLIB", "status": "not_found", "latency_ms": latency}
    except Exception as e:
        return {"provider": "LRCLIB", "status": f"error: {str(e)[:50]}", "latency_ms": int((time.time() - t0) * 1000)}

# =====================================================================
# 3. MUSIXMATCH DESKTOP API
# =====================================================================
def fetch_musixmatch(title, artist):
    t0 = time.time()
    try:
        # Get Musixmatch token
        token_url = "https://apic-desktop.musixmatch.com/ws/1.1/token.get?app_id=web-desktop-app-v1.0"
        token_resp = http_get_json(token_url)
        user_token = token_resp.get("message", {}).get("body", {}).get("user_token")
        
        if not user_token:
            user_token = "240228000000000000000000000000" # fallback public token
            
        q_track = urllib.parse.quote(title)
        q_artist = urllib.parse.quote(artist)
        sub_url = f"https://apic-desktop.musixmatch.com/ws/1.1/macro.subtitles.get?q_track={q_track}&q_artist={q_artist}&format=json&app_id=web-desktop-app-v1.0&usertoken={user_token}"
        sub_resp = http_get_json(sub_url)
        
        macro_calls = sub_resp.get("message", {}).get("body", {}).get("macro_calls", {})
        track_sub = macro_calls.get("track.subtitles.get", {}).get("message", {}).get("body", {})
        sub_list = track_sub.get("subtitle_list", [])
        
        latency = int((time.time() - t0) * 1000)
        if sub_list:
            body = sub_list[0].get("subtitle", {}).get("subtitle_body", "")
            lines = [l for l in body.splitlines() if l.strip()]
            return {"provider": "Musixmatch", "status": "success", "is_synced": True, "line_count": len(lines), "latency_ms": latency}
            
        # Try matcher.lyrics.get for plain
        lyrics_call = macro_calls.get("matcher.lyrics.get", {}).get("message", {}).get("body", {})
        lyrics_body = lyrics_call.get("lyrics", {}).get("lyrics_body", "")
        if lyrics_body:
            lines = [l for l in lyrics_body.splitlines() if l.strip()]
            return {"provider": "Musixmatch", "status": "plain_only", "is_synced": False, "line_count": len(lines), "latency_ms": latency}
            
        return {"provider": "Musixmatch", "status": "not_found", "latency_ms": latency}
    except Exception as e:
        return {"provider": "Musixmatch", "status": f"error: {str(e)[:50]}", "latency_ms": int((time.time() - t0) * 1000)}

# =====================================================================
# 4. NETEASE CLOUD MUSIC SYNCED LRC
# =====================================================================
def fetch_netease(title, artist):
    t0 = time.time()
    try:
        q = urllib.parse.quote(f"{title} {artist}")
        search_url = f"https://music.163.com/api/search/get/web?s={q}&type=1&offset=0&total=true&limit=1"
        search_resp = http_get_json(search_url, {"Referer": "https://music.163.com/"})
        songs = search_resp.get("result", {}).get("songs", [])
        if not songs:
            return {"provider": "NetEase", "status": "not_found", "latency_ms": int((time.time() - t0) * 1000)}
            
        song_id = songs[0].get("id")
        lrc_url = f"https://music.163.com/api/song/lyric?os=pc&id={song_id}&lv=-1&kv=-1&tv=-1"
        lrc_resp = http_get_json(lrc_url)
        lrc_text = lrc_resp.get("lrc", {}).get("lyric", "")
        latency = int((time.time() - t0) * 1000)
        
        if "[" in lrc_text:
            lines = [l for l in lrc_text.splitlines() if l.strip() and not l.startswith("[ti:") and not l.startswith("[ar:")]
            return {"provider": "NetEase", "status": "success", "is_synced": True, "line_count": len(lines), "latency_ms": latency}
        elif lrc_text:
            return {"provider": "NetEase", "status": "plain_only", "is_synced": False, "line_count": len(lrc_text.splitlines()), "latency_ms": latency}
        return {"provider": "NetEase", "status": "empty", "latency_ms": latency}
    except Exception as e:
        return {"provider": "NetEase", "status": f"error: {str(e)[:50]}", "latency_ms": int((time.time() - t0) * 1000)}

# =====================================================================
# BENCHMARK TEST SUITE
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
    {"title": "Industry Baby", "artist": "Lil Nas X", "video_id": "UTHLKHL_whs", "duration": 212}
]

def run_benchmarks():
    print("=" * 80)
    print("🚀 STREAMIFY HIGH-PERFORMANCE LYRICS API BENCHMARK")
    print(f"Testing {len(TEST_SONGS)} representative tracks across all candidate providers...")
    print("=" * 80)
    
    results = {
        "YouTube Music (Innertube)": {"success_synced": 0, "success_plain": 0, "fail": 0, "total_ms": 0},
        "LRCLIB": {"success_synced": 0, "success_plain": 0, "fail": 0, "total_ms": 0},
        "Musixmatch": {"success_synced": 0, "success_plain": 0, "fail": 0, "total_ms": 0},
        "NetEase": {"success_synced": 0, "success_plain": 0, "fail": 0, "total_ms": 0},
    }
    
    song_details = []
    
    for i, song in enumerate(TEST_SONGS, 1):
        title = song["title"]
        artist = song["artist"]
        vid = song["video_id"]
        dur = song["duration"]
        
        print(f"\n[{i}/{len(TEST_SONGS)}] 🎵 '{title}' by {artist} (Video ID: {vid})")
        
        # Test 1: YouTube Music
        res_yt = fetch_ytm_lyrics(vid)
        # Test 2: LRCLIB
        res_lrc = fetch_lrclib(title, artist, dur)
        # Test 3: Musixmatch
        res_mxm = fetch_musixmatch(title, artist)
        # Test 4: NetEase
        res_netease = fetch_netease(title, artist)
        
        test_res = [res_yt, res_lrc, res_mxm, res_netease]
        
        for r in test_res:
            p_name = "YouTube Music (Innertube)" if "YouTube" in r["provider"] else ("LRCLIB" if "LRCLIB" in r["provider"] else r["provider"])
            lat = r.get("latency_ms", 0)
            results[p_name]["total_ms"] += lat
            
            if r.get("is_synced"):
                results[p_name]["success_synced"] += 1
                status_icon = "🟢 SYNCED"
            elif r.get("status") == "plain_only":
                results[p_name]["success_plain"] += 1
                status_icon = "🟡 PLAIN"
            else:
                results[p_name]["fail"] += 1
                status_icon = "🔴 FAILED"
                
            line_info = f"({r.get('line_count', 0)} lines)" if r.get("line_count") else ""
            print(f"   ├─ {p_name:<28} : {status_icon:<10} in {lat:>4}ms {line_info}")
            
        song_details.append({
            "song": f"{title} - {artist}",
            "providers": test_res
        })
        time.sleep(0.1) # polite delay
        
    print("\n" + "=" * 80)
    print("📊 BENCHMARK SUMMARY & PERFORMANCE MATRIX")
    print("=" * 80)
    print(f"{'Provider':<28} | {'Synced %':<10} | {'Plain %':<10} | {'Avg Latency':<12} | {'Rating'}")
    print("-" * 80)
    
    total_count = len(TEST_SONGS)
    for p_name, data in results.items():
        synced_pct = (data["success_synced"] / total_count) * 100
        plain_pct = (data["success_plain"] / total_count) * 100
        avg_lat = data["total_ms"] // total_count
        rating = "⭐⭐⭐⭐⭐ (Tier 1)" if synced_pct >= 80 else ("⭐⭐⭐⭐ (Tier 2)" if (synced_pct + plain_pct) >= 80 else "⭐⭐⭐ (Tier 3)")
        print(f"{p_name:<28} | {synced_pct:>8.1f}% | {plain_pct:>8.1f}% | {avg_lat:>9}ms | {rating}")
        
    print("=" * 80)
    print("✨ Waterfall Synergy: Combining YouTube Music + LRCLIB + Musixmatch + NetEase yields:")
    all_synced = 0
    for s in song_details:
        if any(p.get("is_synced") for p in s["providers"]):
            all_synced += 1
    print(f"🎯 Total Multi-Tier Synced Coverage: {(all_synced / total_count) * 100:.1f}% ({all_synced}/{total_count} songs with exact synchronized lyrics!)")
    print("=" * 80)

if __name__ == "__main__":
    run_benchmarks()
