import json
from difflib import SequenceMatcher

def normalize(text):
    if not text:
        return ""
    return "".join(c.lower() for c in text if c.isalnum() or c.isspace()).strip()

def similarity(title: str, candidate: str) -> float:
    a_norm = normalize(title)
    b_norm = normalize(candidate)
    if not a_norm or not b_norm:
        return 0.0
    return SequenceMatcher(None, a_norm, b_norm).ratio() * 100

def search_youtube(query: str, count: int = 5):
    try:
        import yt_dlp
        ydl_opts = {
            "extract_flat": True,
            "skip_download": True,
            "quiet": True,
            "no_warnings": True,
            "ignoreerrors": True,
        }
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            res = ydl.extract_info(f"ytsearch{count}:{query}", download=False)
            if res and "entries" in res:
                entries = []
                for e in res["entries"]:
                    if e:
                        entries.append({
                            "id": e.get("id"),
                            "title": e.get("title"),
                            "uploader": e.get("uploader"),
                            "duration": e.get("duration"),
                            "url": e.get("url") or (f"https://www.youtube.com/watch?v={e.get('id')}" if e.get("id") else None)
                        })
                return json.dumps({"status": "success", "results": entries})
        return json.dumps({"status": "success", "results": []})
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)})
