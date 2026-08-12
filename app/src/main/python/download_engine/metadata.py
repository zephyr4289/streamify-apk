import os
from mutagen.mp3 import MP3
from mutagen.id3 import ID3, TIT2, TPE1, TALB, APIC

def inject_metadata(filepath, title, artist, album, cover_art_path=None):
    try:
        if not os.path.exists(filepath):
            return [0, 120.0, ""]
            
        base, _ = os.path.splitext(filepath)
        auto_thumb = base + ".jpg"
        if not cover_art_path or not os.path.exists(cover_art_path):
            if os.path.exists(auto_thumb):
                cover_art_path = auto_thumb
            elif os.path.exists(base + ".webp"):
                cover_art_path = base + ".webp"
            elif os.path.exists(base + ".png"):
                cover_art_path = base + ".png"

        audio = MP3(filepath, ID3=ID3)
        if audio.tags is None:
            audio.add_tags()
            
        audio.tags.add(TIT2(encoding=3, text=title))
        audio.tags.add(TPE1(encoding=3, text=artist))
        audio.tags.add(TALB(encoding=3, text=album))
        
        if cover_art_path and os.path.exists(cover_art_path):
            with open(cover_art_path, 'rb') as img_in:
                mime_type = 'image/jpeg'
                if cover_art_path.endswith('.png'): mime_type = 'image/png'
                elif cover_art_path.endswith('.webp'): mime_type = 'image/webp'
                audio.tags.add(
                    APIC(
                        encoding=3,
                        mime=mime_type,
                        type=3, # 3 is for cover front
                        desc=u'Cover',
                        data=img_in.read()
                    )
                )
                
        audio.save()
        
        # Extract true duration using Mutagen
        duration_sec = int(audio.info.length)
        
        # Pseudo-calculate BPM based on file size hash
        size = os.path.getsize(filepath)
        bpm = 90.0 + (size % 500) / 10.0
        
        # Generate mock LRC file for immersive UI
        lyrics_path = base + ".lrc"
        try:
            with open(lyrics_path, 'w', encoding='utf-8') as lrc_file:
                lrc_file.write(f"[00:00.00] {title}\n")
                lrc_file.write(f"[00:05.00] By {artist}\n")
                lrc_file.write("[00:10.00] ♫ (Music playing) ♫\n")
                
                # Add dummy lines every 15 seconds
                for i in range(15, min(duration_sec, 300), 15):
                    m = i // 60
                    s = i % 60
                    lrc_file.write(f"[{m:02d}:{s:02d}.00] ♫ Immersive generated lyrics ♫\n")
        except Exception as ex:
            print(f"Lyrics generation error: {ex}")
            lyrics_path = ""
        
        return [duration_sec, bpm, cover_art_path if cover_art_path else "", lyrics_path]
    except Exception as e:
        print(f"Metadata injection error: {e}")
        return [0, 120.0, ""]
