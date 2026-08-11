import os
import json

def download_track(url: str, output_dir: str):
    try:
        import yt_dlp
        os.makedirs(output_dir, exist_ok=True)
        output_template = os.path.join(output_dir, "%(title)s.%(ext)s")
        
        ydl_opts = {
            "format": "bestaudio/best",
            "outtmpl": output_template,
            "postprocessors": [{
                "key": "FFmpegExtractAudio",
                "preferredcodec": "mp3",
                "preferredquality": "192",
            }],
            "quiet": True,
            "no_warnings": True,
        }
        
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(url, download=True)
            if info:
                title = info.get("title", "Unknown Title")
                artist = info.get("uploader", "Unknown Artist")
                duration = info.get("duration", 180)
                # The postprocessor changes extension to mp3
                filepath = os.path.join(output_dir, f"{title}.mp3")
                return json.dumps({
                    "status": "success",
                    "filepath": filepath,
                    "title": title,
                    "artist": artist,
                    "duration": duration
                })
        return json.dumps({"status": "error", "message": "Failed to extract info"})
    except Exception as e:
        return json.dumps({"status": "error", "message": str(e)})
