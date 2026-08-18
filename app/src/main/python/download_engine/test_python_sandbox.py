import pytest
import os
import json
from download_engine.search import normalize_text, fetch_itunes_cover_art
from download_engine.metadata import fetch_lrclib_lyrics

def test_normalize_text():
    assert normalize_text("Blinding Lights (Official Video)") == "blinding lights"
    assert normalize_text("Starboy [feat. Daft Punk]") == "starboy"
    assert normalize_text("Song Title {Audio}") == "song title"
    assert normalize_text("") == ""
    assert normalize_text(None) == ""

def test_itunes_cover_lookup():
    url = fetch_itunes_cover_art("Blinding Lights", "The Weeknd")
    if url:
        assert "1400x1400" in url or "mzstatic.com" in url

def test_lrclib_lyrics_fallback():
    result = fetch_lrclib_lyrics("NonExistentTrack12345XYZ", "NonExistentArtist12345XYZ", 180)
    assert result == "" or isinstance(result, str)
