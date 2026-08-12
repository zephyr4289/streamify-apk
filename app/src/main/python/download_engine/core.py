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
        elif d['status'] == 'finished':
            self.callback_java.onFinished(d['filename'])
        elif d['status'] == 'error':
            self.callback_java.onError(str(d.get('error', 'Unknown error')))

def download_audio(url, output_path, callback_java):
    ydl_opts = {
        'format': 'bestaudio/best',
        'outtmpl': os.path.join(output_path, '%(title)s.%(ext)s'),
        'postprocessors': [{
            'key': 'FFmpegExtractAudio',
            'preferredcodec': 'mp3',
            'preferredquality': '192',
        }],
        'progress_hooks': [DownloadProgressHook(callback_java)],
        'quiet': True,
        'no_warnings': True,
    }

    try:
        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            ydl.download([url])
            return True
    except Exception as e:
        callback_java.onError(str(e))
        return False
