import os
from mutagen.mp3 import MP3
from mutagen.id3 import ID3, TIT2, TPE1, TALB, APIC

def inject_metadata(filepath, title, artist, album, cover_art_path=None):
    try:
        if not os.path.exists(filepath):
            return False
            
        audio = MP3(filepath, ID3=ID3)
        if audio.tags is None:
            audio.add_tags()
            
        audio.tags.add(TIT2(encoding=3, text=title))
        audio.tags.add(TPE1(encoding=3, text=artist))
        audio.tags.add(TALB(encoding=3, text=album))
        
        if cover_art_path and os.path.exists(cover_art_path):
            with open(cover_art_path, 'rb') as img_in:
                audio.tags.add(
                    APIC(
                        encoding=3,
                        mime='image/jpeg',
                        type=3, # 3 is for cover front
                        desc=u'Cover',
                        data=img_in.read()
                    )
                )
                
        audio.save()
        return True
    except Exception as e:
        print(f"Metadata injection error: {e}")
        return False
