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
    bad_words = ['slowed', 'reverb', 'nightcore', 'cover', 'karaoke', 'instrumental', '8d', 'reaction', 'bass boosted']
    t_lower = title.lower()
    return any(word in t_lower for word in bad_words)

def process_single_entry(entry, query):
    if not entry:
        return None
    video_id = entry.get('id', '')
    title = entry.get('title', '')
    uploader = entry.get('uploader', '')

    if is_bad_candidate(title) and not is_bad_candidate(query):
        return None

    score = 0
    if "- Topic" in uploader or "VEVO" in uploader:
        score += 50

    clean_uploader = uploader.replace(" - Topic", "").replace("VEVO", "").strip()
    hd_cover = fetch_itunes_cover_art(title, clean_uploader)
    thumbnail = hd_cover if hd_cover else entry.get('thumbnail', f"https://i.ytimg.com/vi/{video_id}/hqdefault.jpg")

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
                yt_query = urllib.parse.quote(f"{title} {artist}")
                yt_url = f"https://www.youtube.com/watch?v={item.get('trackId', '')}"
                results.append({
                    'id': str(item.get('trackId', '')),
                    'title': title,
                    'uploader': artist,
                    'duration': duration,
                    'url': yt_url,
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
    }
    try:
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(url, download=False)
            if info:
                if 'entries' in info and len(info['entries']) > 0:
                    info = info['entries'][0]
                target_url = info.get('url', '')
                if target_url:
                    headers = info.get('http_headers', {})
                    user_agent = headers.get('User-Agent', 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36')
                    return json.dumps({
                        'url': target_url,
                        'user_agent': user_agent
                    })
        return ""
    except Exception as e:
        print(f"Stream URL error: {e}")
        return ""

