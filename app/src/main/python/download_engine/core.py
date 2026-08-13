import json
import os
import yt_dlp

class DownloadProgressHook:
    def __init__(self, callback_java):
        self.callback_java = callback_java

    def __call__(self, d):
        if d['status'] == 'downloading':
            try:
                # Remove ANSI escape sequences from strings
                percent_str = d.get('_percent_str', '0%').replace('\x1b[0;94m', '').replace('\x1b[0m', '').strip()
                speed_str = d.get('_speed_str', '0KiB/s').replace('\x1b[0;32m', '').replace('\x1b[0m', '').strip()
                eta_str = d.get('_eta_str', '00:00').replace('\x1b[0;33m', '').replace('\x1b[0m', '').strip()
                
                self.callback_java.onProgress(percent_str, speed_str, eta_str)
            except Exception as e:
                print(f"Error in python hook: {e}")
        elif d['status'] == 'error':
            try:
                self.callback_java.onError(str(d.get('error', 'Unknown error')))
            except Exception as e:
                print(f"Error in python hook: {e}")

def download_audio(url, output_path, callback_java, preferred_quality="320"):
    ydl_opts = {
        'format': 'bestaudio/best',
        'outtmpl': os.path.join(output_path, '%(title)s.%(ext)s'),
        'progress_hooks': [DownloadProgressHook(callback_java)],
        'quiet': True,
        'no_warnings': True,
        'writethumbnail': True,
        'nocheckcertificate': True,
    }

    try:
        import requests
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(url, download=True)
            filename = ydl.prepare_filename(info)
            base, _ = os.path.splitext(filename)
            thumb_path = base + ".jpg"

            # Download thumbnail image if missing or not written
            if not os.path.exists(thumb_path) and info and info.get('thumbnail'):
                try:
                    resp = requests.get(info.get('thumbnail'), timeout=5)
                    if resp.status_code == 200:
                        with open(thumb_path, 'wb') as f:
                            f.write(resp.content)
                except Exception as ex:
                    print(f"Thumbnail download failed: {ex}")

            final_file = filename
            if not os.path.exists(final_file):
                for ext in ['.m4a', '.mp3', '.webm', '.opus', '.ogg', '.flac']:
                    if os.path.exists(base + ext):
                        final_file = base + ext
                        break

            callback_java.onFinished(final_file)
            return True
    except Exception as e:
        print(f"Download exception: {e}")
        try:
            files = [os.path.join(output_path, f) for f in os.listdir(output_path) if f.endswith(('.mp3', '.m4a', '.webm', '.opus'))]
            if files:
                latest = max(files, key=os.path.getmtime)
                callback_java.onFinished(latest)
                return True
        except Exception:
            pass
        callback_java.onError(str(e))
        return False
