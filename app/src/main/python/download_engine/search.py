import json
import yt_dlp

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
                        entries.append({
                            'id': video_id,
                            'title': entry.get('title', ''),
                            'uploader': entry.get('uploader', ''),
                            'duration': entry.get('duration', 0),
                            'url': entry.get('url', f"https://www.youtube.com/watch?v={video_id}"),
                            'thumbnail': entry.get('thumbnail', f"https://i.ytimg.com/vi/{video_id}/hqdefault.jpg") if video_id else ''
                        })
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
    }
    try:
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(url, download=False)
            if info and 'url' in info:
                return info['url']
            elif info and 'entries' in info and len(info['entries']) > 0:
                return info['entries'][0]['url']
        return ""
    except Exception as e:
        print(f"Stream URL error: {e}")
        return ""
