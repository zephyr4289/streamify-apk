import json
import re
import urllib.request
import urllib.parse

def clean_str(s):
    if not s:
        return ""
    # Remove common video fluff keywords in parentheses/brackets
    s = re.sub(r'[\(\[\{].*?(official|video|audio|lyric|hd|4k|remastered|mv|topic|vevo).*?[\)\]\}]', '', s, flags=re.IGNORECASE)
    # Remove feat / ft
    s = re.sub(r'(feat\.|ft\.).*', '', s, flags=re.IGNORECASE)
    # Clean whitespace and quotes
    s = s.replace('"', '').replace("'", "").strip()
    return s

def _make_request(url, timeout=3):
    import time
    from urllib.error import HTTPError
    req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
    for attempt in range(2):
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                if resp.status == 200:
                    return json.loads(resp.read().decode())
        except HTTPError as e:
            if e.code == 429:
                time.sleep(2) # rate limited, sleep and retry
                continue
            break
        except Exception:
            break
    return None

def fetch_lyrics(title, artist, duration_sec=0):
    clean_t = clean_str(title)
    clean_a = clean_str(artist.split(',')[0].replace('- Topic', '').replace('VEVO', ''))
    
    if not clean_t:
        clean_t = title
    if not clean_a:
        clean_a = artist

    # 1. Exact LRCLIB lookup with duration
    url1 = f"https://lrclib.net/api/get?track_name={urllib.parse.quote(clean_t)}&artist_name={urllib.parse.quote(clean_a)}"
    if duration_sec > 0:
        url1 += f"&duration={int(duration_sec)}"
    data = _make_request(url1)
    if data:
        lyrics = data.get("syncedLyrics") or data.get("plainLyrics")
        if lyrics:
            return lyrics

    # 2. LRCLIB lookup WITHOUT duration constraint
    url2 = f"https://lrclib.net/api/get?track_name={urllib.parse.quote(clean_t)}&artist_name={urllib.parse.quote(clean_a)}"
    data = _make_request(url2)
    if data:
        lyrics = data.get("syncedLyrics") or data.get("plainLyrics")
        if lyrics:
            return lyrics

    # 3. LRCLIB Fuzzy Search endpoint
    q = urllib.parse.quote(f"{clean_t} {clean_a}")
    url3 = f"https://lrclib.net/api/search?q={q}"
    results = _make_request(url3, timeout=4)
    if results and isinstance(results, list):
        for item in results:
            if item.get("syncedLyrics"):
                return item.get("syncedLyrics")
        for item in results:
            if item.get("plainLyrics"):
                return item.get("plainLyrics")

    # 4. Lyrics.ovh API fallback for plain lyrics
    url4 = f"https://api.lyrics.ovh/v1/{urllib.parse.quote(clean_a)}/{urllib.parse.quote(clean_t)}"
    data = _make_request(url4, timeout=4)
    if data:
        lyrics = data.get("lyrics")
        if lyrics and len(lyrics.strip()) > 10:
            return lyrics.strip()

    return ""
