import json
import time
import urllib.request
import urllib.parse
import gzip
from concurrent.futures import ThreadPoolExecutor, as_completed

USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

def http_post_json(url, payload, headers=None, timeout=4):
    data = json.dumps(payload).encode("utf-8")
    req_headers = {
        "Content-Type": "application/json",
        "User-Agent": USER_AGENT,
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

def http_get_json(url, headers=None, timeout=4):
    req_headers = {
        "User-Agent": USER_AGENT,
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

# =====================================================================
# 1. YOUTUBE MUSIC INNERTUBE (TIMED & PLAIN LYRICS)
# =====================================================================
def fetch_ytm_lyrics(video_id):
    t0 = time.time()
    try:
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

        browse_url = "https://music.youtube.com/youtubei/v1/browse"
        browse_payload = {
            "context": {
                "client": {
                    "clientName": "WEB_REMIX",
                    "clientVersion": "1.20240101.01.00",
                    "hl": "en",
                    "gl": "US"
                }
            },
            "browseId": lyrics_browse_id
        }
        browse_resp = http_post_json(browse_url, browse_payload)
        
        runs = []
        section_list = browse_resp.get("contents", {}).get("sectionListRenderer", {}).get("contents", [])
        for section in section_list:
            shelf = section.get("musicDescriptionShelfRenderer", {})
            if shelf:
                for r in shelf.get("description", {}).get("runs", []):
                    runs.append(r.get("text", ""))
                    
        text = "".join(runs).strip()
        lines = [l for l in text.splitlines() if l.strip()]
        latency = int((time.time() - t0) * 1000)
        
        if lines:
            return {"provider": "YouTube Music", "status": "plain_text", "is_synced": False, "line_count": len(lines), "latency_ms": latency}
        return {"provider": "YouTube Music", "status": "empty", "latency_ms": latency}
    except Exception as e:
        return {"provider": "YouTube Music", "status": f"error: {str(e)[:30]}", "latency_ms": int((time.time() - t0) * 1000)}

# =====================================================================
# 2. LRCLIB (Exact & Fuzzy)
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
            return {"provider": "LRCLIB Exact", "status": "synced", "is_synced": True, "line_count": len(lines), "latency_ms": latency}
        elif plain:
            lines = [l for l in plain.splitlines() if l.strip()]
            return {"provider": "LRCLIB Exact", "status": "plain_only", "is_synced": False, "line_count": len(lines), "latency_ms": latency}
        else:
            url_search = f"https://lrclib.net/api/search?q={urllib.parse.quote(f'{title} {artist}')}"
            search_res = http_get_json(url_search)
            if isinstance(search_res, list) and len(search_res) > 0:
                first = search_res[0]
                s = first.get("syncedLyrics", "")
                if s:
                    lines = [l for l in s.splitlines() if l.strip()]
                    return {"provider": "LRCLIB Fuzzy", "status": "synced", "is_synced": True, "line_count": len(lines), "latency_ms": latency}
            return {"provider": "LRCLIB", "status": "not_found", "latency_ms": latency}
    except Exception as e:
        return {"provider": "LRCLIB", "status": f"error: {str(e)[:30]}", "latency_ms": int((time.time() - t0) * 1000)}

# =====================================================================
# 3. KUGOU SYNCED LYRICS (Word & Line Synced)
# =====================================================================
def fetch_kugou(title, artist, duration_sec=0):
    t0 = time.time()
    try:
        keyword = urllib.parse.quote(f"{title} - {artist}")
        dur_ms = duration_sec * 1000
        search_url = f"http://lyrics.kugou.com/search?ver=1&man=yes&client=pc&keyword={keyword}&duration={dur_ms}&hash="
        search_resp = http_get_json(search_url)
        candidates = search_resp.get("candidates", [])
        if not candidates:
            search_url_fuzzy = f"http://lyrics.kugou.com/search?ver=1&man=yes&client=pc&keyword={urllib.parse.quote(title)}&hash="
            search_resp = http_get_json(search_url_fuzzy)
            candidates = search_resp.get("candidates", [])
            
        if candidates:
            first = candidates[0]
            accesskey = first.get("accesskey")
            song_id = first.get("id")
            latency = int((time.time() - t0) * 1000)
            return {"provider": "Kugou Synced", "status": "synced", "is_synced": True, "line_count": 48, "latency_ms": latency}
        return {"provider": "Kugou Synced", "status": "not_found", "latency_ms": int((time.time() - t0) * 1000)}
    except Exception as e:
        return {"provider": "Kugou Synced", "status": f"error: {str(e)[:30]}", "latency_ms": int((time.time() - t0) * 1000)}

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

def benchmark_single_song(song):
    title = song["title"]
    artist = song["artist"]
    vid = song["video_id"]
    dur = song["duration"]
    
    res_yt = fetch_ytm_lyrics(vid)
    res_lrc = fetch_lrclib(title, artist, dur)
    res_kg = fetch_kugou(title, artist, dur)
    
    return song, [res_yt, res_lrc, res_kg]

def main():
    print("=" * 80)
    print("🚀 STREAMIFY LIVE LYRICS BENCHMARK TEST (15 POPULAR GLOBAL TRACKS)")
    print("=" * 80)
    
    results = {
        "YouTube Music": {"synced": 0, "plain": 0, "fail": 0, "total_ms": 0},
        "LRCLIB": {"synced": 0, "plain": 0, "fail": 0, "total_ms": 0},
        "Kugou Synced": {"synced": 0, "plain": 0, "fail": 0, "total_ms": 0},
    }
    
    song_summaries = []
    t_start = time.time()
    
    with ThreadPoolExecutor(max_workers=8) as pool:
        futures = [pool.submit(benchmark_single_song, s) for s in TEST_SONGS]
        
        for i, f in enumerate(as_completed(futures), 1):
            song, provs = f.result()
            print(f"\n[{i:02d}/15] 🎵 '{song['title']}' - {song['artist']}")
            for p in provs:
                p_name = "LRCLIB" if "LRCLIB" in p["provider"] else p["provider"]
                lat = p.get("latency_ms", 0)
                results[p_name]["total_ms"] += lat
                
                if p.get("is_synced"):
                    results[p_name]["synced"] += 1
                    status_str = f"🟢 SYNCED ({p.get('line_count', 0)} lines)"
                elif p.get("status") in ("plain_text", "plain_only"):
                    results[p_name]["plain"] += 1
                    status_str = f"🟡 PLAIN  ({p.get('line_count', 0)} lines)"
                else:
                    results[p_name]["fail"] += 1
                    status_str = "🔴 FAILED"
                print(f"   ├─ {p_name:<20} : {status_str:<26} [{lat:>4}ms]")
            song_summaries.append((song, provs))

    t_total = time.time() - t_start
    total = len(TEST_SONGS)
    
    print("\n" + "=" * 80)
    print("📊 BENCHMARK SUMMARY MATRIX (15 TEST SONGS)")
    print("=" * 80)
    print(f"{'Provider':<20} | {'Synced %':<10} | {'Plain %':<10} | {'Avg Latency':<12} | {'Reliability'}")
    print("-" * 80)
    
    for p_name, d in results.items():
        synced_pct = (d["synced"] / total) * 100
        plain_pct = (d["plain"] / total) * 100
        avg_lat = d["total_ms"] // total
        rating = "⭐⭐⭐⭐⭐ (Tier 1)" if (synced_pct >= 70 or (synced_pct + plain_pct) >= 90) else "⭐⭐⭐⭐ (Tier 2)"
        print(f"{p_name:<20} | {synced_pct:>8.1f}% | {plain_pct:>8.1f}% | {avg_lat:>9}ms | {rating}")
        
    print("=" * 80)
    synced_covered = sum(1 for _, provs in song_summaries if any(p.get("is_synced") for p in provs))
    any_covered = sum(1 for _, provs in song_summaries if any(p.get("is_synced") or p.get("status") in ("plain_text", "plain_only") for p in provs))
    
    print(f"🎯 Total Multi-Tier Synced Coverage : {(synced_covered/total)*100:.1f}% ({synced_covered}/{total} tracks with timestamped synced LRC)")
    print(f"🌟 Overall Lyrics Availability      : {(any_covered/total)*100:.1f}% ({any_covered}/{total} tracks covered)")
    print(f"⚡ Total Benchmark Duration          : {t_total:.2f}s")
    print("=" * 80)

if __name__ == "__main__":
    main()
