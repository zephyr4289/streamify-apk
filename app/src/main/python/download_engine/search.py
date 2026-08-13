import json
import yt_dlp

import urllib.request
import urllib.parse

def fetch_itunes_cover_art(title, artist):
    try:
        query = urllib.parse.quote(f"{title} {artist}")
        url = f"https://itunes.apple.com/search?term={query}&limit=1&entity=song"
        req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
        with urllib.request.urlopen(req, timeout=3) as response:
            data = json.loads(response.read().decode())
            if data.get('resultCount', 0) > 0:
                art = data['results'][0].get('artworkUrl100', '')
                return art.replace('100x100bb', '1400x1400bb')
    except Exception as e:
        print(f"iTunes API Error: {e}")
    return ""

def is_bad_candidate(title):
    bad_words = ['slowed', 'reverb', 'nightcore', 'cover', 'karaoke', 'instrumental', '8d', 'reaction', 'bass boosted']
    t_lower = title.lower()
    return any(word in t_lower for word in bad_words)

def search_youtube(query, max_results=20):
    ydl_opts = {
        'format': 'bestaudio/best',
        'extract_flat': 'in_playlist',
        'skip_download': True,
        'quiet': True,
        'no_warnings': True,
        'ignoreerrors': True,
    }
    
    try:
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            result = ydl.extract_info(f"ytsearch{max_results}:{query}", download=False)
            if 'entries' in result:
                entries = []
                for entry in result['entries']:
                    if entry:
                        video_id = entry.get('id', '')
                        title = entry.get('title', '')
                        uploader = entry.get('uploader', '')
                        
                        # Anti-remix filter: skip bad candidates unless user explicitly searched for them
                        if is_bad_candidate(title) and not is_bad_candidate(query):
                            continue
                            
                        # Boost official audio channels by placing them at the top
                        score = 0
                        if "- Topic" in uploader or "VEVO" in uploader:
                            score += 50
                            
                        # Try to get iTunes HD cover
                        hd_cover = fetch_itunes_cover_art(title, uploader.replace(" - Topic", "").replace("VEVO", ""))
                        thumbnail = hd_cover if hd_cover else entry.get('thumbnail', f"https://i.ytimg.com/vi/{video_id}/hqdefault.jpg")

                        entries.append({
                            'id': video_id,
                            'title': title,
                            'uploader': uploader,
                            'duration': entry.get('duration', 0),
                            'url': entry.get('url', f"https://www.youtube.com/watch?v={video_id}"),
                            'thumbnail': thumbnail,
                            'score': score
                        })
                
                # Sort by score descending
                entries.sort(key=lambda x: x['score'], reverse=True)
                return json.dumps(entries)
            return "[]"
    except Exception as e:
        print(f"Search error: {e}")
        return "[]"

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

