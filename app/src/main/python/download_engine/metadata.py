import os
import json
import urllib.request
import urllib.parse
from mutagen.mp4 import MP4, MP4Cover
from mutagen.id3 import ID3, TIT2, TPE1, TALB, APIC, USLT, ID3NoHeaderError
import mutagen

from download_engine.lyrics import fetch_lyrics

def fetch_lrclib_lyrics(title, artist, duration_sec):
    return fetch_lyrics(title, artist, duration_sec)

def inject_metadata(filepath, title, artist, album, cover_art_path=None):
    try:
        if not os.path.exists(filepath):
            return [0, 120.0, "", ""]
            
        base, ext = os.path.splitext(filepath)
        ext = ext.lower()
        auto_thumb = base + ".jpg"
        if not cover_art_path or not os.path.exists(cover_art_path):
            if os.path.exists(auto_thumb):
                cover_art_path = auto_thumb
            elif os.path.exists(base + ".webp"):
                cover_art_path = base + ".webp"
            elif os.path.exists(base + ".png"):
                cover_art_path = base + ".png"

        image_bytes = None
        if cover_art_path and os.path.exists(cover_art_path):
            with open(cover_art_path, 'rb') as f:
                image_bytes = f.read()

        # Extract duration
        audio_file = mutagen.File(filepath)
        duration_sec = int(audio_file.info.length) if audio_file else 0
        
        # Fetch actual lyrics
        lyrics_path = base + ".lrc"
        lyrics_text = ""
        if os.path.exists(lyrics_path):
            try:
                with open(lyrics_path, 'r', encoding='utf-8') as f:
                    lyrics_text = f.read()
            except Exception:
                pass
        
        if not lyrics_text:
            lyrics_text = fetch_lrclib_lyrics(title, artist, duration_sec)
            if lyrics_text:
                with open(lyrics_path, 'w', encoding='utf-8') as f:
                    f.write(lyrics_text)
        
        if not os.path.exists(lyrics_path):
            lyrics_path = ""

        # Apply Mutagen Tags natively
        if ext in [".m4a", ".mp4"]:
            tags = MP4(filepath)
            tags["\xa9nam"] = [title]
            tags["\xa9ART"] = [artist]
            tags["\xa9alb"] = [album]
            if lyrics_text:
                tags["\xa9lyr"] = [lyrics_text]
            if image_bytes:
                fmt = MP4Cover.FORMAT_PNG if image_bytes.startswith(b"\x89PNG") else MP4Cover.FORMAT_JPEG
                tags["covr"] = [MP4Cover(image_bytes, imageformat=fmt)]
            tags.save()

        elif ext == ".mp3":
            try:
                tags = ID3(filepath)
            except ID3NoHeaderError:
                tags = ID3()
            tags.add(TIT2(encoding=3, text=title))
            tags.add(TPE1(encoding=3, text=artist))
            tags.add(TALB(encoding=3, text=album))
            if lyrics_text:
                tags.add(USLT(encoding=3, lang="eng", desc="", text=lyrics_text))
            if image_bytes:
                mime = "image/png" if image_bytes.startswith(b"\x89PNG") else "image/jpeg"
                tags.add(APIC(encoding=3, mime=mime, type=3, desc="Cover", data=image_bytes))
            tags.save(filepath)

        elif ext in [".opus", ".ogg", ".flac", ".webm"]:
            if audio_file:
                audio_file["TITLE"] = [title]
                audio_file["ARTIST"] = [artist]
                audio_file["ALBUM"] = [album]
                if lyrics_text:
                    audio_file["LYRICS"] = [lyrics_text]
                audio_file.save()

        bpm = 0.0
        
        return [duration_sec, bpm, cover_art_path if cover_art_path else "", lyrics_path]
    except Exception as e:
        print(f"Metadata injection error: {e}")
        return [0, 0.0, "", ""]
