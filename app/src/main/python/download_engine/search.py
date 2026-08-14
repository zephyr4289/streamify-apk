import json
import yt_dlp
import urllib.request
import urllib.parse
import concurrent.futures

def fetch_itunes_cover_art(title, artist):
    try:
        query = urllib.parse.quote(f"{title} {artist}")
        url = f"https://itunes.apple.com/search?term={query}&limit=1&entity=song"
        req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
        with urllib.request.urlopen(req, timeout=2) as response:
            data = json.loads(response.read().decode())
            if data.get('resultCount', 0) > 0:
                art = data['results'][0].get('artworkUrl100', '')
                return art.replace('100x100bb', '600x600bb')
    except Exception as e:
        pass
    return ""

def is_bad_candidate(title):
    bad_words = ['slowed', 'reverb', 'nightcore', 'cover', 'karaoke', 'instrumental', '8d', 'reaction', 'bass boosted', 'episode', 'season', 'explained', 'review', 'trailer']
    t_lower = title.lower()
    return any(word in t_lower for word in bad_words)

def is_likely_music(title, duration):
    if duration and (duration < 30 or duration > 900): # 15 mins
        return False
    return True

def process_single_entry(entry, query):
    if not entry:
        return None
    video_id = entry.get('id', '')
    title = entry.get('title', '')
    uploader = entry.get('uploader', '')

    duration = entry.get('duration', 0)
    if not is_likely_music(title, duration):
        return None

    if is_bad_candidate(title) and not is_bad_candidate(query):
        return None

    score = 0
    if "- Topic" in uploader or "VEVO" in uploader:
        score += 50

    thumbnail = entry.get('thumbnail', f"https://i.ytimg.com/vi/{video_id}/hqdefault.jpg")

    return {
        'id': video_id,
        'title': title,
        'uploader': uploader,
        'duration': entry.get('duration', 0),
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
        'extractor_args': {
            'youtube': {
                'player_client': ['android', 'web'],
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
