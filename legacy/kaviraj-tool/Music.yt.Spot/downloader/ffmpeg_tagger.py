import uuid
from pathlib import Path
from typing import Tuple, Optional
from downloader.utils import run_command

try:
    import mutagen
    from mutagen.mp4 import MP4, MP4Cover
    from mutagen.id3 import ID3, TIT2, TPE1, TALB, TRCK, USLT, APIC, ID3NoHeaderError
    from mutagen.flac import FLAC, Picture
    MUTAGEN_AVAILABLE = True
except ImportError:
    MUTAGEN_AVAILABLE = False


def apply_native_metadata(
    audio_file: Path,
    title: str,
    artist: str,
    album: str,
    image_bytes: Optional[bytes] = None,
    lyrics_text: Optional[str] = None,
    track_number: Optional[int] = None,
) -> Tuple[bool, str]:
    """
    Applies track tags (Title, Artist, Album, Cover Art, Lyrics, Track Number) directly
    to audio files using Mutagen without re-encoding.
    Falls back to FFmpeg if Mutagen is unavailable.
    """
    if not audio_file.exists():
        return False, "Audio file does not exist"

    if not MUTAGEN_AVAILABLE:
        return apply_spotify_metadata(audio_file, title, artist, album)

    ext = audio_file.suffix.lower()

    try:
        if ext in [".m4a", ".mp4"]:
            tags = MP4(audio_file)
            tags["©nam"] = [title]
            tags["©ART"] = [artist]
            tags["©alb"] = [album]
            if track_number:
                tags["trkn"] = [(track_number, 0)]
            if lyrics_text:
                tags["©lyr"] = [lyrics_text]
            if image_bytes:
                fmt = MP4Cover.FORMAT_PNG if image_bytes.startswith(b"\x89PNG") else MP4Cover.FORMAT_JPEG
                tags["covr"] = [MP4Cover(image_bytes, imageformat=fmt)]
            tags.save()
            return True, "Applied MP4 native metadata & artwork"

        elif ext == ".mp3":
            try:
                tags = ID3(audio_file)
            except ID3NoHeaderError:
                tags = ID3()

            tags.add(TIT2(encoding=3, text=title))
            tags.add(TPE1(encoding=3, text=artist))
            tags.add(TALB(encoding=3, text=album))
            if track_number:
                tags.add(TRCK(encoding=3, text=str(track_number)))
            if lyrics_text:
                tags.add(USLT(encoding=3, lang="eng", desc="", text=lyrics_text))
            if image_bytes:
                mime = "image/png" if image_bytes.startswith(b"\x89PNG") else "image/jpeg"
                tags.add(APIC(encoding=3, mime=mime, type=3, desc="Cover", data=image_bytes))
            tags.save(audio_file)
            return True, "Applied ID3 native metadata & artwork"

        elif ext in [".opus", ".ogg", ".flac", ".webm"]:
            audio = mutagen.File(audio_file)
            if audio is not None:
                audio["TITLE"] = [title]
                audio["ARTIST"] = [artist]
                audio["ALBUM"] = [album]
                if track_number:
                    audio["TRACKNUMBER"] = [str(track_number)]
                if lyrics_text:
                    audio["LYRICS"] = [lyrics_text]

                if image_bytes:
                    pic = Picture()
                    pic.data = image_bytes
                    pic.type = 3
                    pic.mime = "image/png" if image_bytes.startswith(b"\x89PNG") else "image/jpeg"
                    if hasattr(audio, "add_picture"):
                        audio.add_picture(pic)
                    else:
                        import base64
                        audio["METADATA_BLOCK_PICTURE"] = [base64.b64encode(pic.write()).decode("ascii")]

                audio.save()

            # For .opus files, also attach picture stream via FFmpeg for Android MediaStore compatibility
            if image_bytes and ext == ".opus":
                try:
                    cov_tmp = audio_file.with_name(f"{audio_file.stem}_cov.jpg")
                    cov_tmp.write_bytes(image_bytes)
                    out_tmp = audio_file.with_name(f"{audio_file.stem}_pic_{uuid.uuid4().hex[:4]}.opus")
                    c_cmd = [
                        "ffmpeg", "-y",
                        "-i", str(audio_file),
                        "-i", str(cov_tmp),
                        "-map", "0:a",
                        "-map", "1:v",
                        "-c", "copy",
                        "-disposition:v:0", "attached_pic",
                        "-metadata:s:v:0", "title=Album cover",
                        "-metadata:s:v:0", "comment=Cover (front)",
                        str(out_tmp)
                    ]
                    c_code, _, _ = run_command(c_cmd)
                    if c_code == 0 and out_tmp.exists():
                        out_tmp.replace(audio_file)
                    if cov_tmp.exists():
                        cov_tmp.unlink()
                except Exception:
                    pass


            return True, f"Applied {ext} Vorbis metadata & artwork"



        # Fallback for unrecognized extension
        return apply_spotify_metadata(audio_file, title, artist, album)

    except Exception as e:
        # Fallback to FFmpeg on Mutagen error
        return apply_spotify_metadata(audio_file, title, artist, album)


def apply_spotify_metadata(audio_file: Path, title: str, artist: str, album: str) -> Tuple[bool, str]:
    """Injects title, artist, album metadata using FFmpeg stream copy (-c copy)."""
    if not audio_file.exists():
        return False, "Audio file does not exist"
    temp_file = audio_file.with_name(f"{audio_file.stem}.meta_{uuid.uuid4().hex[:6]}{audio_file.suffix}")
    cmd = ["ffmpeg", "-y", "-i", str(audio_file), "-map", "0", "-c", "copy", "-metadata", f"title={title}", "-metadata", f"artist={artist}", "-metadata", f"album={album}", "-metadata", "genre=Music", str(temp_file)]
    code, _, stderr = run_command(cmd)
    if code == 0 and temp_file.exists():
        try:
            temp_file.replace(audio_file)
            return True, "Metadata written via FFmpeg"
        except Exception as e:
            if temp_file.exists():
                temp_file.unlink()
            return False, str(e)
    if temp_file.exists():
        temp_file.unlink()
    return False, stderr


def crop_square_artwork(image_path: Path) -> Tuple[bool, str]:
    """Crops artwork (.webp / .jpg) to a 1:1 square aspect ratio using FFmpeg."""
    if not image_path or not image_path.exists():
        return False, "Image file not found"
    temp_crop = image_path.with_name(f"{image_path.stem}.crop_{uuid.uuid4().hex[:6]}{image_path.suffix}")
    cmd = ["ffmpeg", "-y", "-i", str(image_path), "-vf", "crop='min(iw,ih):min(iw,ih)'", str(temp_crop)]
    code, _, _ = run_command(cmd)
    if code == 0 and temp_crop.exists():
        try:
            temp_crop.replace(image_path)
            return True, "Square crop successful"
        except Exception:
            pass
    if temp_crop.exists():
        temp_crop.unlink()
    return False, "FFmpeg crop failed"

