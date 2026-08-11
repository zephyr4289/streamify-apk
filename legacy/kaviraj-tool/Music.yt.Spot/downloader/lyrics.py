import json
import re
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Tuple, Union, Optional


def clean_artist_name(artist: str) -> str:
    """Strips feat/ft/collaborators to get primary artist for LRCLIB search."""
    if not artist:
        return ""
    primary = re.split(r",| feat\.| ft\.|&", artist, flags=re.IGNORECASE)[0]
    return primary.strip()


def fetch_lyrics(
    title: str, artist: str, album: str, output_audio_path: Path, duration_sec: Optional[int] = None
) -> Tuple[bool, Union[Path, str], Optional[str]]:
    """Queries LRCLIB REST API for synchronized (.lrc) or plain lyrics."""
    if not title or not output_audio_path:
        return False, "Missing track details", None
    headers = {"User-Agent": "MusicYtSpot-Termux/2.0 (https://github.com/Zoro-15/Music.yt.Spot)"}
    primary_artist = clean_artist_name(artist)
    clean_title = re.sub(r"\(feat\.[^\)]+\)", "", title, flags=re.IGNORECASE).strip()

    urls = []
    if duration_sec and duration_sec > 0:
        urls.append(
            f"https://lrclib.net/api/get?track_name={urllib.parse.quote(clean_title)}&artist_name={urllib.parse.quote(primary_artist)}&duration={duration_sec}"
        )
    urls.append(f"https://lrclib.net/api/get?track_name={urllib.parse.quote(clean_title)}&artist_name={urllib.parse.quote(primary_artist)}")
    urls.append(f"https://lrclib.net/api/search?q={urllib.parse.quote(f'{clean_title} {primary_artist}')}")

    for url in urls:
        try:
            req = urllib.request.Request(url, headers=headers)
            with urllib.request.urlopen(req, timeout=8) as resp:
                if resp.status == 200:
                    data = json.loads(resp.read().decode("utf-8"))
                    items = data if isinstance(data, list) else [data]
                    for item in items:
                        lyrics = item.get("syncedLyrics") or item.get("plainLyrics")
                        if lyrics:
                            ok, path = _save_lrc(output_audio_path, lyrics)
                            return ok, path, lyrics
        except Exception:
            pass
    return False, "No lyrics found on LRCLIB", None



def _save_lrc(output_audio_path: Path, lyrics_text: str) -> Tuple[bool, Path]:
    """Helper to write lyrics to .lrc file next to audio track."""
    lrc_path = Path(output_audio_path).with_suffix(".lrc")
    with open(lrc_path, "w", encoding="utf-8") as f:
        f.write(lyrics_text)
    return True, lrc_path
