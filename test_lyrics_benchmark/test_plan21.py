import json
import time
import urllib.request
import urllib.parse
import gzip
import re
from concurrent.futures import ThreadPoolExecutor, as_completed

USER_AGENT_DESKTOP = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

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

# =====================================================================
# UNIVERSAL METADATA NORMALIZER (PLAN 21)
# =====================================================================
def normalize_metadata(raw_title, raw_artist):
    title = raw_title
    artist = re.sub(r'(?i)(\s*-\s*Topic|\s*VEVO|\s*Official)', '', raw_artist).strip()

    # 1. Split compound titles with Unicode dashes / delimiters
    delimiters = [" – ", " — ", " - ", " | ", " // "]
    for delim in delimiters:
        if delim in title:
            parts = title.split(delim)
            if len(parts) == 2:
                p0 = parts[0].strip()
                p1 = parts[1].strip()
                if artist and artist.lower() in p1.lower():
                    title = p0
                elif artist and artist.lower() in p0.lower():
                    title = p1
                elif not artist:
                    artist = p0
                    title = p1

    # 2. Strip noise tokens inside parentheses and brackets
    noise_regex = re.compile(
        r"(?i)\((?:official|audio|video|remastered|remaster|radio edit|edit|version|deluxe|feat\.|feat|ft\.|with|bonus|live|acoustic|anniversary|lyric video|lyrics).*?\)|\[.*?\]"
    )
    title = noise_regex.sub("", title).strip()

    # 3. Strip dangling punctuation
    clean_title = re.sub(r'^[^\w\s]+|[^\w\s]+$', '', title).strip()
    clean_artist = re.sub(r'^[^\w\s]+|[^\w\s]+$', '', artist).strip()

    return clean_title, clean_artist

# =====================================================================
# 1. MUSIXMATCH 100 RESOLVER (PLAN 21)
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

def test_plan21_musixmatch(raw_title, raw_artist):
    t0 = time.time()
    try:
        token = get_musixmatch_token()
        title, artist = normalize_metadata(raw_title, raw_artist)
        
        headers = {
            "User-Agent": USER_AGENT_DESKTOP,
            "Authority": "apic-desktop.musixmatch.com",
            "Cookie": f"x-mxm-token-id={token}"
        }
        
        # Tier 1: Direct Matcher with Normalized Metadata
        q_t = urllib.parse.quote(title)
        q_a = urllib.parse.quote(artist)
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
                                "provider": "Musixmatch Plan21",
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
                        "provider": "Musixmatch Plan21",
                        "status": "synced (line-level)",
                        "is_synced": True,
                        "is_word_synced": False,
                        "line_count": len(lines),
                        "latency_ms": latency
                    }
        except Exception:
            pass

        # Tier 2: Stripped Base Title Matcher
        base_title = re.sub(r"[\(\[\{].*?[\)\]\}]", "", title).strip()
        base_title = re.sub(r'^[^\w\s]+|[^\w\s]+$', '', base_title).strip()
        
        if base_title and base_title != title:
            q_bt = urllib.parse.quote(base_title)
            base_matcher_url = f"https://apic-desktop.musixmatch.com/ws/1.1/matcher.subtitle.get?format=json&subtitle_format=mxm&app_id=web-desktop-app-v1.0&usertoken={token}&q_track={q_bt}&q_artist={q_a}"
            try:
                b_res = http_get(base_matcher_url, headers=headers, timeout=3)
                b_body = b_res.get("message", {}).get("body", {})
                if isinstance(b_body, dict):
                    sub_raw = b_body.get("subtitle", {}).get("subtitle_body", "")
                    if sub_raw:
                        latency = int((time.time() - t0)*1000)
                        try:
                            parsed_mxm = json.loads(sub_raw)
                            if isinstance(parsed_mxm, list) and len(parsed_mxm) > 0:
                                return {
                                    "provider": "Musixmatch Plan21",
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
                            "provider": "Musixmatch Plan21",
                            "status": "synced (line-level)",
                            "is_synced": True,
                            "is_word_synced": False,
                            "line_count": len(lines),
                            "latency_ms": latency
                        }
            except Exception:
                pass

        # Tier 3: Search Cascade
        search_queries = [
            f"q={urllib.parse.quote(f'{base_title} {artist}')}",
            f"q_track={urllib.parse.quote(base_title)}&q_artist={q_a}",
            f"q_track={urllib.parse.quote(base_title)}",
            f"q={urllib.parse.quote(base_title)}"
        ]
        
        for q_str in search_queries:
            search_url = f"https://apic-desktop.musixmatch.com/ws/1.1/track.search?format=json&page_size=5&s_track_rating=desc&app_id=web-desktop-app-v1.0&usertoken={token}&{q_str}"
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
                            # 1. Prefer Syllable RichSync
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
                                                "provider": "Musixmatch Plan21",
                                                "status": "richsync (syllable)",
                                                "is_synced": True,
                                                "is_word_synced": True,
                                                "line_count": len(parsed),
                                                "latency_ms": int((time.time() - t0)*1000)
                                            }
                                        except Exception:
                                            pass

                            # 2. Fallback to Subtitles
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
                                                    "provider": "Musixmatch Plan21",
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
                                            "provider": "Musixmatch Plan21",
                                            "status": "synced (line-level)",
                                            "is_synced": True,
                                            "is_word_synced": False,
                                            "line_count": len(lines),
                                            "latency_ms": latency
                                        }
            except Exception:
                continue

        return {"provider": "Musixmatch Plan21", "status": "not_found", "latency_ms": int((time.time() - t0)*1000)}
    except Exception as e:
        return {"provider": "Musixmatch Plan21", "status": f"error: {str(e)[:30]}", "latency_ms": int((time.time() - t0)*1000)}

# =====================================================================
# 20 REPRESENTATIVE TEST TRACKS (INCLUDING NOISY UNICODE / EN-DASHES)
# =====================================================================
TEST_SONGS = [
    {"title": "Blinding Lights (Official Audio)", "artist": "The Weeknd"},
    {"title": "Shape of You [Official Video]", "artist": "Ed Sheeran"},
    {"title": "Bohemian Rhapsody - Remastered 2011", "artist": "Queen - Topic"},
    {"title": "Levitating (feat. DaBaby)", "artist": "Dua Lipa"},
    {"title": "Starboy (Official Music Video)", "artist": "The Weeknd ft. Daft Punk"},
    {"title": "As It Was", "artist": "Harry Styles"},
    {"title": "Flowers", "artist": "Miley Cyrus"},
    {"title": "Someone You Loved", "artist": "Lewis Capaldi - Topic"},
    {"title": "Believer", "artist": "Imagine Dragons"},
    {"title": "Stay (with Justin Bieber)", "artist": "The Kid LAROI"},
    {"title": "Bad Guy", "artist": "Billie Eilish"},
    {"title": "Save Your Tears (Official Music Video)", "artist": "The Weeknd"},
    {"title": "Heat Waves (Official Video)", "artist": "Glass Animals"},
    {"title": "Watermelon Sugar", "artist": "Harry Styles"},
    {"title": "Industry Baby (feat. Jack Harlow)", "artist": "Lil Nas X"},
    {"title": "Hotel California - 2013 Remaster", "artist": "Eagles"},
    {"title": "Cruel Summer", "artist": "Taylor Swift"},
    {"title": "Counting Stars – OneRepublic", "artist": "OneRepublic"},
    {"title": "vampire (Official Video)", "artist": "Olivia Rodrigo"},
    {"title": "Wake Me Up (Radio Edit)", "artist": "Avicii"}
]

def benchmark_track(song):
    return song, test_plan21_musixmatch(song["title"], song["artist"])

def main():
    print("=" * 95)
    print("🚀 LIVE BENCHMARK: PHASE 21 (UNIVERSAL NORMALIZER & 100% RESOLVER)")
    print(f"Testing {len(TEST_SONGS)} tracks across Musixmatch Plan 21...")
    print("=" * 95, flush=True)
    
    get_musixmatch_token()
    
    synced_count = 0
    word_synced_count = 0
    total_latency_ms = 0
    
    t_start = time.time()
    
    with ThreadPoolExecutor(max_workers=8) as pool:
        futures = [pool.submit(benchmark_track, s) for s in TEST_SONGS]
        
        for i, f in enumerate(as_completed(futures), 1):
            song, res = f.result()
            lat = res.get("latency_ms", 0)
            total_latency_ms += lat
            
            is_synced = res.get("is_synced", False)
            is_word = res.get("is_word_synced", False)
            
            if is_synced:
                synced_count += 1
            if is_word:
                word_synced_count += 1
                
            stat_str = f"🔥 WORD-SYNC (Syllable) [{res.get('line_count', 0)} lines]" if is_word else (
                f"🟢 SYNCED (Line-Level)  [{res.get('line_count', 0)} lines]" if is_synced else f"🔴 {res.get('status', 'FAIL')}"
            )
            
            print(f"[{i:02d}/{len(TEST_SONGS)}] 🎵 {song['title']} - {song['artist']}", flush=True)
            print(f"   ├─ Musixmatch Plan21 : {stat_str:<36} [{lat:>4}ms]", flush=True)
            print(flush=True)

    t_total = time.time() - t_start
    total_tracks = len(TEST_SONGS)
    
    print("=" * 95)
    print("📊 BENCHMARK SUMMARY & PERFORMANCE MATRIX (PHASE 21)")
    print("=" * 95)
    print(f"🎯 Total Synced Coverage           : {(synced_count / total_tracks) * 100:.1f}% ({synced_count}/{total_tracks} tracks)")
    print(f"🎤 Word/Syllable Karaoke Precision  : {(word_synced_count / total_tracks) * 100:.1f}% ({word_synced_count}/{total_tracks} tracks)")
    print(f"⚡ Average Latency                  : {total_latency_ms // total_tracks} ms")
    print(f"⏱️ Total Benchmark Runtime          : {t_total:.2f} seconds across {total_tracks} tracks")
    print("=" * 95, flush=True)

if __name__ == "__main__":
    main()
