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
                        entries.append({
                            'id': entry.get('id', ''),
                            'title': entry.get('title', ''),
                            'uploader': entry.get('uploader', ''),
                            'duration': entry.get('duration', 0),
                            'url': entry.get('url', f"https://www.youtube.com/watch?v={entry.get('id', '')}")
                        })
                return json.dumps(entries)
            return "[]"
    except Exception as e:
        print(f"Search error: {e}")
        return "[]"
