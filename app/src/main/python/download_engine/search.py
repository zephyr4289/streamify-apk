import json
import yt_dlp
import urllib.request
import urllib.parse
import concurrent.futures

from difflib import SequenceMatcher
import re

def normalize_text(text):
    if not text:
        return ""
    text = re.sub(r'[\(\[\{].*?[\)\]\}]', '', text)
    text = re.sub(r'[^a-zA-Z0-9\s]', ' ', text)
    return " ".join(text.lower().split())

def fetch_itunes_cover_art(title, artist):
    try:
        clean_title = re.sub(r"\(feat\.[^\)]+\)", "", title, flags=re.IGNORECASE).strip()
        primary_artist = re.split(r",| feat\.| ft\.|&", artist, flags=re.IGNORECASE)[0].strip() if artist else ""
        query = urllib.parse.quote(f"{clean_title} {primary_artist}".strip())
        url = f"https://itunes.apple.com/search?term={query}&limit=5&entity=song"
        req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36'})
        with urllib.request.urlopen(req, timeout=3) as response:
            data = json.loads(response.read().decode())
            results = data.get('results', [])
            norm_target_title = normalize_text(clean_title)
            norm_target_artist = normalize_text(primary_artist)
            
            for item in results:
                track_name = normalize_text(item.get('trackName', ''))
                artist_name = normalize_text(item.get('artistName', ''))
                
                # Check for strong match
                if (norm_target_title in track_name or track_name in norm_target_title) and \
                   (not norm_target_artist or norm_target_artist in artist_name or artist_name in norm_target_artist):
                    art = item.get('artworkUrl100', '')
                    if art:
                        return art.replace('100x100bb', '1400x1400bb').replace('100x100', '1400x1400')
    except Exception:
        pass
    return ""

def is_bad_candidate(title):
    bad_words = ['slowed', 'reverb', 'nightcore', 'cover', 'karaoke', 'instrumental', '8d audio', 'reaction', 'bass boosted', 'episode', 'season', 'explained', 'review', 'trailer', 'parody']
    t_lower = title.lower()
    return any(word in t_lower for word in bad_words)

def calculate_title_similarity(target_title, candidate_title):
    t1 = normalize_text(target_title)
    t2 = normalize_text(candidate_title)
    if not t1 or not t2:
        return 0.0
    seq_ratio = SequenceMatcher(None, t1, t2).ratio()
    words1 = set(t1.split())
    words2 = set(t2.split())
    overlap = len(words1 & words2) / max(len(words1 | words2), 1)
    return (0.6 * seq_ratio) + (0.4 * overlap)

def score_candidate(target_title, target_artist, candidate_title, uploader, candidate_duration, target_duration=0):
    score = 0
    sim = calculate_title_similarity(target_title, candidate_title)
    score += int(sim * 60) # Up to 60 pts from title similarity

    # Channel match & studio bonus (+25 pts)
    uploader_lower = uploader.lower()
    if "- topic" in uploader_lower or "vevo" in uploader_lower:
        score += 25
    elif target_artist and normalize_text(target_artist) in normalize_text(uploader):
        score += 15

    # Duration tolerance check (+15 pts if within 15s)
    if target_duration > 0 and candidate_duration > 0:
        delta = abs(target_duration - candidate_duration)
        if delta <= 5:
            score += 15
        elif delta <= 15:
            score += 10
        elif delta > 45:
            score -= 25

    # Bad keyword penalty (-40 pts)
    if is_bad_candidate(candidate_title) and not is_bad_candidate(target_title):
        score -= 40

    return max(0, min(100, score))

def process_single_entry(entry, query, target_duration=0):
    if not entry:
        return None
    video_id = entry.get('id', '')
    title = entry.get('title', '')
    uploader = entry.get('uploader', '')
    duration = entry.get('duration', 0)

    if duration and (duration < 30 or duration > 1200): # Filter out < 30s clips or > 20 min videos
        return None

    score = score_candidate(query, "", title, uploader, duration, target_duration)
    if score < 20 and is_bad_candidate(title):
        return None

    thumbnail = entry.get('thumbnail', f"https://i.ytimg.com/vi/{video_id}/hqdefault.jpg")

    return {
        'id': video_id,
        'title': title,
        'uploader': uploader,
        'duration': duration,
        'url': entry.get('url', f"https://www.youtube.com/watch?v={video_id}"),
        'thumbnail': thumbnail,
        'score': score
    }

def search_itunes_fast(query, max_results=20):
    try:
        q_encoded = urllib.parse.quote(query)
        url = f"https://itunes.apple.com/search?term={q_encoded}&media=music&entity=song&limit={max_results}"
        req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
        with urllib.request.urlopen(req, timeout=3) as response:
            data = json.loads(response.read().decode())
            results = []
            for item in data.get('results', []):
                title = item.get('trackName', 'Unknown')
                artist = item.get('artistName', 'Unknown')
                duration = item.get('trackTimeMillis', 0) // 1000
                thumb = item.get('artworkUrl100', '').replace('100x100bb', '600x600bb')
                results.append({
                    'id': str(item.get('trackId', '')),
                    'title': title,
                    'uploader': artist,
                    'duration': duration,
                    'url': f"https://www.youtube.com/results?search_query={urllib.parse.quote(f'{title} {artist}')}",
                    'thumbnail': thumb,
                    'score': 100
                })
            return results
    except Exception:
        return []

def search_youtube(query, max_results=20):
    ydl_opts = {
        'format': 'bestaudio/best',
        'extract_flat': 'in_playlist',
        'skip_download': True,
        'quiet': True,
        'no_warnings': True,
        'ignoreerrors': True,
        'nocheckcertificate': True,
    }
    
    try:
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            result = ydl.extract_info(f"ytsearch{max_results}:{query}", download=False)
            if result and 'entries' in result:
                raw_entries = [e for e in result['entries'] if e]
                entries = []
                
                with concurrent.futures.ThreadPoolExecutor(max_workers=10) as executor:
                    futures = [executor.submit(process_single_entry, entry, query) for entry in raw_entries]
                    for future in concurrent.futures.as_completed(futures):
                        item = future.result()
                        if item:
                            entries.append(item)

                if entries:
                    entries.sort(key=lambda x: x['score'], reverse=True)
                    return json.dumps(entries)

        fast_results = search_itunes_fast(query, max_results)
        return json.dumps(fast_results)
    except Exception as e:
        print(f"Search error: {e}")
        fast_results = search_itunes_fast(query, max_results)
        return json.dumps(fast_results)

def get_stream_url(url):
    ydl_opts = {
        'format': 'bestaudio/best',
        'quiet': True,
        'no_warnings': True,
        'ignoreerrors': True,
        'nocheckcertificate': True,
        'noplaylist': True,
        'cachedir': False,
        'socket_timeout': 5,
        'retries': 1,
        'extractor_args': {
            'youtube': {
                'player_client': ['android'],
                'skip': ['translated_subs', 'dash', 'hls', 'comments', 'description']
            }
        }
    }
    try:
        target_input = url.strip()
        if "results?search_query=" in target_input:
            parsed = urllib.parse.urlparse(target_input)
            params = urllib.parse.parse_qs(parsed.query)
            q = params.get('search_query', [''])[0]
            if q:
                target_input = f"ytsearch1:{q}"
        elif not target_input.startswith("http"):
            target_input = f"ytsearch1:{target_input}"

        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(target_input, download=False)
            if not info:
                return ""
            
            if 'entries' in info:
                entries = [e for e in info['entries'] if e]
                if not entries:
                    return ""
                info = entries[0]
                if not info.get('formats') and (info.get('webpage_url') or info.get('id')):
                    sub_url = info.get('webpage_url') or f"https://www.youtube.com/watch?v={info.get('id')}"
                    info = ydl.extract_info(sub_url, download=False)

            formats = info.get('formats', [])
            
            # 1. Look for genuine audio-only streams (m4a, webm/opus)
            audio_formats = [
                f for f in formats 
                if f.get('url') 
                and (f.get('acodec') and f.get('acodec') != 'none')
                and (f.get('vcodec') == 'none' or not f.get('vcodec'))
                and not f.get('format_id', '').startswith('sb')
                and f.get('ext') not in ['mhtml', 'jpg', 'jpeg', 'png', 'webp']
            ]

            direct_url = None
            if audio_formats:
                audio_formats.sort(
                    key=lambda f: (
                        1 if f.get('ext') == 'm4a' else 0,
                        f.get('abr') or f.get('tbr') or 0
                    ),
                    reverse=True
                )
                direct_url = audio_formats[0].get('url')

            # 2. Fallback to top-level URL or media formats
            if not direct_url:
                if info.get('url') and info.get('ext') not in ['mhtml', 'jpg', 'png', 'webp']:
                    direct_url = info.get('url')
                elif formats:
                    valid_media = [
                        f for f in formats 
                        if f.get('url') 
                        and not f.get('format_id', '').startswith('sb')
                        and f.get('ext') not in ['mhtml', 'jpg', 'png', 'webp']
                    ]
                    if valid_media:
                        direct_url = valid_media[-1].get('url')

            if direct_url:
                headers = info.get('http_headers', {})
                user_agent = headers.get('User-Agent', 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36')
                return json.dumps({
                    'url': direct_url,
                    'user_agent': user_agent,
                    'title': info.get('title', ''),
                    'uploader': info.get('uploader', ''),
                    'duration': info.get('duration', 0)
                })
        return ""
    except Exception as e:
        print(f"Stream URL error: {e}")
        return ""

def fetch_youtube_playlist(url, max_entries=500):
    ydl_opts = {
        'extract_flat': 'in_playlist',
        'skip_download': True,
        'quiet': True,
        'no_warnings': True,
        'playlistend': max_entries,
        'ignoreerrors': True,
        'nocheckcertificate': True,
    }
    try:
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(url, download=False)
            if not info:
                return json.dumps({'title': 'Imported Playlist', 'tracks': []})
            
            entries = info.get('entries', [])
            results = []
            for entry in entries:
                if not entry:
                    continue
                title = entry.get('title', 'Unknown Title')
                artist = entry.get('uploader') or entry.get('channel') or 'Unknown Artist'
                duration = entry.get('duration', 0)
                vid = entry.get('id', '')
                thumbnail = entry.get('thumbnail') or (f"https://i.ytimg.com/vi/{vid}/hqdefault.jpg" if vid else '')
                results.append({
                    'id': vid,
                    'title': title,
                    'artist': artist,
                    'duration': duration,
                    'thumbnail': thumbnail
                })
            
            playlist_title = info.get('title') or 'Imported YouTube Playlist'
            return json.dumps({
                'title': playlist_title,
                'tracks': results
            })
    except Exception as e:
        print(f"fetch_youtube_playlist error: {e}")
        return json.dumps({'title': 'Imported Playlist', 'tracks': []})
