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

def fetch_lyrics(title, artist, duration_sec=0):
    clean_t = clean_str(title)
    clean_a = clean_str(artist.split(',')[0].replace('- Topic', '').replace('VEVO', ''))
    
    if not clean_t:
        clean_t = title
    if not clean_a:
        clean_a = artist

    # 1. Exact LRCLIB lookup with duration
    try:
        url = f"https://lrclib.net/api/get?track_name={urllib.parse.quote(clean_t)}&artist_name={urllib.parse.quote(clean_a)}"
        if duration_sec > 0:
            url += f"&duration={int(duration_sec)}"
        req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
        with urllib.request.urlopen(req, timeout=3) as resp:
            if resp.status == 200:
                data = json.loads(resp.read().decode())
                lyrics = data.get("syncedLyrics") or data.get("plainLyrics")
                if lyrics:
                    return lyrics
    except Exception:
        pass

    # 2. LRCLIB lookup WITHOUT duration constraint
    try:
        url = f"https://lrclib.net/api/get?track_name={urllib.parse.quote(clean_t)}&artist_name={urllib.parse.quote(clean_a)}"
        req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
        with urllib.request.urlopen(req, timeout=3) as resp:
            if resp.status == 200:
                data = json.loads(resp.read().decode())
                lyrics = data.get("syncedLyrics") or data.get("plainLyrics")
                if lyrics:
                    return lyrics
    except Exception:
        pass

    # 3. LRCLIB Fuzzy Search endpoint
    try:
        q = urllib.parse.quote(f"{clean_t} {clean_a}")
        url = f"https://lrclib.net/api/search?q={q}"
        req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
        with urllib.request.urlopen(req, timeout=4) as resp:
            if resp.status == 200:
                results = json.loads(resp.read().decode())
                if isinstance(results, list) and len(results) > 0:
                    # Prefer item with synced lyrics
                    for item in results:
                        if item.get("syncedLyrics"):
                            return item.get("syncedLyrics")
                    # Fallback to plain lyrics
                    for item in results:
                        if item.get("plainLyrics"):
                            return item.get("plainLyrics")
    except Exception:
        pass

    # 4. Lyrics.ovh API fallback for plain lyrics
    try:
        url = f"https://api.lyrics.ovh/v1/{urllib.parse.quote(clean_a)}/{urllib.parse.quote(clean_t)}"
        req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
        with urllib.request.urlopen(req, timeout=4) as resp:
            if resp.status == 200:
                data = json.loads(resp.read().decode())
                lyrics = data.get("lyrics")
                if lyrics and len(lyrics.strip()) > 10:
                    return lyrics.strip()
    except Exception:
        pass

    return ""
